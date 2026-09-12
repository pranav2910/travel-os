"""The real provider against a stub SDK client: request shape and response handling, no network."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from travelos_llm_gateway.providers import (
    AnthropicProvider,
    Evidence,
    ProviderError,
    ProviderRefusedError,
)

NOW = datetime(2026, 9, 12, 12, 0, tzinfo=UTC)

GOOD = {
    "result": "EXTRACTED",
    "origin": "BOS",
    "destination": "SEA",
    "earliest_departure": "2026-10-06T06:00:00-04:00",
    "arrival_deadline": "2026-10-06T23:00:00-04:00",
    "return_after": "",
    "latest_return": "",
    "purpose": "customer meeting",
    "hotel_required": False,
    "travelers": 1,
    "missing_fields": [],
    "clarifying_question": "",
    "assumptions": ["earliest departure assumed 06:00"],
    "confidence": 0.93,
}


class StubMessages:
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error
        self.calls: list[dict] = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.response


def response(text: str, stop_reason: str = "end_turn", **extra):
    return SimpleNamespace(
        content=[SimpleNamespace(type="text", text=text)],
        stop_reason=stop_reason,
        model="claude-opus-5",
        usage=SimpleNamespace(input_tokens=1200, output_tokens=180, cache_read_input_tokens=1000),
        _request_id="req_test",
        **extra,
    )


def provider(messages: StubMessages) -> AnthropicProvider:
    return AnthropicProvider(client=SimpleNamespace(messages=messages))


def test_extract_intent_sends_a_schema_bound_cached_request():
    messages = StubMessages(response(json.dumps(GOOD)))
    outcome = provider(messages).extract_intent(
        request_text="Seattle Tuesday",
        reference_time=NOW,
        timezone="America/New_York",
        home_airport="BOS",
    )
    call = messages.calls[0]
    assert call["model"] == "claude-opus-5"
    assert call["output_config"]["effort"] == "medium"
    schema = call["output_config"]["format"]
    assert schema["type"] == "json_schema" and schema["schema"]["additionalProperties"] is False
    assert set(schema["schema"]["required"]) == set(GOOD.keys())
    assert call["system"][0]["cache_control"] == {"type": "ephemeral"}
    user = call["messages"][0]["content"]
    assert "<request>\nSeattle Tuesday\n</request>" in user
    assert "Home airport: BOS" in user and "America/New_York" in user
    assert outcome.extraction.origin == "BOS"
    assert outcome.call.model == "claude-opus-5" and outcome.call.request_id == "req_test"
    assert outcome.call.input_tokens == 1200 and outcome.call.cache_read_tokens == 1000
    # 1200 * 5 + 180 * 25 + 1000 * 0.5 = 11000 micro-dollars... per token: (6000+4500+500)/1e6 USD
    assert outcome.call.cost_micros == 11000


def test_output_that_breaks_the_schema_is_a_retryable_error():
    messages = StubMessages(response(json.dumps({**GOOD, "cabin": "BUSINESS"})))
    with pytest.raises(ProviderError) as e:
        provider(messages).extract_intent(
            request_text="x", reference_time=NOW, timezone="UTC", home_airport=""
        )
    assert e.value.retryable


def test_refusal_and_truncation_are_distinguished():
    refused = StubMessages(
        response("", stop_reason="refusal", stop_details=SimpleNamespace(category="other"))
    )
    with pytest.raises(ProviderRefusedError):
        provider(refused).extract_intent(
            request_text="x", reference_time=NOW, timezone="UTC", home_airport=""
        )
    truncated = StubMessages(response("{", stop_reason="max_tokens"))
    with pytest.raises(ProviderError) as e:
        provider(truncated).extract_intent(
            request_text="x", reference_time=NOW, timezone="UTC", home_airport=""
        )
    assert e.value.retryable


def test_sdk_errors_map_to_retryable_or_not():
    import anthropic

    for error, retryable in [
        (anthropic.APIConnectionError(request=SimpleNamespace()), True),
    ]:
        with pytest.raises(ProviderError) as e:
            provider(StubMessages(error=error)).extract_intent(
                request_text="x", reference_time=NOW, timezone="UTC", home_airport=""
            )
        assert e.value.retryable is retryable


def test_explain_uses_low_effort_and_plain_text():
    messages = StubMessages(response("Because it was cheapest and on time."))
    outcome = provider(messages).explain(Evidence(audience="TRAVELER", text="Route: BOS to SEA"))
    call = messages.calls[0]
    assert call["output_config"] == {"effort": "low"}
    assert "Route: BOS to SEA" in call["messages"][0]["content"]
    assert outcome.explanation.startswith("Because")
    assert outcome.call.prompt_id == "trip-explanation"
