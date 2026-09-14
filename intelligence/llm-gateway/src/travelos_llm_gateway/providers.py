"""Model providers behind one interface. The servicer never sees an SDK type.

AnthropicProvider is the real one (official SDK, structured JSON output, prompt caching).
FakeProvider is deterministic and offline: tests, CI and local development without a key.
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Protocol
from zoneinfo import ZoneInfo

from pydantic import ValidationError

from travelos_llm_gateway import pricing
from travelos_llm_gateway.prompts import (
    DISRUPTION_PROMPT_ID,
    DISRUPTION_PROMPT_VERSION,
    EXPLAIN_DISRUPTION_SYSTEM,
    EXPLAIN_PROMPT_ID,
    EXPLAIN_PROMPT_VERSION,
    EXPLAIN_SYSTEM,
    INTENT_PROMPT_ID,
    INTENT_PROMPT_VERSION,
    INTENT_SYSTEM,
    intent_user_message,
)
from travelos_llm_gateway.schema import IntentExtraction, intent_json_schema

log = logging.getLogger(__name__)

DEFAULT_MODEL = "claude-opus-5"


class ProviderError(Exception):
    """The provider could not produce a usable answer; `retryable` says if trying again helps."""

    def __init__(self, message: str, *, retryable: bool):
        super().__init__(message)
        self.retryable = retryable


class ProviderRefusedError(ProviderError):
    """The model declined to answer (safety classifier). Not retryable; not the traveler's fault."""

    def __init__(self, category: str | None):
        super().__init__(f"model declined the request ({category})", retryable=False)
        self.category = category


@dataclass(frozen=True)
class ModelCallResult:
    provider: str
    model: str
    prompt_id: str
    prompt_version: int
    request_id: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    latency_ms: int = 0
    cost_micros: int = 0


@dataclass(frozen=True)
class ExtractionOutcome:
    extraction: IntentExtraction
    call: ModelCallResult


@dataclass(frozen=True)
class ExplanationOutcome:
    explanation: str
    call: ModelCallResult


@dataclass(frozen=True)
class Evidence:
    """Everything an explanation may talk about, already rendered to plain text by the servicer."""

    audience: str
    text: str
    facts: dict[str, Any] = field(default_factory=dict)


class Provider(Protocol):
    name: str

    def extract_intent(
        self, *, request_text: str, reference_time: datetime, timezone: str, home_airport: str
    ) -> ExtractionOutcome: ...

    def explain(self, evidence: Evidence) -> ExplanationOutcome: ...


# --------------------------------------------------------------------------- anthropic


class AnthropicProvider:
    """Official SDK. Structured output pins the answer to the schema; the servicer re-validates."""

    name = "anthropic"

    def __init__(self, client: Any | None = None, model: str = DEFAULT_MODEL):
        if client is None:
            import anthropic

            client = anthropic.Anthropic(timeout=60.0, max_retries=2)
        self._client = client
        self.model = model

    def extract_intent(
        self, *, request_text: str, reference_time: datetime, timezone: str, home_airport: str
    ) -> ExtractionOutcome:
        started = time.monotonic()
        response = self._create(
            system=INTENT_SYSTEM,
            user=intent_user_message(request_text, reference_time, timezone, home_airport),
            max_tokens=2048,
            effort="medium",
            output_format={"type": "json_schema", "schema": intent_json_schema()},
        )
        text = _first_text(response)
        try:
            extraction = IntentExtraction.model_validate_json(text)
        except ValidationError as e:
            raise ProviderError(
                f"model output did not match the intent schema: {e}", retryable=True
            ) from e
        return ExtractionOutcome(
            extraction=extraction,
            call=self._call(response, INTENT_PROMPT_ID, INTENT_PROMPT_VERSION, started),
        )

    def explain(self, evidence: Evidence) -> ExplanationOutcome:
        started = time.monotonic()
        disruption = evidence.facts.get("kind") == "disruption"
        system = EXPLAIN_DISRUPTION_SYSTEM if disruption else EXPLAIN_SYSTEM
        prompt_id = DISRUPTION_PROMPT_ID if disruption else EXPLAIN_PROMPT_ID
        prompt_version = DISRUPTION_PROMPT_VERSION if disruption else EXPLAIN_PROMPT_VERSION
        # No tools, ever: the gateway narrates; it cannot search, book, change or approve anything.
        response = self._create(
            system=system,
            user=f"Audience: {evidence.audience}\n\nEvidence:\n{evidence.text}",
            max_tokens=1024,
            effort="low",
            output_format=None,
        )
        text = _first_text(response).strip()
        if not text:
            raise ProviderError("model returned an empty explanation", retryable=True)
        return ExplanationOutcome(
            explanation=text, call=self._call(response, prompt_id, prompt_version, started)
        )

    def _create(
        self, *, system: str, user: str, max_tokens: int, effort: str, output_format: dict | None
    ) -> Any:
        import anthropic

        output_config: dict[str, Any] = {"effort": effort}
        if output_format is not None:
            output_config["format"] = output_format
        try:
            response = self._client.messages.create(
                model=self.model,
                max_tokens=max_tokens,
                system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                messages=[{"role": "user", "content": user}],
                output_config=output_config,
            )
        except anthropic.RateLimitError as e:
            raise ProviderError(f"rate limited: {e}", retryable=True) from e
        except anthropic.APIConnectionError as e:
            raise ProviderError(f"connection error: {e}", retryable=True) from e
        except anthropic.APIStatusError as e:
            raise ProviderError(
                f"api error {e.status_code}: {e.message}", retryable=e.status_code >= 500
            ) from e
        if response.stop_reason == "refusal":
            details = getattr(response, "stop_details", None)
            category = getattr(details, "category", None) if details else None
            raise ProviderRefusedError(category)
        if response.stop_reason == "max_tokens":
            raise ProviderError("model output was truncated", retryable=True)
        return response

    def _call(self, response: Any, prompt_id: str, version: int, started: float) -> ModelCallResult:
        usage = response.usage
        input_tokens = int(getattr(usage, "input_tokens", 0) or 0)
        output_tokens = int(getattr(usage, "output_tokens", 0) or 0)
        cache_read = int(getattr(usage, "cache_read_input_tokens", 0) or 0)
        return ModelCallResult(
            provider=self.name,
            model=str(getattr(response, "model", self.model) or self.model),
            prompt_id=prompt_id,
            prompt_version=version,
            request_id=str(getattr(response, "_request_id", "") or ""),
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cache_read_tokens=cache_read,
            latency_ms=int((time.monotonic() - started) * 1000),
            cost_micros=pricing.cost_micros(self.model, input_tokens, output_tokens, cache_read),
        )


def _first_text(response: Any) -> str:
    for block in response.content:
        if getattr(block, "type", None) == "text":
            return block.text
    raise ProviderError("model response carried no text block", retryable=True)


# --------------------------------------------------------------------------- fake


_IATA = re.compile(r"\b([A-Z]{3})\b")
_ROUTE = re.compile(r"\b([A-Z]{3})\s*(?:to|->|-)\s*([A-Z]{3})\b")
_DATE = re.compile(r"\b(\d{4}-\d{2}-\d{2})\b")
_PURPOSE = re.compile(r"(?:purpose:|for (?:a|an|the)\s)\s*([A-Za-z][A-Za-z ]{2,60})")
_NOT_CODES = {
    "AND",
    "THE",
    "FOR",
    "NOT",
    "ALL",
    "ANY",
    "YOU",
    "OUR",
    "CEO",
    "VIP",
    "USD",
    "EUR",
    "GBP",
    "ASAP",
    "EST",
    "PST",
    "UTC",
    "GMT",
    "CST",
    "MST",
    "PDT",
    "EDT",
    "PLZ",
    "NOW",
    "ETA",
    "FYI",
}
_TRAVEL_WORDS = ("fly", "flight", "trip", "travel", "go to", "be in", "visit", "get to", "hotel")


class FakeProvider:
    """Deterministic, offline. Understands 'BOS to SEA on 2026-10-06, back 2026-10-08, hotel'."""

    name = "fake"
    model = "fake-rules-v1"

    def extract_intent(
        self, *, request_text: str, reference_time: datetime, timezone: str, home_airport: str
    ) -> ExtractionOutcome:
        text = request_text
        lower = text.lower()
        codes = [c for c in _IATA.findall(text) if c not in _NOT_CODES]
        dates = _DATE.findall(text)
        assumptions: list[str] = []
        if not codes and not any(w in lower for w in _TRAVEL_WORDS):
            return self._outcome(
                IntentExtraction(
                    result="NOT_A_TRAVEL_REQUEST",
                    origin="",
                    destination="",
                    earliest_departure="",
                    arrival_deadline="",
                    return_after="",
                    latest_return="",
                    purpose="",
                    hotel_required=False,
                    travelers=0,
                    missing_fields=[],
                    clarifying_question="",
                    assumptions=[],
                    confidence=0.9,
                )
            )
        origin, destination = "", ""
        route = _ROUTE.search(text)
        if route:
            origin, destination = route.group(1), route.group(2)
        elif len(codes) >= 2:
            origin, destination = codes[0], codes[1]
        elif len(codes) == 1:
            destination = codes[0]
            if home_airport:
                origin = home_airport
                assumptions.append(f"origin {home_airport} taken from the traveler's home airport")
        missing: list[str] = []
        if not destination:
            missing.append("destination")
        if not origin:
            missing.append("origin")
        if not dates:
            missing.append("travel_date")
        if missing:
            question = {
                "destination": "Where do you need to go?",
                "origin": "Which airport are you flying from?",
                "travel_date": "Which day do you need to be there?",
            }[missing[0]]
            return self._outcome(
                IntentExtraction(
                    result="NEEDS_CLARIFICATION",
                    origin=origin,
                    destination=destination,
                    earliest_departure="",
                    arrival_deadline="",
                    return_after="",
                    latest_return="",
                    purpose="",
                    hotel_required=False,
                    travelers=0,
                    missing_fields=missing,  # type: ignore[arg-type]
                    clarifying_question=question,
                    assumptions=assumptions,
                    confidence=0.6,
                )
            )
        zone = ZoneInfo(timezone) if timezone else reference_time.tzinfo
        out = datetime.fromisoformat(dates[0]).replace(tzinfo=zone)
        earliest = out.replace(hour=6, minute=0)
        deadline = out.replace(hour=23, minute=0)
        assumptions.append("earliest departure assumed 06:00 local on the travel day")
        return_after = latest_return = ""
        if len(dates) > 1:
            back = datetime.fromisoformat(dates[1]).replace(tzinfo=zone)
            return_after = back.replace(hour=6, minute=0).isoformat()
            latest_return = (back.replace(hour=23, minute=59)).isoformat()
        purpose_match = _PURPOSE.search(text)
        purpose = purpose_match.group(1).strip() if purpose_match else ""
        hotel = "hotel" in lower or bool(return_after)
        return self._outcome(
            IntentExtraction(
                result="EXTRACTED",
                origin=origin,
                destination=destination,
                earliest_departure=earliest.isoformat(),
                arrival_deadline=deadline.isoformat(),
                return_after=return_after,
                latest_return=latest_return,
                purpose=purpose,
                hotel_required=hotel,
                travelers=1,
                missing_fields=[],
                clarifying_question="",
                assumptions=assumptions,
                confidence=0.85,
            )
        )

    def explain(self, evidence: Evidence) -> ExplanationOutcome:
        f = evidence.facts
        if f.get("kind") == "disruption":
            return self._explain_disruption(f)
        parts = [
            f"For the {f.get('route', 'trip')} we chose {f.get('selected', 'the selected option')} "
            f"at {f.get('total', 'the quoted total')}.",
            f"{f.get('searched', 0)} options were searched and {f.get('permitted', 0)} were "
            f"permitted by policy {f.get('policy', '')}.",
            f"It ranked first with a score of {f.get('score', 'n/a')}"
            + (f" ({f['breakdown']})" if f.get("breakdown") else "")
            + ".",
            f"Policy outcome: {f.get('outcome', 'n/a')}"
            + (f"; {f['reasons']}" if f.get("reasons") else "")
            + ".",
            "A manager's approval is required before booking."
            if f.get("requires_approval")
            else "No approval is required; it books automatically.",
        ]
        return ExplanationOutcome(
            explanation=" ".join(parts),
            call=ModelCallResult(self.name, self.model, EXPLAIN_PROMPT_ID, EXPLAIN_PROMPT_VERSION),
        )

    def _explain_disruption(self, f: dict[str, Any]) -> ExplanationOutcome:
        """Deterministic narration from the facts alone; the supplier's words are not among them."""
        if f.get("autonomy") == "ALLOW":
            authorization = (
                "Policy permits this change automatically, so the booking is updated without "
                "waiting for anyone."
            )
        elif f.get("requires_approval"):
            authorization = (
                f"Policy requires approval from {f.get('approver', 'a manager')} before the "
                "booking is changed."
            )
        else:
            authorization = (
                f"Policy does not permit this change ({f.get('autonomy', 'DENY')}); "
                "a person must handle it."
            )
        parts = [
            f"{f.get('supplier', 'The supplier')} reported {f.get('type', 'a disruption')} on "
            f"{f.get('original', 'the itinerary')}.",
            f"{f.get('searched', 0)} alternatives were searched and {f.get('permitted', 0)} were "
            f"permitted by policy {f.get('policy', '')}.".replace("policy .", "policy."),
            f"The optimizer chose {f.get('replacement', 'a replacement')} at "
            f"{f.get('replacement_total', 'the quoted total')}, "
            f"{f.get('incremental', 'n/a')} versus the original order"
            + (f", scoring {f['score']} ({f['breakdown']})" if f.get("score") else "")
            + ".",
            authorization,
        ]
        if f.get("reasons"):
            parts.append(f"Policy noted: {f['reasons']}.")
        return ExplanationOutcome(
            explanation=" ".join(parts),
            call=ModelCallResult(
                self.name, self.model, DISRUPTION_PROMPT_ID, DISRUPTION_PROMPT_VERSION
            ),
        )

    def _outcome(self, extraction: IntentExtraction) -> ExtractionOutcome:
        return ExtractionOutcome(
            extraction=extraction,
            call=ModelCallResult(self.name, self.model, INTENT_PROMPT_ID, INTENT_PROMPT_VERSION),
        )


def provider_from_env(env: dict[str, str]) -> Provider:
    """LLM_PROVIDER=anthropic|fake. Defaults to anthropic when a key is present, fake otherwise."""
    choice = env.get("LLM_PROVIDER", "").strip().lower()
    if not choice:
        choice = "anthropic" if env.get("ANTHROPIC_API_KEY") else "fake"
    if choice == "anthropic":
        return AnthropicProvider(model=env.get("LLM_MODEL", DEFAULT_MODEL))
    if choice == "fake":
        log.warning("LLM_PROVIDER=fake: deterministic rule-based provider, no model is called")
        return FakeProvider()
    raise ValueError(f"unknown LLM_PROVIDER {choice!r}")
