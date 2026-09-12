"""OpenTelemetry for the gRPC server: spans continue from the caller's traceparent metadata.

Enabled only when OTEL_EXPORTER_OTLP_ENDPOINT is set (e.g. http://localhost:4317); otherwise the
no-op provider stays and nothing is exported.
"""

from __future__ import annotations

import logging

import grpc
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.grpc import server_interceptor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

log = logging.getLogger(__name__)


def configure(service_name: str, env: dict[str, str]) -> list[grpc.ServerInterceptor]:
    endpoint = env.get("OTEL_EXPORTER_OTLP_ENDPOINT", "").strip()
    if not endpoint:
        return []
    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    provider.add_span_processor(
        BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint, insecure=True))
    )
    trace.set_tracer_provider(provider)
    log.info("tracing: exporting to %s as %s", endpoint, service_name)
    return [server_interceptor()]


def tag_current_span(tenant_id: str, correlation_id: str, principal_id: str) -> None:
    span = trace.get_current_span()
    if not span.get_span_context().is_valid:
        return
    span.set_attribute("tenant.id", tenant_id)
    span.set_attribute("trip.id", correlation_id)
    span.set_attribute("principal.id", principal_id)
