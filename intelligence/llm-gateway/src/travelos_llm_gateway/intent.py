"""The gate between what a model says and what the platform accepts.

A model's self-reported "EXTRACTED" is a claim. Every field is re-checked here against the same
invariants Travel Core enforces, plus one the model cannot know: the reference time. Anything that
fails is downgraded to NEEDS_CLARIFICATION with a concrete question. Nothing invalid leaves.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from google.protobuf import timestamp_pb2

from travelos.trip.v1 import trip_pb2
from travelos_llm_gateway.schema import IntentExtraction

_IATA = re.compile(r"^[A-Z]{3}$")
MAX_PURPOSE = 500


@dataclass(frozen=True)
class Validated:
    result: str
    intent: trip_pb2.TravelIntent | None
    missing_fields: list[str] = field(default_factory=list)
    clarifying_question: str = ""
    assumptions: list[str] = field(default_factory=list)
    confidence: float = 0.0


def validate(extraction: IntentExtraction, reference_time: datetime) -> Validated:
    if extraction.result != "EXTRACTED":
        return Validated(
            result=extraction.result,
            intent=None,
            missing_fields=list(extraction.missing_fields),
            clarifying_question=extraction.clarifying_question.strip(),
            assumptions=list(extraction.assumptions),
            confidence=extraction.confidence,
        )

    missing: list[str] = []
    problems: list[str] = []
    origin = extraction.origin.strip().upper()
    destination = extraction.destination.strip().upper()
    if not _IATA.match(origin):
        missing.append("origin")
    if not _IATA.match(destination):
        missing.append("destination")
    if origin and origin == destination:
        problems.append("origin and destination are the same airport")

    earliest = _parse(extraction.earliest_departure)
    deadline = _parse(extraction.arrival_deadline)
    if earliest is None or deadline is None:
        missing.append("travel_date")
    else:
        if not earliest < deadline:
            problems.append("the earliest departure is not before the arrival deadline")
        if deadline < reference_time - timedelta(hours=1):
            problems.append("the travel date is in the past")

    return_after = _parse(extraction.return_after)
    latest_return = _parse(extraction.latest_return)
    if (return_after is None) != (latest_return is None):
        missing.append("return_date")
    elif return_after is not None and latest_return is not None and deadline is not None:
        if return_after < deadline:
            problems.append("the return window starts before the arrival deadline")
        if latest_return < return_after:
            problems.append("the latest return is before the earliest return")

    travelers = extraction.travelers or 1
    if not 1 <= travelers <= 9:
        problems.append("travelers must be between 1 and 9")

    if missing or problems:
        question = extraction.clarifying_question.strip() or _question(missing, problems)
        return Validated(
            result="NEEDS_CLARIFICATION",
            intent=None,
            missing_fields=missing,
            clarifying_question=question,
            assumptions=list(extraction.assumptions),
            confidence=min(extraction.confidence, 0.5),
        )

    assert earliest is not None and deadline is not None
    intent = trip_pb2.TravelIntent(
        origin=origin,
        destination=destination,
        earliest_departure=_ts(earliest),
        arrival_deadline=_ts(deadline),
        purpose=extraction.purpose.strip()[:MAX_PURPOSE],
        hotel_required=extraction.hotel_required,
        travelers=travelers,
    )
    if return_after is not None and latest_return is not None:
        intent.return_after.CopyFrom(_ts(return_after))
        intent.latest_return.CopyFrom(_ts(latest_return))
    return Validated(
        result="EXTRACTED",
        intent=intent,
        assumptions=list(extraction.assumptions),
        confidence=extraction.confidence,
    )


def _parse(value: str) -> datetime | None:
    value = value.strip()
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed


def _ts(dt: datetime) -> timestamp_pb2.Timestamp:
    ts = timestamp_pb2.Timestamp()
    ts.FromDatetime(dt.astimezone(UTC))
    return ts


def _question(missing: list[str], problems: list[str]) -> str:
    asks = {
        "origin": "Which airport are you flying from?",
        "destination": "Where do you need to go?",
        "travel_date": "Which day do you need to be there?",
        "return_date": "When do you need to be back?",
    }
    if missing:
        return asks[missing[0]]
    return "Could you confirm the dates? " + "; ".join(problems) + "."
