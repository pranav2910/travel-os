"""Per-tenant daily spend ceiling. A cheap HTTP request must never become an unbounded model bill.

In-memory and per-process for Slice 1 (one gateway instance). The interface is what matters: the
Redis-backed version drops in without touching the servicer.
"""

from __future__ import annotations

import threading
from datetime import UTC, datetime


class BudgetExceededError(Exception):
    def __init__(self, tenant: str, spent_micros: int, limit_micros: int):
        super().__init__(
            f"tenant {tenant} has spent {spent_micros / 1e6:.2f} USD of its "
            f"{limit_micros / 1e6:.2f} USD daily model budget"
        )
        self.tenant = tenant
        self.spent_micros = spent_micros
        self.limit_micros = limit_micros


class TenantBudget:
    def __init__(self, daily_limit_micros: int):
        self.daily_limit_micros = daily_limit_micros
        self._spent: dict[tuple[str, str], int] = {}
        self._lock = threading.Lock()

    @staticmethod
    def _day(now: datetime) -> str:
        return now.astimezone(UTC).strftime("%Y-%m-%d")

    def check(self, tenant: str, now: datetime) -> None:
        with self._lock:
            spent = self._spent.get((tenant, self._day(now)), 0)
        if spent >= self.daily_limit_micros:
            raise BudgetExceededError(tenant, spent, self.daily_limit_micros)

    def charge(self, tenant: str, now: datetime, micros: int) -> int:
        key = (tenant, self._day(now))
        with self._lock:
            self._spent[key] = self._spent.get(key, 0) + max(0, micros)
            return self._spent[key]

    def spent(self, tenant: str, now: datetime) -> int:
        with self._lock:
            return self._spent.get((tenant, self._day(now)), 0)
