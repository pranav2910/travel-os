from __future__ import annotations

from datetime import UTC, datetime

import pytest
from google.protobuf import timestamp_pb2

from travelos.common.v1 import common_pb2

NOW = datetime(2026, 9, 12, 12, 0, tzinfo=UTC)


def ctx(tenant: str = "acme") -> common_pb2.RequestContext:
    return common_pb2.RequestContext(
        tenant_id=tenant,
        correlation_id="trip_01ARZ3NDEKTSV4RRFFQ69G5FAV",
        principal=common_pb2.Principal(kind=common_pb2.Principal.AGENT, id="agent/trip-planner/v1"),
    )


def ts(dt: datetime) -> timestamp_pb2.Timestamp:
    t = timestamp_pb2.Timestamp()
    t.FromDatetime(dt)
    return t


@pytest.fixture
def now() -> datetime:
    return NOW
