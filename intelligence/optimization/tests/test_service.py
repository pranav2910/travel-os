"""In-process gRPC: real server, real stubs generated from contracts/protobuf."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import grpc
import pytest
from google.protobuf import timestamp_pb2

from travelos.common.v1 import common_pb2
from travelos.offer.v1 import offer_pb2
from travelos.optimization.v1 import optimization_pb2, optimization_pb2_grpc
from travelos_optimization.server import build_server

T0 = datetime(2026, 10, 6, 8, 0, tzinfo=UTC)


@pytest.fixture(scope="module")
def stub():
    server, port = build_server(0)
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    yield optimization_pb2_grpc.OptimizationServiceStub(channel)
    channel.close()
    server.stop(grace=None)


def ts(dt: datetime) -> timestamp_pb2.Timestamp:
    t = timestamp_pb2.Timestamp()
    t.FromDatetime(dt)
    return t


def ctx() -> common_pb2.RequestContext:
    return common_pb2.RequestContext(
        tenant_id="acme",
        correlation_id="trip_01ARZ3NDEKTSV4RRFFQ69G5FAV",
        principal=common_pb2.Principal(kind=common_pb2.Principal.AGENT, id="agent/trip-planner/v1"),
    )


def bundle(
    bundle_id: str, cents: int, legs, cabin=common_pb2.ECONOMY, start=T0
) -> offer_pb2.Bundle:
    segments = []
    clock = start
    for carrier, origin, destination, minutes, layover in legs:
        clock += timedelta(minutes=layover)
        segments.append(
            offer_pb2.FlightSegment(
                carrier=carrier,
                origin=origin,
                destination=destination,
                departure=ts(clock),
                arrival=ts(clock + timedelta(minutes=minutes)),
                cabin=cabin,
            )
        )
        clock += timedelta(minutes=minutes)
    offer = offer_pb2.Offer(
        offer_id=f"off_{bundle_id}",
        provider="sandbox-air",
        type=offer_pb2.AIR,
        total=common_pb2.Money(currency="USD", amount_minor=cents),
        air=offer_pb2.AirOffer(outbound=offer_pb2.Journey(segments=segments)),
    )
    return offer_pb2.Bundle(
        bundle_id=bundle_id,
        offers=[offer],
        total=common_pb2.Money(currency="USD", amount_minor=cents),
    )


def test_optimize_returns_full_ranking_with_breakdowns(stub):
    request = optimization_pb2.OptimizeTripRequest(
        ctx=ctx(),
        trip_id="trip_01ARZ3NDEKTSV4RRFFQ69G5FAV",
        candidates=[
            bundle("bdl_nonstop", 52000, [("DL", "BOS", "SEA", 380, 0)]),
            bundle(
                "bdl_onestop", 41000, [("UA", "BOS", "ORD", 160, 0), ("UA", "ORD", "SEA", 260, 70)]
            ),
            bundle(
                "bdl_business", 130000, [("DL", "BOS", "SEA", 380, 0)], cabin=common_pb2.BUSINESS
            ),
            bundle(
                "bdl_late", 30000, [("B6", "BOS", "SEA", 380, 0)], start=T0 + timedelta(hours=9)
            ),
        ],
        constraints=optimization_pb2.ConstraintSet(
            arrival_deadline=ts(T0 + timedelta(hours=9)),
            allowed_cabins=[common_pb2.ECONOMY],
            max_stops=1,
        ),
        preferences=optimization_pb2.OptimizationPreferences(preferred_carriers=["DL"]),
    )
    response = stub.OptimizeTrip(request)

    assert response.optimization_run_id.startswith("opt_")
    assert response.solver.startswith("ortools-cpsat")
    ranked = {r.bundle_id: r for r in response.ranking}
    assert ranked["bdl_business"].feasible is False
    assert list(ranked["bdl_business"].infeasibility_reasons) == ["CABIN_NOT_ALLOWED"]
    assert ranked["bdl_late"].feasible is False
    assert list(ranked["bdl_late"].infeasibility_reasons) == ["ARRIVES_AFTER_DEADLINE"]
    assert ranked["bdl_nonstop"].feasible and ranked["bdl_onestop"].feasible
    assert response.selected_bundle_id in ("bdl_nonstop", "bdl_onestop")
    assert response.ranking[0].bundle_id == response.selected_bundle_id
    assert response.ranking[0].rank == 1
    assert ranked["bdl_nonstop"].breakdown.preference == 100.0
    assert ranked["bdl_onestop"].breakdown.preference == 0.0
    assert ranked["bdl_onestop"].breakdown.cost == 100.0
    assert 0 < ranked["bdl_nonstop"].score <= 100


def test_missing_context_is_invalid_argument(stub):
    with pytest.raises(grpc.RpcError) as e:
        stub.OptimizeTrip(optimization_pb2.OptimizeTripRequest(trip_id="trip_1"))
    assert e.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    assert "tenant_id" in e.value.details()


def test_segment_without_times_is_invalid_argument(stub):
    broken = offer_pb2.Bundle(
        bundle_id="bdl_x",
        offers=[
            offer_pb2.Offer(
                provider="sandbox-air",
                type=offer_pb2.AIR,
                total=common_pb2.Money(currency="USD", amount_minor=1),
                air=offer_pb2.AirOffer(
                    outbound=offer_pb2.Journey(segments=[offer_pb2.FlightSegment(carrier="DL")])
                ),
            )
        ],
    )
    with pytest.raises(grpc.RpcError) as e:
        stub.OptimizeTrip(
            optimization_pb2.OptimizeTripRequest(ctx=ctx(), trip_id="trip_1", candidates=[broken])
        )
    assert e.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_no_feasible_candidate_selects_nothing(stub):
    response = stub.OptimizeTrip(
        optimization_pb2.OptimizeTripRequest(
            ctx=ctx(),
            trip_id="trip_1",
            candidates=[
                bundle(
                    "bdl_business",
                    130000,
                    [("DL", "BOS", "SEA", 380, 0)],
                    cabin=common_pb2.BUSINESS,
                )
            ],
            constraints=optimization_pb2.ConstraintSet(allowed_cabins=[common_pb2.ECONOMY]),
        )
    )
    assert response.selected_bundle_id == ""
    assert response.ranking[0].feasible is False
