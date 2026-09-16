"""travel.optimization.completed: envelope shape, broker config, and 'no event => no result'."""

from __future__ import annotations

import re
from datetime import UTC, datetime

import grpc
import pytest
from test_service import bundle, ctx

from travelos.optimization.v1 import optimization_pb2, optimization_pb2_grpc
from travelos_optimization import events
from travelos_optimization.server import build_server

ULID = r"[0-9A-HJKMNP-TV-Z]{26}"


def test_envelope_matches_the_contract():
    now = datetime(2026, 9, 12, 21, 15, 36, 373282, tzinfo=UTC)
    e = events.envelope(
        "travel.optimization.completed",
        "acme",
        "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV",
        {"optimizationRunId": "opt_x"},
        causation_id="cmd_1",
        now=now,
    )
    assert re.fullmatch(rf"evt_{ULID}", e["eventId"])
    assert list(e) == [
        "eventId",
        "eventType",
        "eventVersion",
        "occurredAt",
        "tenantId",
        "correlationId",
        "causationId",
        "producer",
        "data",
    ]
    assert e["occurredAt"] == "2026-09-12T21:15:36.373282Z"
    assert e["producer"] == "optimization" and e["eventVersion"] == 1
    assert events.topic_of(e["eventType"]) == "travel.optimization"


def test_envelope_omits_causation_when_unknown():
    e = events.envelope("travel.optimization.completed", "acme", "trip_1", {})
    assert "causationId" not in e


def test_kafka_config_plaintext_by_default():
    cfg = events.kafka_config({"KAFKA_BOOTSTRAP_SERVERS": "kafka:9092"})
    assert cfg["bootstrap.servers"] == "kafka:9092"
    assert cfg["acks"] == "all" and cfg["enable.idempotence"] is True
    assert "security.protocol" not in cfg


def test_kafka_config_msk_iam_is_sasl_ssl_with_a_signed_token_callback():
    cfg = events.kafka_config(
        {"KAFKA_BOOTSTRAP_SERVERS": "b-1:9098", "KAFKA_AUTH": "msk-iam", "AWS_REGION": "us-east-1"}
    )
    assert cfg["security.protocol"] == "SASL_SSL"
    assert cfg["sasl.mechanisms"] == "OAUTHBEARER"
    assert callable(cfg["oauth_cb"])


def test_kafka_config_msk_iam_requires_a_region():
    with pytest.raises(ValueError, match="AWS_REGION"):
        events.kafka_config({"KAFKA_BOOTSTRAP_SERVERS": "b-1:9098", "KAFKA_AUTH": "msk-iam"})


def test_kafka_config_rejects_unknown_modes():
    with pytest.raises(ValueError, match="kerberos"):
        events.kafka_config({"KAFKA_BOOTSTRAP_SERVERS": "k:9092", "KAFKA_AUTH": "kerberos"})


def test_no_bootstrap_servers_means_noop():
    assert isinstance(events.publisher_from_env({}), events.NoopPublisher)


def test_librdkafka_accepts_the_msk_iam_configuration():
    """The strings are only real if librdkafka builds a producer from them (no broker contacted).

    The token callback is replaced: the real one signs with AWS credentials the test host lacks.
    """
    import time

    from confluent_kafka import Producer

    cfg = events.kafka_config(
        {"KAFKA_BOOTSTRAP_SERVERS": "b-1:9098", "KAFKA_AUTH": "msk-iam", "AWS_REGION": "us-east-1"}
    )
    cfg["oauth_cb"] = lambda _c: ("signed-token", time.time() + 300)
    producer = Producer(cfg)
    producer.poll(0)  # drives the OAUTHBEARER token refresh through our callback
    assert producer is not None


@pytest.fixture
def recording():
    rec = events.RecordingPublisher()
    server, port = build_server(0, publisher=rec)
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    yield rec, optimization_pb2_grpc.OptimizationServiceStub(channel)
    channel.close()
    server.stop(grace=None)


def _request(trip_id="trip_01ARZ3NDEKTSV4RRFFQ69G5FAV"):
    c = ctx()
    c.causation_id = "cmd_01ARZ3NDEKTSV4RRFFQ69G5FAW"
    return optimization_pb2.OptimizeTripRequest(
        ctx=c,
        trip_id=trip_id,
        candidates=[
            bundle("bdl_a", 52000, [("DL", "BOS", "SEA", 380, 0)]),
            bundle("bdl_b", 41000, [("UA", "BOS", "ORD", 160, 0), ("UA", "ORD", "SEA", 260, 70)]),
        ],
        constraints=optimization_pb2.ConstraintSet(),
        preferences=optimization_pb2.OptimizationPreferences(),
    )


def test_every_optimization_publishes_exactly_one_completed_event(recording):
    rec, stub = recording
    response = stub.OptimizeTrip(_request())
    assert len(rec.events) == 1
    e = rec.events[0]
    assert e["eventType"] == "travel.optimization.completed"
    assert e["tenantId"] == "acme"
    assert e["correlationId"] == "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV"
    assert e["causationId"] == "cmd_01ARZ3NDEKTSV4RRFFQ69G5FAW"
    d = e["data"]
    assert d["optimizationRunId"] == response.optimization_run_id
    assert d["tripId"] == "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV"
    assert d["candidatesEvaluated"] == 2 and d["feasibleCandidates"] == 2
    assert d["selectedBundleId"] == response.selected_bundle_id
    assert 0 <= d["selectedScore"] <= 100
    assert d["solver"] == response.solver and d["solveTimeMs"] == response.solve_time_ms
    # additionalProperties: false in the schema — nothing else may leak in
    assert set(d) <= {
        "optimizationRunId",
        "tripId",
        "selectedBundleId",
        "selectedScore",
        "candidatesEvaluated",
        "feasibleCandidates",
        "solver",
        "solveTimeMs",
        "learning",
        "candidates",
    }


def test_a_run_whose_event_cannot_be_published_is_not_returned():
    rec = events.RecordingPublisher(fail_with=events.PublishError("broker down"))
    server, port = build_server(0, publisher=rec)
    server.start()
    try:
        stub = optimization_pb2_grpc.OptimizationServiceStub(
            grpc.insecure_channel(f"localhost:{port}")
        )
        with pytest.raises(grpc.RpcError) as err:
            stub.OptimizeTrip(_request())
        assert err.value.code() == grpc.StatusCode.UNAVAILABLE
        assert "OPTIMIZATION_EVENT_NOT_PUBLISHED" in err.value.details()
    finally:
        server.stop(grace=None)
