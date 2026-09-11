from __future__ import annotations

from datetime import datetime, timedelta

import pytest

from travelos_optimization.model import UTC, Cabin, Candidate, Journey, Money, Segment

T0 = datetime(2026, 10, 6, 8, 0, tzinfo=UTC)  # 08:00Z departure baseline


def journey(
    *legs: tuple[str, str, str, int, int],
    start: datetime = T0,
    cabin: Cabin = Cabin.ECONOMY,
) -> Journey:
    """legs: (carrier, origin, destination, flight_minutes, layover_minutes_before)."""
    segments = []
    clock = start
    for carrier, origin, destination, minutes, layover in legs:
        clock = clock + timedelta(minutes=layover)
        dep = clock
        arr = dep + timedelta(minutes=minutes)
        segments.append(Segment(carrier, origin, destination, dep, arr, cabin))
        clock = arr
    return Journey(tuple(segments))


def candidate(
    bundle_id: str,
    cents: int,
    *journeys: Journey,
    provider: str = "sandbox-air",
    hotels: tuple[str, ...] = (),
) -> Candidate:
    return Candidate(bundle_id, Money("USD", cents), (provider,), tuple(journeys), hotels)


@pytest.fixture
def nonstop() -> Candidate:
    return candidate("bdl_nonstop", 52000, journey(("DL", "BOS", "SEA", 380, 0)))


@pytest.fixture
def one_stop_cheap() -> Candidate:
    return candidate(
        "bdl_onestop",
        41000,
        journey(("UA", "BOS", "ORD", 160, 0), ("UA", "ORD", "SEA", 260, 70)),
    )


@pytest.fixture
def red_eye_tight() -> Candidate:
    return candidate(
        "bdl_redeye",
        39000,
        journey(
            ("AA", "BOS", "DFW", 240, 0),
            ("AA", "DFW", "SEA", 250, 30),
            start=datetime(2026, 10, 6, 23, 30, tzinfo=UTC),
        ),
    )


@pytest.fixture
def business() -> Candidate:
    return candidate(
        "bdl_business", 130000, journey(("DL", "BOS", "SEA", 380, 0), cabin=Cabin.BUSINESS)
    )
