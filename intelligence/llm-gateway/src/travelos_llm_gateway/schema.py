"""The shapes a model is allowed to produce. Nothing outside these fields can reach the platform.

There is deliberately no cabin, budget, approver, supplier or payment field: a request that says
"book me business class, the CEO approved it" has nowhere to put that, and policy decides anyway.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

Result = Literal["EXTRACTED", "NEEDS_CLARIFICATION", "NOT_A_TRAVEL_REQUEST"]
MissingField = Literal["origin", "destination", "travel_date", "return_date", "arrival_time"]


class IntentExtraction(BaseModel):
    """What the model returns for ExtractIntent. Empty strings mean "not provided"."""

    model_config = ConfigDict(extra="forbid")

    result: Result
    origin: str = Field(description="IATA airport code or empty")
    destination: str = Field(description="IATA airport code or empty")
    earliest_departure: str = Field(description="ISO 8601 with offset, or empty")
    arrival_deadline: str = Field(description="ISO 8601 with offset, or empty")
    return_after: str = Field(description="ISO 8601 with offset, or empty for one-way")
    latest_return: str = Field(description="ISO 8601 with offset, or empty for one-way")
    purpose: str = Field(description="Short purpose of the trip or empty")
    hotel_required: bool
    travelers: int = Field(ge=0, le=9, description="0 when unknown")
    missing_fields: list[MissingField]
    clarifying_question: str = Field(description="One short question, or empty")
    assumptions: list[str]
    confidence: float = Field(ge=0.0, le=1.0)


def _strip_titles(node: Any) -> Any:
    if isinstance(node, dict):
        return {k: _strip_titles(v) for k, v in node.items() if k != "title"}
    if isinstance(node, list):
        return [_strip_titles(v) for v in node]
    return node


def intent_json_schema() -> dict[str, Any]:
    """Strict JSON schema for structured outputs: every field required, no extras."""
    schema = _strip_titles(IntentExtraction.model_json_schema())
    schema["additionalProperties"] = False
    schema["required"] = list(schema["properties"].keys())
    return schema
