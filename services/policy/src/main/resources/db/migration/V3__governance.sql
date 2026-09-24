-- Phase 7: governance. Scoped policy assignment, budgets with reservations, supplier agreements.

-- Which policy applies to a part of the organization. The default (policy_assignment) still applies
-- when nothing more specific matches. One assignment per (kind, id).
CREATE TABLE policy_scope (
    scope_id     VARCHAR(40)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    -- EMPLOYEE | PROJECT | COST_CENTER | DEPARTMENT | LEGAL_ENTITY | OFFICE
    scope_kind   VARCHAR(20)  NOT NULL,
    scope_ref    VARCHAR(64)  NOT NULL,
    policy_id    VARCHAR(64)  NOT NULL,
    created_by   VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    UNIQUE (tenant_id, scope_kind, scope_ref)
);

-- A budget for a scope and a period, in one currency. Reserved and committed amounts move under
-- the row lock, so two trips cannot both take the last of it.
CREATE TABLE budget (
    budget_id        VARCHAR(40)  PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    scope_kind       VARCHAR(20)  NOT NULL,
    scope_ref        VARCHAR(64)  NOT NULL,
    period_start     TIMESTAMPTZ  NOT NULL,
    period_end       TIMESTAMPTZ  NOT NULL,
    currency         CHAR(3)      NOT NULL,
    amount_minor     BIGINT       NOT NULL,
    reserved_minor   BIGINT       NOT NULL DEFAULT 0,
    committed_minor  BIGINT       NOT NULL DEFAULT 0,
    -- true: a trip that does not fit is denied; false: it needs Finance's approval
    hard             BOOLEAN      NOT NULL DEFAULT FALSE,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(128) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX budget_by_scope ON budget (tenant_id, scope_kind, scope_ref, period_start);

-- One reservation per trip and budget: RESERVED before the purchase, COMMITTED once bought (at
-- what it cost), RELEASED when the trip did not happen.
CREATE TABLE budget_reservation (
    reservation_id   VARCHAR(40)  PRIMARY KEY,
    budget_id        VARCHAR(40)  NOT NULL REFERENCES budget (budget_id),
    tenant_id        VARCHAR(64)  NOT NULL,
    trip_id          VARCHAR(64)  NOT NULL,
    traveler_id      VARCHAR(64),
    amount_minor     BIGINT       NOT NULL,
    status           VARCHAR(12)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    UNIQUE (budget_id, trip_id)
);
CREATE INDEX budget_reservation_by_trip ON budget_reservation (tenant_id, trip_id);

-- A negotiated or preferred supplier relationship. The planner searches under the rate codes; the
-- policy's suppliers section decides what a non-preferred choice means.
CREATE TABLE supplier_agreement (
    agreement_id   VARCHAR(40)  PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    provider       VARCHAR(60)  NOT NULL,
    kind           VARCHAR(10)  NOT NULL,
    carrier        VARCHAR(10),
    rate_code      VARCHAR(60),
    preferred      BOOLEAN      NOT NULL DEFAULT TRUE,
    negotiated     BOOLEAN      NOT NULL DEFAULT FALSE,
    contract_ref   VARCHAR(200),
    valid_from     TIMESTAMPTZ  NOT NULL,
    valid_until    TIMESTAMPTZ,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by     VARCHAR(128) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX supplier_agreement_by_tenant ON supplier_agreement (tenant_id, kind, provider);

-- Kafka is at-least-once; budget settlement from trip events is exactly-once per event id.
CREATE TABLE processed_event (
    event_id     VARCHAR(40)  PRIMARY KEY,
    event_type   VARCHAR(80)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL
);
