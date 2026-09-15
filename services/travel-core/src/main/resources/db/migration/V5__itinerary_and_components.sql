-- Slice 3: multi-city itineraries and per-component status.

-- The frozen itinerary (legs, stays, transfers with stable component ids and dependencies), as
-- JSON; the legacy intent columns keep describing its first/last leg for Slice 1/2 readers.
ALTER TABLE trip ADD COLUMN itinerary JSONB;

-- Where each component stands. Written by the workflow (coordination state) as it plans, books,
-- compensates or recovers; the Order service remains the transactional truth for money and
-- supplier references, and the two are reconciled through idempotent updates.
CREATE TABLE trip_component (
    trip_id         VARCHAR(40)  NOT NULL REFERENCES trip (trip_id),
    tenant_id       VARCHAR(64)  NOT NULL,
    component_id    VARCHAR(40)  NOT NULL,
    type            VARCHAR(10)  NOT NULL,   -- AIR | HOTEL | GROUND
    status          VARCHAR(20)  NOT NULL,   -- PLANNED .. CONFIRMED | FAILED | CANCELLED | CANCEL_FAILED | CHANGED | SKIPPED
    offer_id        VARCHAR(40),
    provider        VARCHAR(100),
    external_ref    VARCHAR(100),
    total_currency  CHAR(3),
    total_minor     BIGINT,
    failure_code    VARCHAR(80),
    summary         VARCHAR(500),
    position        INTEGER      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (trip_id, component_id)
);

CREATE INDEX trip_component_by_trip ON trip_component (tenant_id, trip_id, position);
