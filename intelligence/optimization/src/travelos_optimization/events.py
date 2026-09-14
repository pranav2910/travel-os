"""Domain events from the optimization service: the standard envelope, keyed by correlation id.

The service is stateless, so there is no outbox to make the event transactional. The equivalent
guarantee here is: the response is not returned until the broker has acknowledged the event
(acks=all). If the broker is unreachable the call fails and the caller's activity retries — one
optimization run therefore never leaves a gap in the trail, and a retried call produces a new run
id, so consumers never see the same run twice.

Broker security comes from the same switch the Java services use:
  KAFKA_AUTH=none      PLAINTEXT (laptops, kind)                                   (default)
  KAFKA_AUTH=msk-iam   SASL_SSL + OAUTHBEARER tokens signed with the pod's IAM role (Amazon MSK)
  KAFKA_AUTH=tls       SSL without SASL
"""

from __future__ import annotations

import json
import logging
import threading
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from typing import Any, Protocol

from opentelemetry import trace
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

from travelos_optimization import ids

log = logging.getLogger(__name__)

PRODUCER = "optimization"
# Well inside the caller's gRPC deadline, so a dead broker surfaces as UNAVAILABLE, not a timeout.
DELIVERY_TIMEOUT_S = 5.0


def envelope(
    event_type: str,
    tenant_id: str,
    correlation_id: str,
    data: Mapping[str, Any],
    *,
    causation_id: str | None = None,
    version: int = 1,
    now: datetime | None = None,
) -> dict[str, Any]:
    """The standard envelope (contracts/events/event-envelope.schema.json), field for field."""
    at = (now or datetime.now(UTC)).astimezone(UTC)
    env: dict[str, Any] = {
        "eventId": ids.new_id("evt"),
        "eventType": event_type,
        "eventVersion": version,
        "occurredAt": at.isoformat(timespec="microseconds").replace("+00:00", "Z"),
        "tenantId": tenant_id,
        "correlationId": correlation_id,
    }
    if causation_id:
        env["causationId"] = causation_id
    env["producer"] = PRODUCER
    env["data"] = dict(data)
    return env


def topic_of(event_type: str) -> str:
    """travel.optimization.completed -> travel.optimization (contracts/events/topics.yaml rule)."""
    parts = event_type.split(".")
    if len(parts) < 3 or parts[0] != "travel":
        raise ValueError(f"not a travel event type: {event_type}")
    return ".".join(parts[:2])


def trace_headers() -> list[tuple[str, bytes]]:
    """W3C traceparent of the current span, so consumers continue the same trace."""
    carrier: dict[str, str] = {}
    TraceContextTextMapPropagator().inject(carrier)
    return [(k, v.encode()) for k, v in carrier.items()]


class EventPublisher(Protocol):
    def publish(self, event: Mapping[str, Any]) -> None:
        """Deliver one enveloped event to its topic, keyed by correlationId. Raises on failure."""


class PublishError(RuntimeError):
    pass


class NoopPublisher:
    """No broker configured (unit tests, laptops without Kafka): the event is logged, not sent."""

    def publish(self, event: Mapping[str, Any]) -> None:
        log.info("event not published (no KAFKA_BOOTSTRAP_SERVERS): %s", event["eventType"])


class RecordingPublisher:
    """Test double: keeps every event; can be told to fail."""

    def __init__(self, fail_with: Exception | None = None) -> None:
        self.events: list[dict[str, Any]] = []
        self.fail_with = fail_with

    def publish(self, event: Mapping[str, Any]) -> None:
        if self.fail_with is not None:
            raise self.fail_with
        self.events.append(dict(event))


def kafka_config(env: Mapping[str, str]) -> dict[str, Any]:
    """librdkafka configuration from the environment. Idempotent producer, acks=all."""
    servers = env.get("KAFKA_BOOTSTRAP_SERVERS", "").strip()
    cfg: dict[str, Any] = {
        "bootstrap.servers": servers,
        "client.id": PRODUCER,
        "acks": "all",
        "enable.idempotence": True,
        "request.timeout.ms": 15000,
        "delivery.timeout.ms": 45000,
        "linger.ms": 5,
    }
    auth = env.get("KAFKA_AUTH", "none").strip().lower() or "none"
    if auth in ("none", "plaintext"):
        pass
    elif auth == "msk-iam":
        region = env.get("AWS_REGION") or env.get("AWS_DEFAULT_REGION") or ""
        if not region:
            raise ValueError("KAFKA_AUTH=msk-iam needs AWS_REGION")
        cfg["security.protocol"] = "SASL_SSL"
        cfg["sasl.mechanisms"] = "OAUTHBEARER"
        cfg["oauth_cb"] = msk_iam_token_callback(region)
    elif auth == "tls":
        cfg["security.protocol"] = "SSL"
    else:
        raise ValueError(f"unknown KAFKA_AUTH '{auth}': expected none, msk-iam or tls")
    ca = env.get("KAFKA_SSL_CA_LOCATION", "").strip()
    if ca:
        cfg["ssl.ca.location"] = ca
    return cfg


def msk_iam_token_callback(region: str) -> Callable[[Any], tuple[str, float]]:
    """librdkafka OAUTHBEARER callback: a short-lived token signed with the pod IAM role."""

    def oauth_cb(_config: Any) -> tuple[str, float]:
        from aws_msk_iam_sasl_signer import MSKAuthTokenProvider  # boto3 credential chain (IRSA)

        token, expiry_ms = MSKAuthTokenProvider.generate_auth_token(region)
        return token, expiry_ms / 1000

    return oauth_cb


class KafkaEventPublisher:
    """confluent-kafka producer; publish() blocks until the broker acknowledges or fails."""

    def __init__(self, config: Mapping[str, Any]) -> None:
        from confluent_kafka import Producer  # imported here so tests need no librdkafka broker

        self._producer = Producer(dict(config))
        self._lock = threading.Lock()

    def publish(self, event: Mapping[str, Any]) -> None:
        topic = topic_of(event["eventType"])
        payload = json.dumps(event, separators=(",", ":")).encode()
        outcome: dict[str, Any] = {}

        def on_delivery(err: Any, _msg: Any) -> None:
            outcome["err"] = err

        with self._lock:
            self._producer.produce(
                topic,
                key=event["correlationId"].encode(),
                value=payload,
                headers=trace_headers(),
                on_delivery=on_delivery,
            )
            remaining = self._producer.flush(DELIVERY_TIMEOUT_S)
        if remaining or "err" not in outcome:
            raise PublishError(
                f"{event['eventType']} not acknowledged within {DELIVERY_TIMEOUT_S}s"
            )
        if outcome["err"] is not None:
            raise PublishError(f"{event['eventType']} delivery failed: {outcome['err']}")
        span = trace.get_current_span()
        if span.get_span_context().is_valid:
            span.add_event("event.published", {"event.type": event["eventType"], "topic": topic})


def publisher_from_env(env: Mapping[str, str]) -> EventPublisher:
    if not env.get("KAFKA_BOOTSTRAP_SERVERS", "").strip():
        return NoopPublisher()
    cfg = kafka_config(env)
    log.info(
        "events: publishing to %s (%s)",
        cfg["bootstrap.servers"],
        cfg.get("security.protocol", "PLAINTEXT"),
    )
    return KafkaEventPublisher(cfg)
