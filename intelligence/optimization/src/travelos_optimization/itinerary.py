"""Slice 3: choose one offer per itinerary component so the whole trip is feasible and cheapest.

Pure: no protobuf, no clock, no I/O. Hard constraints make combinations infeasible and are
explained with stable reason codes; soft objectives are the same five as for single bundles, scored
per component relative to the other offers of that component.

Hard constraints:
  * one currency for the whole itinerary, providers permitted, cabins allowed, stops bounded
  * a leg flies inside its window; the next leg departs after the previous one lands plus a
    connection buffer (chronology)
  * an airport transfer is reachable: pickup after landing plus a buffer (and within a few hours),
    drop-off before departure minus a buffer
  * a stay covers its nights: the arriving leg lands on or before check-in (local date at the
    property), the departing leg leaves on or after check-out
  * the whole itinerary stays within the budget
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from ortools.sat.python import cp_model

from travelos_optimization.model import Breakdown, Cabin, Money, Preferences, Ranked, Weights
from travelos_optimization.solver import SCORE_SCALE, _solver_name

REACHABLE_AFTER_LANDING = timedelta(hours=4)
REACHABLE_BEFORE_DEPARTURE = timedelta(hours=6)


@dataclass(frozen=True)
class ComponentOffer:
    offer_id: str
    provider: str
    total: Money
    kind: str  # AIR | HOTEL | GROUND
    departure: datetime | None = None  # AIR: first departure; GROUND: pickup
    arrival: datetime | None = None  # AIR: last arrival; GROUND: drop-off
    cabins: frozenset[Cabin] = frozenset()
    stops: int = 0
    carriers: frozenset[str] = frozenset()
    check_in_date: str | None = None  # HOTEL: ISO local dates
    check_out_date: str | None = None
    hotel_id: str | None = None
    refundable: bool = True
    duration_minutes: int = 0


@dataclass(frozen=True)
class Component:
    component_id: str
    kind: str
    sequence: int
    required: bool
    offers: tuple[ComponentOffer, ...]
    depends_on: tuple[str, ...] = ()
    not_before: datetime | None = None
    not_after: datetime | None = None
    check_in_date: str | None = None
    check_out_date: str | None = None
    time_zone: str | None = None
    arrival_leg_id: str | None = None
    departure_leg_id: str | None = None


@dataclass(frozen=True)
class ItineraryConstraints:
    max_total: Money | None = None
    allowed_cabins: frozenset[Cabin] = frozenset()
    permitted_providers: frozenset[str] = frozenset()
    min_connection_minutes: int = 60
    transfer_after_arrival_minutes: int = 45
    transfer_before_departure_minutes: int = 90
    currency: str | None = None
    max_stops: int = -1


@dataclass(frozen=True)
class Selection:
    component_id: str
    offer_id: str
    outcome: str  # SELECTED | SKIPPED | INFEASIBLE
    score: float
    breakdown: Breakdown
    ranking: tuple[Ranked, ...]
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class ItineraryResult:
    selected: dict[str, str] = field(default_factory=dict)  # component id -> offer id
    selections: tuple[Selection, ...] = ()
    reasons: tuple[str, ...] = ()
    score: float = 0.0
    breakdown: Breakdown = Breakdown(0.0, 0.0, 0.0, 0.0, 0.0)
    solver: str = ""
    solve_time_ms: int = 0
    combinations: int = 0

    @property
    def feasible(self) -> bool:
        return bool(self.selected)


# ---------------------------------------------------------------- unary feasibility


def offer_reasons(c: Component, o: ComponentOffer, k: ItineraryConstraints) -> tuple[str, ...]:
    reasons: list[str] = []
    if k.currency and o.total.currency != k.currency:
        reasons.append("CURRENCY_MISMATCH")
    if k.permitted_providers and o.provider not in k.permitted_providers:
        reasons.append("PROVIDER_NOT_PERMITTED")
    if o.kind == "AIR":
        if k.allowed_cabins and not o.cabins <= k.allowed_cabins:
            reasons.append("CABIN_NOT_ALLOWED")
        if k.max_stops >= 0 and o.stops > k.max_stops:
            reasons.append("TOO_MANY_STOPS")
        if c.not_before is not None and o.departure is not None and o.departure < c.not_before:
            reasons.append("DEPARTS_BEFORE_WINDOW")
        if c.not_after is not None and o.arrival is not None and o.arrival > c.not_after:
            reasons.append("ARRIVES_AFTER_DEADLINE")
    elif o.kind == "GROUND":
        if c.not_before is not None and o.departure is not None and o.departure < c.not_before:
            reasons.append("PICKUP_BEFORE_WINDOW")
        if c.not_after is not None and o.departure is not None and o.departure > c.not_after:
            reasons.append("PICKUP_AFTER_WINDOW")
    elif o.kind == "HOTEL":
        if c.check_in_date and o.check_in_date and o.check_in_date != c.check_in_date:
            reasons.append("HOTEL_DATES_MISMATCH")
        if c.check_out_date and o.check_out_date and o.check_out_date != c.check_out_date:
            reasons.append("HOTEL_DATES_MISMATCH")
    return tuple(dict.fromkeys(reasons))


# ---------------------------------------------------------------- pairwise compatibility


def _local_date(at: datetime, zone: str | None) -> str:
    if zone:
        return at.astimezone(ZoneInfo(zone)).date().isoformat()
    return at.date().isoformat()


def compatible(
    a: Component, oa: ComponentOffer, b: Component, ob: ComponentOffer, k: ItineraryConstraints
) -> str | None:
    """None when the two offers can be booked together; otherwise the stable reason code."""
    # consecutive legs: b depends on a
    if a.kind == "AIR" and b.kind == "AIR" and a.component_id in b.depends_on:
        if oa.arrival is not None and ob.departure is not None:
            if ob.departure < oa.arrival + timedelta(minutes=k.min_connection_minutes):
                return f"LEG_CHRONOLOGY:{a.component_id}->{b.component_id}"
        return None
    if a.kind == "AIR" and b.kind == "GROUND":
        if b.arrival_leg_id == a.component_id and oa.arrival is not None and ob.departure:
            earliest = oa.arrival + timedelta(minutes=k.transfer_after_arrival_minutes)
            if ob.departure < earliest:
                return f"TRANSFER_BEFORE_LANDING:{b.component_id}"
            if ob.departure > oa.arrival + REACHABLE_AFTER_LANDING:
                return f"TRANSFER_TOO_LONG_AFTER_LANDING:{b.component_id}"
        if b.departure_leg_id == a.component_id and oa.departure is not None and ob.arrival:
            latest = oa.departure - timedelta(minutes=k.transfer_before_departure_minutes)
            if ob.arrival > latest:
                return f"TRANSFER_AFTER_DEPARTURE:{b.component_id}"
            if (
                ob.departure is not None
                and ob.departure < oa.departure - REACHABLE_BEFORE_DEPARTURE
            ):
                return f"TRANSFER_TOO_EARLY_BEFORE_DEPARTURE:{b.component_id}"
        return None
    if a.kind == "AIR" and b.kind == "HOTEL":
        check_in = ob.check_in_date or b.check_in_date
        check_out = ob.check_out_date or b.check_out_date
        if b.arrival_leg_id == a.component_id and oa.arrival is not None and check_in:
            if _local_date(oa.arrival, b.time_zone) > check_in:
                return f"NO_HOTEL_COVERS_NIGHTS:{b.component_id}"
        if b.departure_leg_id == a.component_id and oa.departure is not None and check_out:
            if _local_date(oa.departure, b.time_zone) < check_out:
                return f"HOTEL_ENDS_AFTER_DEPARTURE:{b.component_id}"
        return None
    return None


# ---------------------------------------------------------------- scoring per component


def _linear(value: float, best: float, worst: float) -> float:
    if worst == best:
        return 100.0
    return max(0.0, min(100.0, 100.0 * (worst - value) / (worst - best)))


def _breakdowns(c: Component, offers: list[ComponentOffer], p: Preferences) -> dict[str, Breakdown]:
    if not offers:
        return {}
    costs = [o.total.amount_minor for o in offers]
    times = [o.duration_minutes for o in offers]
    out: dict[str, Breakdown] = {}
    for o, cost, minutes in zip(offers, costs, times, strict=True):
        if o.kind == "AIR":
            risk = max(0.0, 100.0 - 25.0 * o.stops)
            hour = o.departure.hour if o.departure is not None else 12
            experience = (
                0.0 if hour < 6 or hour >= 22 else 50.0 if hour < 8 or hour >= 20 else 100.0
            )
            preference = 100.0
            if p.preferred_carriers and o.carriers:
                preference = 100.0 * len(o.carriers & p.preferred_carriers) / len(o.carriers)
        else:
            risk = 100.0 if o.refundable else 70.0
            experience = 100.0
            preference = 100.0
            if o.kind == "HOTEL" and p.preferred_hotels and o.hotel_id:
                preference = 100.0 if o.hotel_id in p.preferred_hotels else 0.0
        out[o.offer_id] = Breakdown(
            cost=_linear(cost, min(costs), max(costs)),
            time=_linear(minutes, min(times), max(times)),
            risk=risk,
            preference=preference,
            experience=experience,
        )
    return out


# ---------------------------------------------------------------- the solve


def optimize_itinerary(
    components: list[Component], k: ItineraryConstraints, p: Preferences
) -> ItineraryResult:
    started = time.perf_counter()
    weights: Weights = p.weights
    by_id = {c.component_id: c for c in components}
    ordered = sorted(components, key=lambda c: (c.sequence, c.component_id))

    unary: dict[str, dict[str, tuple[str, ...]]] = {}
    feasible: dict[str, list[ComponentOffer]] = {}
    scores: dict[str, dict[str, Breakdown]] = {}
    combinations = 1
    for c in ordered:
        unary[c.component_id] = {o.offer_id: offer_reasons(c, o, k) for o in c.offers}
        feasible[c.component_id] = [o for o in c.offers if not unary[c.component_id][o.offer_id]]
        scores[c.component_id] = _breakdowns(c, feasible[c.component_id], p)
        combinations *= max(1, len(feasible[c.component_id]))

    reasons: list[str] = []
    for c in ordered:
        if c.required and not feasible[c.component_id]:
            codes = sorted({r for rs in unary[c.component_id].values() for r in rs})
            reasons.append(
                f"REQUIRED_COMPONENT_UNFULFILLABLE:{c.component_id}"
                + (":" + ",".join(codes) if codes else "")
            )

    model = cp_model.CpModel()
    x: dict[tuple[str, str], cp_model.IntVar] = {}
    for c in ordered:
        for o in feasible[c.component_id]:
            x[(c.component_id, o.offer_id)] = model.NewBoolVar(f"{c.component_id}:{o.offer_id}")
        picks = [x[(c.component_id, o.offer_id)] for o in feasible[c.component_id]]
        if c.required:
            if picks:
                model.AddExactlyOne(picks)
        elif picks:
            model.AddAtMostOne(picks)

    # pairwise: every dependent pair of offers that cannot go together is forbidden
    pair_reasons: dict[tuple[str, str], set[str]] = {}
    pair_ok: dict[tuple[str, str], bool] = {}
    for b in ordered:
        for dep in b.depends_on:
            a = by_id.get(dep)
            if a is None:
                continue
            any_ok = False
            for oa in feasible[a.component_id]:
                for ob in feasible[b.component_id]:
                    why = compatible(a, oa, b, ob, k)
                    if why is None:
                        any_ok = True
                    else:
                        pair_reasons.setdefault((a.component_id, b.component_id), set()).add(why)
                        model.AddBoolOr(
                            [
                                x[(a.component_id, oa.offer_id)].Not(),
                                x[(b.component_id, ob.offer_id)].Not(),
                            ]
                        )
            pair_ok[(a.component_id, b.component_id)] = any_ok
            if (
                b.required
                and a.required
                and not any_ok
                and feasible[a.component_id]
                and feasible[b.component_id]
            ):
                reasons.extend(sorted(pair_reasons.get((a.component_id, b.component_id), set())))

    # budget over everything selected
    if k.max_total is not None:
        model.Add(
            sum(
                int(o.total.amount_minor) * x[(c.component_id, o.offer_id)]
                for c in ordered
                for o in feasible[c.component_id]
            )
            <= int(k.max_total.amount_minor)
        )

    # objective: weighted score, then cheaper, then fulfil optional components
    terms = []
    for c in ordered:
        offers = feasible[c.component_id]
        by_cost = sorted(offers, key=lambda o: (o.total.amount_minor, o.offer_id))
        rank = {o.offer_id: i for i, o in enumerate(by_cost)}
        n = len(offers)
        for o in offers:
            weighted = scores[c.component_id][o.offer_id].weighted(weights)
            value = int(round(weighted * SCORE_SCALE)) * (n + 1) - rank[o.offer_id]
            if not c.required:
                value += SCORE_SCALE * (n + 1)  # a fulfilled optional component beats an empty one
            terms.append(value * x[(c.component_id, o.offer_id)])
    if terms:
        model.Maximize(sum(terms))

    solver = cp_model.CpSolver()
    solver.parameters.num_workers = 1
    solver.parameters.random_seed = 7
    solver.parameters.max_time_in_seconds = 10.0
    status = solver.Solve(model) if x else cp_model.INFEASIBLE
    ok = status in (cp_model.OPTIMAL, cp_model.FEASIBLE) and not reasons

    selected: dict[str, str] = {}
    if ok:
        for (cid, oid), var in x.items():
            if solver.Value(var):
                selected[cid] = oid
    elif not reasons:
        if k.max_total is not None:
            cheapest = sum(
                min((o.total.amount_minor for o in feasible[c.component_id]), default=0)
                for c in ordered
                if c.required
            )
            if cheapest > k.max_total.amount_minor:
                reasons.append("TOTAL_ABOVE_MAX")
        if not reasons:
            reasons.append("NO_COMPATIBLE_COMBINATION")

    selections: list[Selection] = []
    total_breakdown = [0.0, 0.0, 0.0, 0.0, 0.0]
    total_score = 0.0
    counted = 0
    for c in ordered:
        chosen = selected.get(c.component_id, "")
        ranking: list[Ranked] = []
        feasible_sorted = sorted(
            feasible[c.component_id],
            key=lambda o: (
                -scores[c.component_id][o.offer_id].weighted(weights),
                o.total.amount_minor,
                o.offer_id,
            ),
        )
        infeasible_sorted = sorted(
            (o for o in c.offers if unary[c.component_id][o.offer_id]),
            key=lambda o: (o.total.amount_minor, o.offer_id),
        )
        for i, o in enumerate(feasible_sorted + infeasible_sorted, start=1):
            bad = unary[c.component_id][o.offer_id]
            ranking.append(
                Ranked(
                    bundle_id=o.offer_id,
                    feasible=not bad,
                    score=round(scores[c.component_id][o.offer_id].weighted(weights), 4)
                    if not bad
                    else 0.0,
                    breakdown=scores[c.component_id][o.offer_id]
                    if not bad
                    else Breakdown(0, 0, 0, 0, 0),
                    infeasibility_reasons=bad,
                    rank=i,
                )
            )
        if chosen:
            bd = scores[c.component_id][chosen]
            sc = round(bd.weighted(weights), 4)
            outcome = "SELECTED"
            comp_reasons: tuple[str, ...] = ()
            total_score += sc
            counted += 1
            for i, v in enumerate((bd.cost, bd.time, bd.risk, bd.preference, bd.experience)):
                total_breakdown[i] += v
        else:
            bd = Breakdown(0.0, 0.0, 0.0, 0.0, 0.0)
            sc = 0.0
            outcome = "SKIPPED" if (ok and not c.required) else "INFEASIBLE"
            comp_reasons = tuple(r for r in reasons if c.component_id in r)
            if not comp_reasons and not feasible[c.component_id]:
                comp_reasons = tuple(
                    sorted({r for rs in unary[c.component_id].values() for r in rs})
                )
        selections.append(
            Selection(c.component_id, chosen, outcome, sc, bd, tuple(ranking), comp_reasons)
        )

    elapsed_ms = int((time.perf_counter() - started) * 1000)
    if counted:
        total_breakdown = [v / counted for v in total_breakdown]
    return ItineraryResult(
        selected=selected,
        selections=tuple(selections),
        reasons=tuple(dict.fromkeys(reasons)),
        score=round(total_score / counted, 4) if counted else 0.0,
        breakdown=Breakdown(*total_breakdown),
        solver=_solver_name(),
        solve_time_ms=elapsed_ms,
        combinations=combinations,
    )
