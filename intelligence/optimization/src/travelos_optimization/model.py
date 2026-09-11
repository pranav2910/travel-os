"""The engine's vocabulary: plain dataclasses, no protobuf, so scoring is trivially testable."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import IntEnum


class Cabin(IntEnum):
    ECONOMY = 1
    PREMIUM_ECONOMY = 2
    BUSINESS = 3
    FIRST = 4


@dataclass(frozen=True)
class Money:
    currency: str
    amount_minor: int


@dataclass(frozen=True)
class Segment:
    carrier: str
    origin: str
    destination: str
    departure: datetime
    arrival: datetime
    cabin: Cabin


@dataclass(frozen=True)
class Journey:
    segments: tuple[Segment, ...]

    @property
    def stops(self) -> int:
        return max(len(self.segments) - 1, 0)

    @property
    def departure(self) -> datetime:
        return self.segments[0].departure

    @property
    def arrival(self) -> datetime:
        return self.segments[-1].arrival

    @property
    def duration_minutes(self) -> int:
        return int((self.arrival - self.departure).total_seconds() // 60)

    def connection_minutes(self) -> list[int]:
        return [
            int((b.departure - a.arrival).total_seconds() // 60)
            for a, b in zip(self.segments, self.segments[1:], strict=False)
        ]


@dataclass(frozen=True)
class Candidate:
    bundle_id: str
    total: Money
    providers: tuple[str, ...]
    journeys: tuple[Journey, ...]  # outbound (+ inbound); empty for bundles without air
    hotel_ids: tuple[str, ...] = ()

    @property
    def carriers(self) -> set[str]:
        return {s.carrier for j in self.journeys for s in j.segments}

    @property
    def cabins(self) -> set[Cabin]:
        return {s.cabin for j in self.journeys for s in j.segments}


@dataclass(frozen=True)
class Constraints:
    arrival_deadline: datetime | None = None
    return_after: datetime | None = None
    allowed_cabins: frozenset[Cabin] = frozenset()
    max_total: Money | None = None
    permitted_providers: frozenset[str] = frozenset()
    max_stops: int = -1  # negative = unconstrained


@dataclass(frozen=True)
class Weights:
    cost: float = 0.40
    time: float = 0.25
    risk: float = 0.15
    preference: float = 0.10
    experience: float = 0.10

    @staticmethod
    def normalized(
        cost: float, time: float, risk: float, preference: float, experience: float
    ) -> Weights:
        """Falls back to the defaults when the caller sends no weights at all."""
        values = [cost, time, risk, preference, experience]
        if any(v < 0 for v in values):
            raise ValueError("weights must be >= 0")
        if sum(values) == 0:
            return Weights()
        return Weights(cost, time, risk, preference, experience)

    @property
    def total(self) -> float:
        return self.cost + self.time + self.risk + self.preference + self.experience


@dataclass(frozen=True)
class Preferences:
    weights: Weights = field(default_factory=Weights)
    preferred_carriers: frozenset[str] = frozenset()
    preferred_hotels: frozenset[str] = frozenset()


@dataclass(frozen=True)
class Breakdown:
    cost: float
    time: float
    risk: float
    preference: float
    experience: float

    def weighted(self, w: Weights) -> float:
        return (
            w.cost * self.cost
            + w.time * self.time
            + w.risk * self.risk
            + w.preference * self.preference
            + w.experience * self.experience
        ) / w.total


@dataclass(frozen=True)
class Ranked:
    bundle_id: str
    feasible: bool
    score: float
    breakdown: Breakdown
    infeasibility_reasons: tuple[str, ...]
    rank: int


ZERO_BREAKDOWN = Breakdown(0.0, 0.0, 0.0, 0.0, 0.0)
UTC = UTC
