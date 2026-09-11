from __future__ import annotations

from datetime import timedelta

from conftest import T0, candidate, journey

from travelos_optimization.model import Cabin, Constraints, Preferences, Weights
from travelos_optimization.solver import optimize


def test_ranks_feasible_first_and_explains_the_rest(
    nonstop, one_stop_cheap, red_eye_tight, business
):
    k = Constraints(allowed_cabins=frozenset({Cabin.ECONOMY}))
    result = optimize([business, red_eye_tight, nonstop, one_stop_cheap], k, Preferences())

    assert result.solver.startswith("ortools-cpsat")
    assert [r.bundle_id for r in result.ranking][-1] == "bdl_business"
    assert result.ranking[-1].feasible is False
    assert result.ranking[-1].infeasibility_reasons == ("CABIN_NOT_ALLOWED",)
    assert result.ranking[-1].score == 0.0
    assert [r.rank for r in result.ranking] == [1, 2, 3, 4]
    assert result.selected_bundle_id == result.ranking[0].bundle_id
    assert all(r.feasible for r in result.ranking[:3])
    assert result.ranking[0].score >= result.ranking[1].score >= result.ranking[2].score


def test_weights_change_the_winner(nonstop, one_stop_cheap, red_eye_tight):
    cost_first = Preferences(weights=Weights(cost=1.0, time=0, risk=0, preference=0, experience=0))
    comfort_first = Preferences(
        weights=Weights(cost=0, time=0.4, risk=0.3, preference=0, experience=0.3)
    )
    candidates = [nonstop, one_stop_cheap, red_eye_tight]
    assert optimize(candidates, Constraints(), cost_first).selected_bundle_id == "bdl_redeye"
    assert optimize(candidates, Constraints(), comfort_first).selected_bundle_id == "bdl_nonstop"


def test_ties_break_on_cost_then_id():
    a = candidate("bdl_b", 50000, journey(("DL", "BOS", "SEA", 380, 0)))
    b = candidate("bdl_a", 50000, journey(("DL", "BOS", "SEA", 380, 0)))
    c = candidate("bdl_c", 49000, journey(("DL", "BOS", "SEA", 380, 0)))
    # Identical on every axis except cost: c is cheapest and wins outright.
    result = optimize([a, b, c], Constraints(), Preferences())
    assert result.selected_bundle_id == "bdl_c"
    # a and b are identical: id decides, deterministically.
    result = optimize([a, b], Constraints(), Preferences())
    assert result.selected_bundle_id == "bdl_a"
    assert [r.bundle_id for r in result.ranking] == ["bdl_a", "bdl_b"]


def test_nothing_feasible_selects_nothing(business):
    result = optimize(
        [business], Constraints(allowed_cabins=frozenset({Cabin.ECONOMY})), Preferences()
    )
    assert result.selected_bundle_id == ""
    assert result.ranking[0].feasible is False


def test_deterministic_across_runs(nonstop, one_stop_cheap, red_eye_tight):
    k = Constraints(arrival_deadline=T0 + timedelta(hours=12))
    first = optimize([nonstop, one_stop_cheap, red_eye_tight], k, Preferences())
    second = optimize([nonstop, one_stop_cheap, red_eye_tight], k, Preferences())
    assert first.selected_bundle_id == second.selected_bundle_id
    assert [(r.bundle_id, r.score, r.rank) for r in first.ranking] == [
        (r.bundle_id, r.score, r.rank) for r in second.ranking
    ]
