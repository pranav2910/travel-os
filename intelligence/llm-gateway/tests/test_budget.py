from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from travelos_llm_gateway.budget import BudgetExceededError, TenantBudget


def test_budget_is_per_tenant_per_utc_day():
    b = TenantBudget(1_000_000)
    now = datetime(2026, 9, 12, 23, 30, tzinfo=UTC)
    b.check("acme", now)
    assert b.charge("acme", now, 999_999) == 999_999
    b.check("acme", now)  # still under
    b.charge("acme", now, 1)
    with pytest.raises(BudgetExceededError):
        b.check("acme", now)
    b.check("globex", now)
    b.check("acme", now + timedelta(hours=1))  # next UTC day, fresh budget
