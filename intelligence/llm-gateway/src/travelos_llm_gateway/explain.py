"""Renders decision evidence to plain text for the explanation prompt (and the fake provider)."""

from __future__ import annotations

from typing import Any

from travelos.common.v1 import common_pb2
from travelos.llm.v1 import llm_pb2
from travelos.offer.v1 import offer_pb2
from travelos.optimization.v1 import optimization_pb2
from travelos_llm_gateway.providers import Evidence


def money(m: common_pb2.Money) -> str:
    if not m.currency:
        return "n/a"
    return f"{m.currency} {m.amount_minor / 100:.2f}"


def _flight(s: offer_pb2.FlightSegment) -> str:
    number = s.flight_number
    if s.carrier and not number.startswith(s.carrier):
        number = f"{s.carrier}{number}"
    return f"{number} {s.origin}-{s.destination}"


def _bundle_summary(b: offer_pb2.Bundle) -> str:
    parts: list[str] = []
    for o in b.offers:
        if o.HasField("air"):
            legs = [_flight(s) for s in o.air.outbound.segments]
            back = [_flight(s) for s in o.air.inbound.segments]
            desc = ", ".join(legs) or "flight"
            if back:
                desc += " / return " + ", ".join(back)
            parts.append(f"{desc} via {o.provider}" if o.provider else desc)
        elif o.HasField("hotel"):
            nights = f"{o.hotel.nights} night" + ("s" if o.hotel.nights != 1 else "")
            where = f" in {o.hotel.city}" if o.hotel.city else ""
            parts.append(
                f"hotel {o.hotel.name}{where} ({nights}, {o.hotel.check_in_date or 'dates n/a'})"
            )
        elif o.HasField("ground"):
            parts.append(
                f"{o.ground.vehicle_class.lower() or 'transfer'} transfer "
                f"{o.ground.pickup_location} to {o.ground.dropoff_location} "
                f"by {o.ground.vendor_name}"
            )
        else:
            parts.append(o.provider or "offer")
    return "; ".join(parts) or b.bundle_id


def _components(b: offer_pb2.Bundle) -> str:
    kinds = {"AIR": 0, "HOTEL": 0, "GROUND": 0}
    for o in b.offers:
        if o.HasField("air"):
            kinds["AIR"] += 1
        elif o.HasField("hotel"):
            kinds["HOTEL"] += 1
        elif o.HasField("ground"):
            kinds["GROUND"] += 1
    return ", ".join(
        f"{n} {name}"
        for name, n in (
            ("legs", kinds["AIR"]),
            ("stays", kinds["HOTEL"]),
            ("transfers", kinds["GROUND"]),
        )
        if n
    )


def _breakdown(rc: optimization_pb2.RankedCandidate) -> str:
    b = rc.breakdown
    return (
        f"cost {b.cost:.0f}, time {b.time:.0f}, risk {b.risk:.0f}, "
        f"preference {b.preference:.0f}, experience {b.experience:.0f}"
    )


def evidence(request: llm_pb2.ExplainTripRequest) -> Evidence:
    i = request.intent
    sel = request.selected
    pd = request.policy_decision
    route = f"{i.origin} to {i.destination}" if i.origin else "the trip"
    ranking = sorted(request.ranking, key=lambda r: r.rank or 999)
    chosen = next((r for r in ranking if r.bundle_id == sel.bundle_id), None)
    runner_up = next((r for r in ranking if r.bundle_id != sel.bundle_id and r.feasible), None)
    reasons = "; ".join(f"{r.code}: {r.message}" if r.message else r.code for r in pd.reasons)
    if i.HasField("itinerary") and i.itinerary.legs:
        legs = i.itinerary.legs
        route = " to ".join([legs[0].origin] + [leg.destination for leg in legs])
    facts: dict[str, Any] = {
        "route": route,
        "components": _components(sel) if len(sel.offers) > 1 else "",
        "selected": _bundle_summary(sel),
        "total": money(sel.total),
        "searched": request.candidates_searched,
        "permitted": request.candidates_permitted,
        "policy": f"{pd.policy_id} v{pd.policy_version}" if pd.policy_id else "",
        "outcome": _outcome_name(pd),
        "reasons": reasons,
        "requires_approval": pd.requires_approval,
        "score": f"{chosen.score:.1f}" if chosen else "",
        "breakdown": _breakdown(chosen) if chosen else "",
    }
    lines = [
        f"Audience: {request.audience or 'TRAVELER'}",
        f"Route: {route}",
        f"Purpose: {i.purpose or 'not stated'}",
        f"Round trip: {'yes' if i.HasField('return_after') else 'no'}; hotel: "
        f"{'yes' if i.hotel_required else 'no'}",
        f"Options searched: {request.candidates_searched}; permitted by policy: "
        f"{request.candidates_permitted}",
        f"Selected: {facts['selected']} at {facts['total']}",
    ]
    if facts["components"]:
        lines.append(f"Itinerary components: {facts['components']}")
    if chosen:
        lines.append(f"Optimizer score: {chosen.score:.1f} of 100 ({_breakdown(chosen)})")
    if runner_up:
        lines.append(f"Runner-up: {runner_up.bundle_id} scored {runner_up.score:.1f}")
    if pd.policy_id:
        lines.append(f"Policy: {facts['policy']}, outcome {facts['outcome']}")
        if reasons:
            lines.append(f"Policy reasons: {reasons}")
        lines.append(
            "Approval: required from " + (pd.approvers[0].role if pd.approvers else "a manager")
            if pd.requires_approval
            else "Approval: not required"
        )
    return Evidence(audience=request.audience or "TRAVELER", text="\n".join(lines), facts=facts)


def _outcome_name(pd) -> str:
    from travelos.policy.v1 import policy_pb2

    try:
        return policy_pb2.Outcome.Name(pd.outcome)
    except ValueError:
        return str(pd.outcome)


def disruption_evidence(request: llm_pb2.ExplainDisruptionRequest) -> Evidence:
    """Everything the narration may say about a disruption, rendered from evidence only.

    The supplier's free text is fenced as untrusted data; every other line comes from structured
    facts the platform decided. Nothing the supplier wrote reaches the facts dictionary.
    """
    pd = request.policy_decision
    original = _bundle_summary(request.original) if request.original.offers else "the itinerary"
    replacement = (
        _bundle_summary(request.replacement) if request.replacement.offers else "no replacement"
    )
    ranking = sorted(request.ranking, key=lambda r: r.rank or 999)
    chosen = next((r for r in ranking if r.bundle_id == request.replacement.bundle_id), None)
    runner_up = next(
        (r for r in ranking if r.bundle_id != request.replacement.bundle_id and r.feasible), None
    )
    reasons = "; ".join(f"{r.code}: {r.message}" if r.message else r.code for r in pd.reasons)
    autonomy = request.autonomy_outcome or _outcome_name(pd)
    incremental = money(request.incremental_cost)
    facts: dict[str, Any] = {
        "kind": "disruption",
        "type": request.disruption_type or "DISRUPTION",
        "supplier": request.supplier,
        "original": original,
        "replacement": replacement,
        "replacement_total": money(request.replacement.total),
        "incremental": incremental,
        "searched": request.candidates_searched,
        "permitted": request.candidates_permitted,
        "policy": f"{pd.policy_id} v{pd.policy_version}" if pd.policy_id else "",
        "outcome": _outcome_name(pd),
        "autonomy": autonomy,
        "reasons": reasons,
        "requires_approval": pd.requires_approval or autonomy == "ALLOW_WITH_APPROVAL",
        "approver": pd.approvers[0].role if pd.approvers else "a manager",
        "score": f"{chosen.score:.1f}" if chosen else "",
        "breakdown": _breakdown(chosen) if chosen else "",
    }
    notice = (request.supplier_reason or "").strip()
    lines = [
        f"Audience: {request.audience or 'TRAVELER'}",
        f"Disruption: {facts['type']} reported by {request.supplier or 'the supplier'}",
        "<supplier_notice>",
        notice or "(no message)",
        "</supplier_notice>",
        f"Original itinerary: {original}",
        f"Alternatives searched: {request.candidates_searched}; permitted by policy: "
        f"{request.candidates_permitted}",
        f"Replacement chosen by the optimizer: {replacement} at {facts['replacement_total']}",
        f"Incremental cost versus the original order: {incremental}",
    ]
    if chosen:
        lines.append(f"Optimizer score: {chosen.score:.1f} of 100 ({_breakdown(chosen)})")
    if runner_up:
        lines.append(f"Runner-up: {runner_up.bundle_id} scored {runner_up.score:.1f}")
    if pd.policy_id:
        lines.append(f"Policy: {facts['policy']}, decision on changing the order: {autonomy}")
        if reasons:
            lines.append(f"Policy reasons: {reasons}")
    lines.append(
        f"Approval: required from {facts['approver']}"
        if facts["requires_approval"]
        else "Approval: not required; the change is made automatically"
        if autonomy == "ALLOW"
        else f"Approval: the change is not permitted ({autonomy})"
    )
    return Evidence(audience=request.audience or "TRAVELER", text="\n".join(lines), facts=facts)
