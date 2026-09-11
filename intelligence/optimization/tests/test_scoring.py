from __future__ import annotations

from datetime import datetime, timedelta

from conftest import T0, candidate, journey

from travelos_optimization.model import UTC, Cabin, Constraints, Money, Preferences
from travelos_optimization.scoring import breakdowns, infeasibility_reasons


def test_feasible_when_no_constraints(nonstop):
    assert infeasibility_reasons(nonstop, Constraints()) == ()


def test_every_violated_constraint_is_named(business, red_eye_tight):
    k = Constraints(
        arrival_deadline=T0 + timedelta(hours=5),
        allowed_cabins=frozenset({Cabin.ECONOMY}),
        max_total=Money("USD", 100000),
        permitted_providers=frozenset({"ndc-delta"}),
        max_stops=0,
    )
    assert infeasibility_reasons(business, k) == (
        "TOTAL_ABOVE_MAX",
        "PROVIDER_NOT_PERMITTED",
        "CABIN_NOT_ALLOWED",
        "ARRIVES_AFTER_DEADLINE",
    )
    assert "TOO_MANY_STOPS" in infeasibility_reasons(red_eye_tight, k)


def test_return_after_and_currency():
    k = Constraints(return_after=T0 + timedelta(days=2), max_total=Money("EUR", 1))
    c = candidate(
        "bdl_rt",
        60000,
        journey(("DL", "BOS", "SEA", 380, 0)),
        journey(("DL", "SEA", "BOS", 330, 0), start=T0 + timedelta(days=1)),
    )
    assert infeasibility_reasons(c, k) == ("CURRENCY_MISMATCH", "RETURNS_TOO_EARLY")


def test_scores_are_relative_to_the_feasible_set(nonstop, one_stop_cheap, red_eye_tight):
    scores = breakdowns([nonstop, one_stop_cheap, red_eye_tight], Preferences())

    assert scores["bdl_redeye"].cost == 100.0  # cheapest
    assert scores["bdl_nonstop"].cost == 0.0  # most expensive
    assert 0.0 < scores["bdl_onestop"].cost < 100.0

    assert scores["bdl_nonstop"].time == 100.0  # 380 min vs 490 vs 520
    assert scores["bdl_redeye"].time == 0.0

    assert scores["bdl_nonstop"].risk == 100.0
    assert scores["bdl_onestop"].risk == 75.0  # one stop, comfortable connection
    assert scores["bdl_redeye"].risk == 55.0  # one stop AND a 30-minute connection

    assert scores["bdl_nonstop"].experience == 100.0  # 08:00 departure
    assert scores["bdl_redeye"].experience == 0.0  # 23:30 departure


def test_single_feasible_candidate_gets_full_relative_scores(nonstop):
    scores = breakdowns([nonstop], Preferences())
    assert scores["bdl_nonstop"].cost == 100.0
    assert scores["bdl_nonstop"].time == 100.0


def test_preference_rewards_preferred_carriers(nonstop, one_stop_cheap):
    p = Preferences(preferred_carriers=frozenset({"DL"}))
    scores = breakdowns([nonstop, one_stop_cheap], p)
    assert scores["bdl_nonstop"].preference == 100.0
    assert scores["bdl_onestop"].preference == 0.0


def test_early_morning_departure_is_a_half_penalty():
    c = candidate(
        "bdl_dawn",
        50000,
        journey(("DL", "BOS", "SEA", 380, 0), start=datetime(2026, 10, 6, 6, 30, tzinfo=UTC)),
    )
    assert breakdowns([c], Preferences())["bdl_dawn"].experience == 50.0
