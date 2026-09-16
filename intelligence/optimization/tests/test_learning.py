"""Learning inputs never touch feasibility or money.

SHADOW reports, ACTIVE applies, both bounded."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from travelos.optimization.v1 import optimization_pb2
from travelos_optimization import learning, solver
from travelos_optimization.itinerary import (
    Component,
    ComponentOffer,
    ItineraryConstraints,
    optimize_itinerary,
)
from travelos_optimization.model import Candidate, Constraints, Journey, Money, Preferences, Segment

T0 = datetime(2026, 10, 6, 12, 0, tzinfo=UTC)


def bundle(bundle_id: str, carrier: str, fare: int) -> Candidate:
    seg = Segment(carrier, "BOS", "SEA", T0, T0 + timedelta(hours=6), 1)
    return Candidate(bundle_id, Money("USD", fare), ("sandbox-air",), (Journey((seg,)),))


def inputs(mode: str, **adjustments: float) -> learning.Inputs:
    return learning.Inputs(
        mode=mode,
        profile_id="lp_01ARZ3NDEKTSV4RRFFQ69G5FB8",
        algorithm_version="reliability-v1",
        evidence_class="SANDBOX",
        adjustments={
            k: (learning.Adjustment(k, v, reason=f"{k} {v:+.1f}"),) for k, v in adjustments.items()
        },
        max_adjustment=10.0,
    )


# DL is cheapest (baseline winner by cost); UA costs 3% more.
CANDIDATES = [
    bundle("bdl_dl", "DL", 30000),
    bundle("bdl_ua", "UA", 30600),
    bundle("bdl_aa", "AA", 34000),
]


def test_off_or_absent_inputs_reproduce_the_baseline_exactly():
    base = solver.optimize(CANDIDATES, Constraints(), Preferences())
    off = solver.optimize(
        CANDIDATES, Constraints(), Preferences(), inputs("OFF", **{"air:DL": -10})
    )
    assert off.selected_bundle_id == base.selected_bundle_id == "bdl_dl"
    assert [r.score for r in off.ranking] == [r.score for r in base.ranking]
    assert off.learning is not None and off.learning.applied is False
    assert off.learning.fallback_reason == "MODE_OFF"
    assert base.learning is not None and base.learning.mode == "OFF"


def test_shadow_reports_the_alternative_and_executes_the_baseline():
    shadow = solver.optimize(
        CANDIDATES, Constraints(), Preferences(), inputs("SHADOW", **{"air:DL": -9})
    )
    assert shadow.selected_bundle_id == "bdl_dl"
    assert shadow.learning.applied is False
    assert shadow.learning.baseline_selected_id == "bdl_dl"
    assert shadow.learning.learned_selected_id == "bdl_ua"
    dl = next(c for c in shadow.learning.contributions if c.candidate_id == "bdl_dl")
    assert dl.adjustment == -9 and dl.learned_score == round(dl.baseline_score - 9, 4)
    assert dl.supplier_keys == ("air:DL",) and dl.reasons == ("air:DL -9.0",)
    # the executed ranking is the baseline one
    assert [r.bundle_id for r in shadow.ranking][0] == "bdl_dl"


def test_active_applies_the_bounded_adjustment_among_feasible_candidates():
    active = solver.optimize(
        CANDIDATES, Constraints(), Preferences(), inputs("ACTIVE", **{"air:DL": -9})
    )
    assert active.selected_bundle_id == "bdl_ua"
    assert active.learning.applied is True
    assert active.learning.baseline_selected_id == "bdl_dl"
    assert [r.bundle_id for r in active.ranking][0] == "bdl_ua"


def test_adjustments_are_clamped_and_unknown_keys_add_nothing():
    proto_strong = optimization_pb2.LearningInputs(
        mode="ACTIVE", profile_id="lp_x", max_adjustment=10
    )
    proto_strong.adjustments.add(supplier_key="air:DL", adjustment=-90.0)
    proto_strong.adjustments.add(supplier_key="air:AA", adjustment=90.0)
    strong = learning.from_proto(proto_strong)
    assert strong.adjustments["air:DL"][0].adjustment == -10.0
    assert strong.adjustments["air:AA"][0].adjustment == 10.0
    assert learning.contribution_for(("air:ZZ",), strong) == (0.0, ())
    total, _ = learning.contribution_for(
        ("air:AA", "hotel:x"), inputs("ACTIVE", **{"air:AA": 8, "hotel:x": 8})
    )
    assert total == 10.0  # the sum is bounded too
    proto = optimization_pb2.LearningInputs(mode="ACTIVE", profile_id="lp_x", max_adjustment=999)
    assert learning.from_proto(proto).max_adjustment == learning.HARD_MAX_ADJUSTMENT
    assert learning.from_proto(optimization_pb2.LearningInputs()) is None


def test_learning_never_makes_an_infeasible_or_over_budget_candidate_feasible():
    k = Constraints(max_total=Money("USD", 30500))  # only DL fits the budget
    result = solver.optimize(
        CANDIDATES, k, Preferences(), inputs("ACTIVE", **{"air:DL": -10, "air:UA": 10})
    )
    assert result.selected_bundle_id == "bdl_dl"
    ua = next(r for r in result.ranking if r.bundle_id == "bdl_ua")
    assert ua.feasible is False and "TOTAL_ABOVE_MAX" in ua.infeasibility_reasons
    assert result.learning.applied is True and result.learning.learned_selected_id == "bdl_dl"


def _hotel(offer_id: str, hotel_id: str, total: int) -> ComponentOffer:
    return ComponentOffer(
        offer_id=offer_id,
        provider="sandbox-hotel",
        total=Money("USD", total),
        kind="HOTEL",
        check_in_date="2026-10-06",
        check_out_date="2026-10-08",
        hotel_id=hotel_id,
    )


def test_itinerary_learning_is_bounded_shadowed_and_applied_per_component():
    stay = Component(
        "cmp_stay",
        "HOTEL",
        1,
        True,
        (
            _hotel("off_a", "SBH-A", 23800),
            _hotel("off_b", "SBH-B", 23900),
            _hotel("off_c", "SBH-C", 30000),
        ),
        check_in_date="2026-10-06",
        check_out_date="2026-10-08",
        time_zone="America/Los_Angeles",
    )
    k = ItineraryConstraints(currency="USD")
    base = optimize_itinerary([stay], k, Preferences())
    assert base.selected == {"cmp_stay": "off_a"}
    shadow = optimize_itinerary([stay], k, Preferences(), inputs("SHADOW", **{"hotel:SBH-A": -8}))
    assert shadow.selected == {"cmp_stay": "off_a"}
    assert shadow.learning.baseline_selected_id == "cmp_stay=off_a"
    assert shadow.learning.learned_selected_id == "cmp_stay=off_b"
    assert shadow.learning.applied is False
    active = optimize_itinerary([stay], k, Preferences(), inputs("ACTIVE", **{"hotel:SBH-A": -8}))
    assert active.selected == {"cmp_stay": "off_b"}
    assert active.learning.applied is True
    # the budget still decides feasibility: a learned preference cannot buy the dearer room
    tight = ItineraryConstraints(currency="USD", max_total=Money("USD", 23850))
    limited = optimize_itinerary(
        [stay], tight, Preferences(), inputs("ACTIVE", **{"hotel:SBH-A": -10, "hotel:SBH-B": 10})
    )
    assert limited.selected == {"cmp_stay": "off_a"}


def test_reliability_and_a_travelers_preference_on_one_key_both_count_bounded():
    proto = optimization_pb2.LearningInputs(mode="ACTIVE", profile_id="lp_x", max_adjustment=10.0)
    proto.adjustments.add(
        supplier_key="air:AS", adjustment=-10.0, source="SUPPLIER_RELIABILITY", reason="unreliable"
    )
    proto.adjustments.add(
        supplier_key="air:AS", adjustment=-1.25, source="TRAVELER_PREFERENCE", reason="disliked"
    )
    inputs = learning.from_proto(proto)
    assert len(inputs.adjustments["air:AS"]) == 2
    total, reasons = learning.contribution_for(("air:AS",), inputs)
    assert total == -10.0  # -11.25 bounded to the profile's maximum
    assert reasons == ("unreliable", "disliked")
