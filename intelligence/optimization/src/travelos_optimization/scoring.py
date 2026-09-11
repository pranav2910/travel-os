"""Feasibility and normalized scoring. Deterministic: no clock, no randomness, no I/O."""

from __future__ import annotations

from travelos_optimization.model import (
    ZERO_BREAKDOWN,
    Breakdown,
    Candidate,
    Constraints,
    Preferences,
)

TIGHT_CONNECTION_MINUTES = 45
STOP_PENALTY = 25.0
TIGHT_CONNECTION_PENALTY = 20.0


def infeasibility_reasons(c: Candidate, k: Constraints) -> tuple[str, ...]:
    """Every hard constraint the candidate violates, as stable machine codes."""
    reasons: list[str] = []
    if k.max_total is not None:
        if c.total.currency != k.max_total.currency:
            reasons.append("CURRENCY_MISMATCH")
        elif c.total.amount_minor > k.max_total.amount_minor:
            reasons.append("TOTAL_ABOVE_MAX")
    if k.permitted_providers and not set(c.providers) <= k.permitted_providers:
        reasons.append("PROVIDER_NOT_PERMITTED")
    if k.allowed_cabins and not c.cabins <= k.allowed_cabins:
        reasons.append("CABIN_NOT_ALLOWED")
    if k.max_stops >= 0 and any(j.stops > k.max_stops for j in c.journeys):
        reasons.append("TOO_MANY_STOPS")
    if c.journeys:
        outbound = c.journeys[0]
        if k.arrival_deadline is not None and outbound.arrival > k.arrival_deadline:
            reasons.append("ARRIVES_AFTER_DEADLINE")
        if k.return_after is not None and len(c.journeys) > 1:
            if c.journeys[1].departure < k.return_after:
                reasons.append("RETURNS_TOO_EARLY")
        for j in c.journeys:
            if any(m < 0 for m in j.connection_minutes()):
                reasons.append("SEGMENTS_OUT_OF_ORDER")
                break
    return tuple(reasons)


def _linear(value: float, best: float, worst: float) -> float:
    """100 at best, 0 at worst, linear between; 100 when there is no spread."""
    if worst == best:
        return 100.0
    return max(0.0, min(100.0, 100.0 * (worst - value) / (worst - best)))


def _risk(c: Candidate) -> float:
    if not c.journeys:
        return 100.0
    scores = []
    for j in c.journeys:
        s = 100.0 - STOP_PENALTY * j.stops
        s -= TIGHT_CONNECTION_PENALTY * sum(
            1 for m in j.connection_minutes() if 0 <= m < TIGHT_CONNECTION_MINUTES
        )
        scores.append(max(0.0, s))
    return sum(scores) / len(scores)


def _experience(c: Candidate) -> float:
    if not c.journeys:
        return 100.0
    scores = []
    for j in c.journeys:
        hour = j.departure.hour
        if hour < 6 or hour >= 22:
            scores.append(0.0)
        elif hour < 8 or hour >= 20:
            scores.append(50.0)
        else:
            scores.append(100.0)
    return sum(scores) / len(scores)


def _preference(c: Candidate, p: Preferences) -> float:
    score = 100.0
    if p.preferred_carriers and c.carriers:
        matched = len(c.carriers & p.preferred_carriers)
        score = 100.0 * matched / len(c.carriers)
    if p.preferred_hotels and c.hotel_ids:
        hotel = 100.0 if set(c.hotel_ids) & p.preferred_hotels else 0.0
        score = (score + hotel) / 2
    return score


def breakdowns(feasible: list[Candidate], p: Preferences) -> dict[str, Breakdown]:
    """Scores for the feasible set; cost and time are relative to that set only."""
    if not feasible:
        return {}
    costs = [c.total.amount_minor for c in feasible]
    times = [sum(j.duration_minutes for j in c.journeys) for c in feasible]
    result: dict[str, Breakdown] = {}
    for c, cost, time in zip(feasible, costs, times, strict=True):
        result[c.bundle_id] = Breakdown(
            cost=_linear(cost, min(costs), max(costs)),
            time=_linear(time, min(times), max(times)),
            risk=_risk(c),
            preference=_preference(c, p),
            experience=_experience(c),
        )
    return result


def zero() -> Breakdown:
    return ZERO_BREAKDOWN
