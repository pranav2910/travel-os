"""gRPC servicer. Validates the caller, guards the budget, calls the provider, validates the answer.

Request text is never logged; only its length. Every response carries ModelCall evidence.
"""

from __future__ import annotations

import logging
import re
import time
from collections.abc import Callable
from datetime import UTC, datetime

import grpc
from google.protobuf import timestamp_pb2
from opentelemetry import trace

from travelos.common.v1 import common_pb2
from travelos.llm.v1 import llm_pb2, llm_pb2_grpc
from travelos_llm_gateway import explain, ids, intent, tracing
from travelos_llm_gateway.budget import BudgetExceededError, TenantBudget
from travelos_llm_gateway.providers import (
    ModelCallResult,
    Provider,
    ProviderError,
    ProviderRefusedError,
)

log = logging.getLogger(__name__)

MAX_REQUEST_CHARS = 4000
_TRACER = trace.get_tracer("travelos.llm-gateway")

_TENANT = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
_PRINCIPAL = re.compile(r"^(human|service|agent)/[a-z0-9][a-z0-9-]*(/v[0-9]+)?$")


def require_context(ctx: common_pb2.RequestContext, context: grpc.ServicerContext) -> None:
    """Mirror of RequestContexts.require on the Java side: no anonymous internal calls."""
    if not ctx.tenant_id or not _TENANT.match(ctx.tenant_id):
        context.abort(grpc.StatusCode.INVALID_ARGUMENT, "ctx.tenant_id is required")
    if not ctx.HasField("principal") or not _PRINCIPAL.match(ctx.principal.id):
        context.abort(grpc.StatusCode.INVALID_ARGUMENT, "ctx.principal is required")
    if not ctx.correlation_id:
        context.abort(grpc.StatusCode.INVALID_ARGUMENT, "ctx.correlation_id is required")
    tracing.tag_current_span(ctx.tenant_id, ctx.correlation_id, ctx.principal.id)


class LlmGatewayService(llm_pb2_grpc.LlmGatewayServicer):
    def __init__(
        self,
        provider: Provider,
        budget: TenantBudget,
        clock: Callable[[], datetime] | None = None,
    ):
        self._provider = provider
        self._budget = budget
        self._clock = clock or (lambda: datetime.now(UTC))

    # ------------------------------------------------------------------ ExtractIntent

    def ExtractIntent(self, request, context):  # noqa: N802 (gRPC method name)
        require_context(request.ctx, context)
        text = request.request_text.strip()
        if not text:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "request_text is required")
        if len(text) > MAX_REQUEST_CHARS:
            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"request_text exceeds {MAX_REQUEST_CHARS} characters",
            )
        tenant = request.ctx.tenant_id
        now = self._clock()
        self._guard_budget(tenant, now, context)
        reference = _to_datetime(request.reference_time) or now
        started = time.monotonic()
        try:
            with _TRACER.start_as_current_span(
                "llm.extract_intent",
                attributes={"llm.provider": self._provider.name, "request.chars": len(text)},
            ):
                outcome = self._provider.extract_intent(
                    request_text=text,
                    reference_time=reference,
                    timezone=request.timezone.strip() or "UTC",
                    home_airport=request.home_airport.strip().upper(),
                )
        except ProviderRefusedError as e:
            log.warning(
                "extract_intent tenant=%s trip=%s: model declined (%s)",
                tenant,
                request.trip_id,
                e.category,
            )
            return llm_pb2.ExtractIntentResponse(
                result=llm_pb2.ExtractIntentResponse.NEEDS_CLARIFICATION,
                clarifying_question="Please restate your travel request in plain terms.",
                call=_model_call(ModelCallResult(self._provider.name, "", "", 0), now),
            )
        except ProviderError as e:
            self._abort_provider(e, context, "extract_intent", tenant, request.trip_id)
        validated = intent.validate(outcome.extraction, reference)
        spent = self._budget.charge(tenant, now, outcome.call.cost_micros)
        log.info(
            "extract_intent tenant=%s trip=%s result=%s model=%s chars=%d tokens=%d/%d "
            "cost_micros=%d spent_today_micros=%d latency_ms=%d",
            tenant,
            request.trip_id,
            validated.result,
            outcome.call.model,
            len(text),
            outcome.call.input_tokens,
            outcome.call.output_tokens,
            outcome.call.cost_micros,
            spent,
            int((time.monotonic() - started) * 1000),
        )
        response = llm_pb2.ExtractIntentResponse(
            result=llm_pb2.ExtractIntentResponse.Result.Value(validated.result),
            missing_fields=validated.missing_fields,
            clarifying_question=validated.clarifying_question,
            assumptions=validated.assumptions,
            confidence=validated.confidence,
            call=_model_call(outcome.call, now),
        )
        if validated.intent is not None:
            response.intent.CopyFrom(validated.intent)
        return response

    # ------------------------------------------------------------------ ExplainTrip

    def ExplainTrip(self, request, context):  # noqa: N802
        require_context(request.ctx, context)
        tenant = request.ctx.tenant_id
        now = self._clock()
        self._guard_budget(tenant, now, context)
        ev = explain.evidence(request)
        try:
            with _TRACER.start_as_current_span(
                "llm.explain_trip", attributes={"llm.provider": self._provider.name}
            ):
                outcome = self._provider.explain(ev)
        except ProviderError as e:
            self._abort_provider(e, context, "explain", tenant, request.trip_id)
        spent = self._budget.charge(tenant, now, outcome.call.cost_micros)
        log.info(
            "explain tenant=%s trip=%s model=%s tokens=%d/%d cost_micros=%d spent_today_micros=%d",
            tenant,
            request.trip_id,
            outcome.call.model,
            outcome.call.input_tokens,
            outcome.call.output_tokens,
            outcome.call.cost_micros,
            spent,
        )
        return llm_pb2.ExplainTripResponse(
            explanation=outcome.explanation, call=_model_call(outcome.call, now)
        )

    def ExplainDisruption(self, request, context):  # noqa: N802
        require_context(request.ctx, context)
        tenant = request.ctx.tenant_id
        now = self._clock()
        self._guard_budget(tenant, now, context)
        ev = explain.disruption_evidence(request)
        try:
            with _TRACER.start_as_current_span(
                "llm.explain_disruption",
                attributes={
                    "llm.provider": self._provider.name,
                    "disruption.id": request.disruption_id,
                },
            ):
                outcome = self._provider.explain(ev)
        except ProviderError as e:
            self._abort_provider(e, context, "explain_disruption", tenant, request.trip_id)
        spent = self._budget.charge(tenant, now, outcome.call.cost_micros)
        log.info(
            "explain_disruption tenant=%s trip=%s disruption=%s model=%s tokens=%d/%d "
            "cost_micros=%d spent_today_micros=%d",
            tenant,
            request.trip_id,
            request.disruption_id,
            outcome.call.model,
            outcome.call.input_tokens,
            outcome.call.output_tokens,
            outcome.call.cost_micros,
            spent,
        )
        return llm_pb2.ExplainDisruptionResponse(
            explanation=outcome.explanation, call=_model_call(outcome.call, now)
        )

    # ------------------------------------------------------------------ helpers

    def _guard_budget(self, tenant: str, now: datetime, context: grpc.ServicerContext) -> None:
        try:
            self._budget.check(tenant, now)
        except BudgetExceededError as e:
            log.warning("budget exceeded: %s", e)
            context.abort(grpc.StatusCode.RESOURCE_EXHAUSTED, str(e))

    @staticmethod
    def _abort_provider(
        e: ProviderError, context: grpc.ServicerContext, op: str, tenant: str, trip: str
    ) -> None:
        log.error(
            "%s tenant=%s trip=%s provider failure (retryable=%s): %s",
            op,
            tenant,
            trip,
            e.retryable,
            e,
        )
        code = grpc.StatusCode.UNAVAILABLE if e.retryable else grpc.StatusCode.INTERNAL
        context.abort(code, f"llm provider: {e}")


def _model_call(call: ModelCallResult, now: datetime) -> common_pb2.ModelCall:
    called_at = timestamp_pb2.Timestamp()
    called_at.FromDatetime(now.astimezone(UTC))
    return common_pb2.ModelCall(
        call_id=ids.new_id("llm"),
        provider=call.provider,
        model=call.model,
        prompt_id=call.prompt_id,
        prompt_version=call.prompt_version,
        provider_request_id=call.request_id,
        input_tokens=call.input_tokens,
        output_tokens=call.output_tokens,
        cache_read_tokens=call.cache_read_tokens,
        latency_ms=call.latency_ms,
        cost_micros=call.cost_micros,
        called_at=called_at,
    )


def _to_datetime(ts: timestamp_pb2.Timestamp) -> datetime | None:
    if ts.seconds == 0 and ts.nanos == 0:
        return None
    return datetime.fromtimestamp(ts.seconds + ts.nanos / 1e9, tz=UTC)
