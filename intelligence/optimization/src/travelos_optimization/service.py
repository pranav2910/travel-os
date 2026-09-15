"""gRPC servicer: protobuf in, engine, protobuf out. The only place the two vocabularies meet."""

from __future__ import annotations

import logging
import re
from datetime import datetime

import grpc

from travelos.common.v1 import common_pb2
from travelos.offer.v1 import offer_pb2
from travelos.optimization.v1 import optimization_pb2, optimization_pb2_grpc
from travelos_optimization import events, ids, itinerary, solver, tracing
from travelos_optimization.itinerary import Component, ComponentOffer, ItineraryConstraints
from travelos_optimization.model import (
    UTC,
    Cabin,
    Candidate,
    Constraints,
    Journey,
    Money,
    Preferences,
    Segment,
    Weights,
)

log = logging.getLogger(__name__)

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


def _ts(ts) -> datetime | None:
    if ts.seconds == 0 and ts.nanos == 0:
        return None
    return datetime.fromtimestamp(ts.seconds + ts.nanos / 1e9, tz=UTC)


def _money(m: common_pb2.Money) -> Money:
    return Money(m.currency, m.amount_minor)


def _cabin(c: int) -> Cabin:
    return {
        common_pb2.PREMIUM_ECONOMY: Cabin.PREMIUM_ECONOMY,
        common_pb2.BUSINESS: Cabin.BUSINESS,
        common_pb2.FIRST: Cabin.FIRST,
    }.get(c, Cabin.ECONOMY)


def _journey(j: offer_pb2.Journey) -> Journey | None:
    if not j.segments:
        return None
    segments = []
    for s in j.segments:
        dep, arr = _ts(s.departure), _ts(s.arrival)
        if dep is None or arr is None:
            raise ValueError(f"segment {s.flight_number or s.segment_id} lacks departure/arrival")
        segments.append(Segment(s.carrier, s.origin, s.destination, dep, arr, _cabin(s.cabin)))
    return Journey(tuple(segments))


def candidate(b: offer_pb2.Bundle) -> Candidate:
    journeys: list[Journey] = []
    providers: list[str] = []
    hotels: list[str] = []
    total: Money | None = _money(b.total) if b.HasField("total") else None
    for o in b.offers:
        providers.append(o.provider)
        if total is None:
            total = _money(o.total)
        elif not b.HasField("total"):
            if o.total.currency != total.currency:
                raise ValueError(f"bundle {b.bundle_id} mixes currencies")
            total = Money(total.currency, total.amount_minor + o.total.amount_minor)
        if o.HasField("air"):
            for j in (o.air.outbound, o.air.inbound):
                journey = _journey(j)
                if journey is not None:
                    journeys.append(journey)
        if o.HasField("hotel"):
            hotels.append(o.hotel.property_id)
    if total is None:
        raise ValueError(f"bundle {b.bundle_id} has no offers")
    return Candidate(b.bundle_id, total, tuple(providers), tuple(journeys), tuple(hotels))


def constraints(k: optimization_pb2.ConstraintSet) -> Constraints:
    return Constraints(
        arrival_deadline=_ts(k.arrival_deadline),
        return_after=_ts(k.return_after),
        allowed_cabins=frozenset(_cabin(c) for c in k.allowed_cabins),
        max_total=_money(k.max_total) if k.HasField("max_total") else None,
        permitted_providers=frozenset(k.permitted_providers),
        max_stops=k.max_stops if k.HasField("max_stops") else -1,
    )


def preferences(p: optimization_pb2.OptimizationPreferences) -> Preferences:
    w = p.weights
    return Preferences(
        weights=Weights.normalized(w.cost, w.time, w.risk, w.preference, w.experience),
        preferred_carriers=frozenset(p.preferred_carriers),
        preferred_hotels=frozenset(p.preferred_hotels),
    )


def _component_offer(o: offer_pb2.Offer) -> ComponentOffer:
    if o.HasField("air"):
        journeys = [j for j in (_journey(o.air.outbound), _journey(o.air.inbound)) if j is not None]
        if not journeys:
            raise ValueError(f"offer {o.offer_id} has no timed segments")
        first, last = journeys[0], journeys[-1]
        return ComponentOffer(
            offer_id=o.offer_id,
            provider=o.provider,
            total=_money(o.total),
            kind="AIR",
            departure=first.departure,
            arrival=last.arrival,
            cabins=frozenset(s.cabin for j in journeys for s in j.segments),
            stops=max(j.stops for j in journeys),
            carriers=frozenset(s.carrier for j in journeys for s in j.segments),
            refundable=o.refundable,
            duration_minutes=sum(j.duration_minutes for j in journeys),
        )
    if o.HasField("hotel"):
        h = o.hotel
        return ComponentOffer(
            offer_id=o.offer_id,
            provider=o.provider,
            total=_money(o.total),
            kind="HOTEL",
            check_in_date=h.check_in_date or None,
            check_out_date=h.check_out_date or None,
            hotel_id=h.property_id or None,
            refundable=o.refundable,
            duration_minutes=0,
        )
    if o.HasField("ground"):
        g = o.ground
        pickup, dropoff = _ts(g.pickup), _ts(g.dropoff)
        return ComponentOffer(
            offer_id=o.offer_id,
            provider=o.provider,
            total=_money(o.total),
            kind="GROUND",
            departure=pickup,
            arrival=dropoff,
            refundable=o.refundable,
            duration_minutes=int((dropoff - pickup).total_seconds() // 60)
            if pickup and dropoff
            else 0,
        )
    raise ValueError(f"offer {o.offer_id} is neither air, hotel nor ground")


def component(c: optimization_pb2.ComponentCandidates) -> Component:
    if not c.component_id:
        raise ValueError("every component needs a component_id")
    kind = {offer_pb2.AIR: "AIR", offer_pb2.HOTEL: "HOTEL", offer_pb2.GROUND: "GROUND"}.get(c.type)
    if kind is None:
        raise ValueError(f"component {c.component_id} has no type")
    offers = tuple(_component_offer(o) for o in c.offers)
    for o in offers:
        if o.kind != kind:
            raise ValueError(f"component {c.component_id} ({kind}) holds a {o.kind} offer")
    return Component(
        component_id=c.component_id,
        kind=kind,
        sequence=c.sequence,
        required=c.required,
        offers=offers,
        depends_on=tuple(c.depends_on),
        not_before=_ts(c.not_before),
        not_after=_ts(c.not_after),
        check_in_date=c.check_in_date or None,
        check_out_date=c.check_out_date or None,
        time_zone=c.time_zone or None,
        arrival_leg_id=c.arrival_leg_id or None,
        departure_leg_id=c.departure_leg_id or None,
    )


def itinerary_constraints(k: optimization_pb2.ItineraryConstraints) -> ItineraryConstraints:
    return ItineraryConstraints(
        max_total=_money(k.max_total) if k.HasField("max_total") else None,
        allowed_cabins=frozenset(_cabin(c) for c in k.allowed_cabins),
        permitted_providers=frozenset(k.permitted_providers),
        min_connection_minutes=k.min_connection_minutes or 60,
        transfer_after_arrival_minutes=k.transfer_after_arrival_minutes or 45,
        transfer_before_departure_minutes=k.transfer_before_departure_minutes or 90,
        currency=k.currency or None,
        max_stops=k.max_stops if k.HasField("max_stops") else -1,
    )


def _ranked(r) -> optimization_pb2.RankedCandidate:
    return optimization_pb2.RankedCandidate(
        bundle_id=r.bundle_id,
        score=r.score,
        breakdown=_breakdown(r.breakdown),
        feasible=r.feasible,
        infeasibility_reasons=list(r.infeasibility_reasons),
        rank=r.rank,
    )


def _breakdown(b) -> optimization_pb2.ScoreBreakdown:
    return optimization_pb2.ScoreBreakdown(
        cost=b.cost, time=b.time, risk=b.risk, preference=b.preference, experience=b.experience
    )


class OptimizationService(optimization_pb2_grpc.OptimizationServiceServicer):
    def __init__(self, publisher: events.EventPublisher | None = None) -> None:
        self._publisher = publisher or events.NoopPublisher()

    def OptimizeTrip(self, request, context):  # noqa: N802 (gRPC naming)
        require_context(request.ctx, context)
        if not request.trip_id:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "trip_id is required")
        try:
            candidates = [candidate(b) for b in request.candidates]
            k = constraints(request.constraints)
            p = preferences(request.preferences)
        except ValueError as e:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(e))
            raise  # unreachable; keeps type checkers happy

        result = solver.optimize(candidates, k, p)
        run_id = ids.new_id("opt")
        feasible = sum(1 for r in result.ranking if r.feasible)
        log.info(
            "optimized trip=%s tenant=%s candidates=%d feasible=%d selected=%s in %dms",
            request.trip_id,
            request.ctx.tenant_id,
            len(candidates),
            feasible,
            result.selected_bundle_id or "-",
            result.solve_time_ms,
        )
        response = optimization_pb2.OptimizeTripResponse(
            optimization_run_id=run_id,
            selected_bundle_id=result.selected_bundle_id,
            solver=result.solver,
            solve_time_ms=result.solve_time_ms,
        )
        for r in result.ranking:
            response.ranking.add(
                bundle_id=r.bundle_id,
                score=r.score,
                breakdown=optimization_pb2.ScoreBreakdown(
                    cost=r.breakdown.cost,
                    time=r.breakdown.time,
                    risk=r.breakdown.risk,
                    preference=r.breakdown.preference,
                    experience=r.breakdown.experience,
                ),
                feasible=r.feasible,
                infeasibility_reasons=list(r.infeasibility_reasons),
                rank=r.rank,
            )
        self._publish_completed(request, run_id, result, len(candidates), feasible, context)
        return response

    def OptimizeItinerary(self, request, context):  # noqa: N802 (gRPC naming)
        require_context(request.ctx, context)
        if not request.trip_id:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "trip_id is required")
        if not request.components:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "at least one component is required")
        try:
            components = [component(c) for c in request.components]
            k = itinerary_constraints(request.constraints)
            p = preferences(request.preferences)
        except ValueError as e:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(e))
            raise  # unreachable; keeps type checkers happy

        result = itinerary.optimize_itinerary(components, k, p)
        run_id = ids.new_id("opt")
        offers_by_id = {o.offer_id: o for c in request.components for o in c.offers}
        log.info(
            "optimized itinerary trip=%s tenant=%s components=%d combinations=%d"
            " feasible=%s in %dms",
            request.trip_id,
            request.ctx.tenant_id,
            len(components),
            result.combinations,
            result.feasible,
            result.solve_time_ms,
        )
        response = optimization_pb2.OptimizeItineraryResponse(
            optimization_run_id=run_id,
            infeasibility_reasons=list(result.reasons),
            score=result.score,
            breakdown=_breakdown(result.breakdown),
            solver=result.solver,
            solve_time_ms=result.solve_time_ms,
            combinations_considered=result.combinations,
        )
        bundle_id = ""
        if result.feasible:
            bundle_id = ids.new_id("bdl")
            bundle = response.selected
            bundle.bundle_id = bundle_id
            total: Money | None = None
            for c in components:
                oid = result.selected.get(c.component_id)
                if not oid:
                    continue
                chosen = offer_pb2.Offer()
                chosen.CopyFrom(offers_by_id[oid])
                chosen.component_id = c.component_id
                bundle.offers.append(chosen)
                money = _money(chosen.total)
                total = (
                    money
                    if total is None
                    else Money(total.currency, total.amount_minor + money.amount_minor)
                )
            if total is not None:
                bundle.total.currency = total.currency
                bundle.total.amount_minor = total.amount_minor
        for sel in result.selections:
            response.components.add(
                component_id=sel.component_id,
                offer_id=sel.offer_id,
                score=sel.score,
                breakdown=_breakdown(sel.breakdown),
                ranking=[_ranked(r) for r in sel.ranking],
                outcome=sel.outcome,
                infeasibility_reasons=list(sel.reasons),
            )
        self._publish_itinerary_completed(request, run_id, result, bundle_id, context)
        return response

    def _publish_itinerary_completed(self, request, run_id, result, bundle_id, context):
        data = {
            "optimizationRunId": run_id,
            "tripId": request.trip_id,
            "candidatesEvaluated": int(result.combinations),
            "feasibleCandidates": 1 if result.feasible else 0,
            "solver": result.solver,
            "solveTimeMs": int(result.solve_time_ms),
        }
        if bundle_id:
            data["selectedBundleId"] = bundle_id
            data["selectedScore"] = round(float(result.score), 3)
        event = events.envelope(
            "travel.optimization.completed",
            request.ctx.tenant_id,
            request.ctx.correlation_id,
            data,
            causation_id=request.ctx.causation_id or request.ctx.idempotency_key or None,
        )
        try:
            self._publisher.publish(event)
        except Exception as e:  # noqa: BLE001 — any publish failure means the run is not on record
            log.error("itinerary run %s completed but its event was not published: %s", run_id, e)
            context.abort(
                grpc.StatusCode.UNAVAILABLE,
                f"OPTIMIZATION_EVENT_NOT_PUBLISHED: {e}",
            )

    def _publish_completed(self, request, run_id, result, evaluated, feasible, context):
        """travel.optimization.completed: the run is a domain fact, not just a return value."""
        data = {
            "optimizationRunId": run_id,
            "tripId": request.trip_id,
            "candidatesEvaluated": evaluated,
            "feasibleCandidates": feasible,
            "solver": result.solver,
            "solveTimeMs": int(result.solve_time_ms),
        }
        if result.selected_bundle_id:
            data["selectedBundleId"] = result.selected_bundle_id
            selected = next(
                (r for r in result.ranking if r.bundle_id == result.selected_bundle_id), None
            )
            if selected is not None:
                data["selectedScore"] = round(float(selected.score), 3)
        event = events.envelope(
            "travel.optimization.completed",
            request.ctx.tenant_id,
            request.ctx.correlation_id,
            data,
            causation_id=request.ctx.causation_id or request.ctx.idempotency_key or None,
        )
        try:
            self._publisher.publish(event)
        except Exception as e:  # noqa: BLE001 — any publish failure means the run is not on record
            log.error(
                "optimization run %s completed but its event was not published: %s", run_id, e
            )
            context.abort(
                grpc.StatusCode.UNAVAILABLE,
                f"OPTIMIZATION_EVENT_NOT_PUBLISHED: {e}",
            )
