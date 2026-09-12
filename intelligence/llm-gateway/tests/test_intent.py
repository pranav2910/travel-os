from __future__ import annotations

from datetime import UTC, datetime

from travelos_llm_gateway import intent
from travelos_llm_gateway.schema import IntentExtraction

NOW = datetime(2026, 9, 12, 12, 0, tzinfo=UTC)


def make(**overrides) -> IntentExtraction:
    base = dict(
        result="EXTRACTED",
        origin="BOS",
        destination="SEA",
        earliest_departure="2026-10-06T06:00:00-04:00",
        arrival_deadline="2026-10-06T23:00:00-04:00",
        return_after="2026-10-08T06:00:00-04:00",
        latest_return="2026-10-08T23:59:00-04:00",
        purpose="customer meeting",
        hotel_required=True,
        travelers=1,
        missing_fields=[],
        clarifying_question="",
        assumptions=["x"],
        confidence=0.8,
    )
    base.update(overrides)
    return IntentExtraction(**base)


def test_happy_path_converts_to_proto_in_utc():
    v = intent.validate(make(), NOW)
    assert v.result == "EXTRACTED" and v.intent is not None
    assert v.intent.earliest_departure.seconds == int(
        datetime(2026, 10, 6, 10, 0, tzinfo=UTC).timestamp()
    )
    assert v.intent.HasField("return_after") and v.intent.hotel_required
    assert v.assumptions == ["x"] and v.confidence == 0.8


def test_lowercase_codes_are_normalised_and_bad_ones_rejected():
    assert intent.validate(make(origin="bos"), NOW).intent.origin == "BOS"
    v = intent.validate(make(origin="Boston"), NOW)
    assert v.result == "NEEDS_CLARIFICATION" and v.missing_fields == ["origin"]
    assert v.clarifying_question == "Which airport are you flying from?"


def test_same_airport_and_reversed_windows_are_questions():
    assert "same airport" in intent.validate(make(destination="BOS"), NOW).clarifying_question
    v = intent.validate(
        make(
            earliest_departure="2026-10-06T23:00:00-04:00",
            arrival_deadline="2026-10-06T06:00:00-04:00",
        ),
        NOW,
    )
    assert v.result == "NEEDS_CLARIFICATION" and "not before" in v.clarifying_question
    v = intent.validate(make(latest_return="2026-10-07T06:00:00-04:00"), NOW)
    assert "latest return" in v.clarifying_question


def test_half_a_return_window_asks_for_the_return_date():
    v = intent.validate(make(latest_return=""), NOW)
    assert v.missing_fields == ["return_date"]


def test_one_way_has_no_return_fields():
    v = intent.validate(make(return_after="", latest_return=""), NOW)
    assert v.result == "EXTRACTED" and not v.intent.HasField("return_after")


def test_non_extracted_results_pass_through():
    v = intent.validate(
        make(
            result="NEEDS_CLARIFICATION",
            missing_fields=["travel_date"],
            clarifying_question="When?",
        ),
        NOW,
    )
    assert v.result == "NEEDS_CLARIFICATION" and v.missing_fields == ["travel_date"]
    assert v.clarifying_question == "When?" and v.intent is None
