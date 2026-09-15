"""The itinerary solver: coherent multi-component selection with named infeasibility."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from travelos_optimization.itinerary import (
    Component,
    ComponentOffer,
    ItineraryConstraints,
    optimize_itinerary,
)
from travelos_optimization.model import Cabin, Money, Preferences

T0 = datetime(2026, 10, 6, 12, 0, tzinfo=UTC)  # 08:00 Eastern


def usd(cents: int) -> Money:
    return Money("USD", cents)


def flight(offer_id, cents, dep_h, hours, stops=0, cabin=Cabin.ECONOMY, day=0, carrier="DL"):
    dep = T0 + timedelta(days=day, hours=dep_h)
    return ComponentOffer(
        offer_id=offer_id,
        provider="sandbox-air",
        total=usd(cents),
        kind="AIR",
        departure=dep,
        arrival=dep + timedelta(hours=hours),
        cabins=frozenset({cabin}),
        stops=stops,
        carriers=frozenset({carrier}),
        duration_minutes=hours * 60,
    )


def hotel(offer_id, cents, check_in, check_out, hotel_id="HTL-SEA-2", refundable=True):
    return ComponentOffer(
        offer_id=offer_id,
        provider="sandbox-hotel",
        total=usd(cents),
        kind="HOTEL",
        check_in_date=check_in,
        check_out_date=check_out,
        hotel_id=hotel_id,
        refundable=refundable,
    )


def ride(offer_id, cents, pickup, minutes=35, refundable=False):
    return ComponentOffer(
        offer_id=offer_id,
        provider="sandbox-ground",
        total=usd(cents),
        kind="GROUND",
        departure=pickup,
        arrival=pickup + timedelta(minutes=minutes),
        refundable=refundable,
        duration_minutes=minutes,
    )


def components():
    # leg 1 BOS->SEA on 6 Oct: 08:00 (+5h30 lands 13:30 UTC = 06:30 PT) or a cheaper 18:00 flight
    # landing 23:30 UTC (16:30 PT, still 6 Oct locally)
    leg1 = Component(
        "cmp_leg1",
        "AIR",
        1,
        True,
        (
            flight("f1-early", 52000, 0, 5),
            flight("f1-late", 38000, 6, 5),
            flight("f1-biz", 120000, 0, 5, cabin=Cabin.BUSINESS),
        ),
        not_before=T0,
        not_after=T0 + timedelta(hours=13),
    )
    # leg 2 SEA->SFO on 8 Oct (day 2): 10:00 UTC or 20:00 UTC
    leg2 = Component(
        "cmp_leg2",
        "AIR",
        2,
        True,
        (flight("f2-am", 21000, -2, 2, day=2), flight("f2-pm", 16000, 8, 2, day=2)),
        depends_on=("cmp_leg1",),
        not_before=T0 + timedelta(days=2, hours=-4),
        not_after=T0 + timedelta(days=2, hours=12),
    )
    stay = Component(
        "cmp_stay",
        "HOTEL",
        3,
        True,
        (
            hotel("h-budget", 23800, "2026-10-06", "2026-10-08"),
            hotel("h-wrong-dates", 10000, "2026-10-07", "2026-10-08"),
        ),
        depends_on=("cmp_leg1", "cmp_leg2"),
        check_in_date="2026-10-06",
        check_out_date="2026-10-08",
        time_zone="America/Los_Angeles",
        arrival_leg_id="cmp_leg1",
        departure_leg_id="cmp_leg2",
    )
    # transfers after each possible landing: 14:15 UTC (after the early flight),
    # 00:15 next day (after the late one)
    xfer = Component(
        "cmp_xfer",
        "GROUND",
        4,
        True,
        (
            ride("r-early", 3900, T0 + timedelta(hours=2, minutes=15)),
            ride("r-late", 3900, T0 + timedelta(hours=12, minutes=15)),
            ride("r-early-sedan", 6500, T0 + timedelta(hours=2, minutes=15)),
        ),
        depends_on=("cmp_leg1",),
        arrival_leg_id="cmp_leg1",
    )
    return [leg1, leg2, stay, xfer]


def test_picks_a_coherent_itinerary_and_the_cheapest_compatible_transfer():
    result = optimize_itinerary(
        components(),
        ItineraryConstraints(currency="USD", allowed_cabins=frozenset({Cabin.ECONOMY})),
        Preferences(),
    )
    assert result.feasible, result.reasons
    # the cheap late flight wins the leg, and drags the transfer to the late pickup
    assert result.selected["cmp_leg1"] == "f1-late"
    assert result.selected["cmp_xfer"] == "r-late"
    assert result.selected["cmp_leg2"] == "f2-pm"
    assert result.selected["cmp_stay"] == "h-budget"
    assert result.combinations == 2 * 2 * 1 * 3
    by_id = {s.component_id: s for s in result.selections}
    assert by_id["cmp_leg1"].outcome == "SELECTED"
    assert by_id["cmp_leg1"].ranking[-1].bundle_id == "f1-biz"
    assert by_id["cmp_leg1"].ranking[-1].infeasibility_reasons == ("CABIN_NOT_ALLOWED",)
    assert by_id["cmp_stay"].ranking[-1].infeasibility_reasons == ("HOTEL_DATES_MISMATCH",)
    assert result.solver.startswith("ortools-cpsat")
    assert 0 < result.score <= 100


def test_a_transfer_that_cannot_meet_any_flight_makes_the_itinerary_infeasible():
    parts = components()
    only_late_ride = Component(
        "cmp_xfer",
        "GROUND",
        4,
        True,
        (ride("r-late", 3900, T0 + timedelta(hours=12, minutes=15)),),
        depends_on=("cmp_leg1",),
        arrival_leg_id="cmp_leg1",
    )
    early_only_leg = Component(
        "cmp_leg1",
        "AIR",
        1,
        True,
        (flight("f1-early", 52000, 0, 5),),
        not_before=T0,
        not_after=T0 + timedelta(hours=13),
    )
    result = optimize_itinerary(
        [early_only_leg, parts[1], parts[2], only_late_ride],
        ItineraryConstraints(currency="USD"),
        Preferences(),
    )
    assert not result.feasible
    assert "TRANSFER_TOO_LONG_AFTER_LANDING:cmp_xfer" in result.reasons
    by_id = {s.component_id: s for s in result.selections}
    assert by_id["cmp_xfer"].outcome == "INFEASIBLE"
    assert any("cmp_xfer" in r for r in by_id["cmp_xfer"].reasons)


def test_leg_chronology_is_a_hard_constraint_with_a_named_reason():
    parts = components()
    # leg 2 departs before leg 1 lands: nothing chains
    too_early = Component(
        "cmp_leg2", "AIR", 2, True, (flight("f2-impossible", 9000, 2, 2),), depends_on=("cmp_leg1",)
    )
    result = optimize_itinerary(
        [parts[0], too_early], ItineraryConstraints(currency="USD"), Preferences()
    )
    assert not result.feasible
    assert result.reasons == ("LEG_CHRONOLOGY:cmp_leg1->cmp_leg2",)


def test_hotel_nights_must_be_covered_by_the_flights():
    parts = components()
    # the only flight lands on the 7th (local): a stay starting on the 6th cannot be covered
    lands_next_day = Component(
        "cmp_leg1",
        "AIR",
        1,
        True,
        (flight("f1-tomorrow", 30000, 0, 5, day=1),),
        not_before=T0 - timedelta(days=1),
        not_after=T0 + timedelta(days=2),
    )
    result = optimize_itinerary(
        [lands_next_day, parts[1], parts[2]], ItineraryConstraints(currency="USD"), Preferences()
    )
    assert not result.feasible
    assert "NO_HOTEL_COVERS_NIGHTS:cmp_stay" in result.reasons


def test_budget_and_currency_are_explicit():
    parts = components()
    over = optimize_itinerary(
        parts, ItineraryConstraints(currency="USD", max_total=usd(50000)), Preferences()
    )
    assert not over.feasible
    assert "TOTAL_ABOVE_MAX" in over.reasons
    sterling = Component(
        "cmp_stay",
        "HOTEL",
        3,
        True,
        (hotel("h-gbp", 20000, "2026-10-06", "2026-10-08"),),
        depends_on=("cmp_leg1",),
        check_in_date="2026-10-06",
        check_out_date="2026-10-08",
        time_zone="America/Los_Angeles",
        arrival_leg_id="cmp_leg1",
    )
    sterling = Component(
        **{
            **sterling.__dict__,
            "offers": (
                ComponentOffer(**{**sterling.offers[0].__dict__, "total": Money("GBP", 20000)}),
            ),
        }
    )
    mixed = optimize_itinerary(
        [parts[0], sterling], ItineraryConstraints(currency="USD"), Preferences()
    )
    assert not mixed.feasible
    assert mixed.reasons == ("REQUIRED_COMPONENT_UNFULFILLABLE:cmp_stay:CURRENCY_MISMATCH",)


def test_an_optional_component_is_skipped_not_fatal_and_fulfilled_when_it_can_be():
    parts = components()
    optional_unfulfillable = Component(
        "cmp_xfer",
        "GROUND",
        4,
        False,
        (ride("r-none", 3900, T0 + timedelta(days=3)),),
        depends_on=("cmp_leg1",),
        arrival_leg_id="cmp_leg1",
    )
    result = optimize_itinerary(
        [parts[0], parts[1], parts[2], optional_unfulfillable],
        ItineraryConstraints(currency="USD"),
        Preferences(),
    )
    assert result.feasible
    assert "cmp_xfer" not in result.selected
    assert {s.component_id: s.outcome for s in result.selections}["cmp_xfer"] == "SKIPPED"
    fulfilled = optimize_itinerary(
        [parts[0], parts[1], parts[2], Component(**{**parts[3].__dict__, "required": False})],
        ItineraryConstraints(currency="USD"),
        Preferences(),
    )
    assert fulfilled.selected["cmp_xfer"] == "r-late"


def test_deterministic():
    a = optimize_itinerary(components(), ItineraryConstraints(currency="USD"), Preferences())
    b = optimize_itinerary(components(), ItineraryConstraints(currency="USD"), Preferences())
    assert a.selected == b.selected
    assert [s.ranking for s in a.selections] == [s.ranking for s in b.selections]
