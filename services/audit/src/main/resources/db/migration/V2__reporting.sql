-- Phase 9: reporting facts, one row per trip, maintained from the events as they are stored. The
-- audit_event table stays the truth; trip_fact is a derived view that makes spend, exception and
-- outcome reports plain SQL. Money is kept in the trip's own currency (integer minor units).
CREATE TABLE trip_fact (
    tenant_id          VARCHAR(64)  NOT NULL,
    trip_id            VARCHAR(40)  NOT NULL,
    traveler_id        VARCHAR(64),
    department_id      VARCHAR(64),
    cost_center_id     VARCHAR(64),
    project_id         VARCHAR(64),
    legal_entity_id    VARCHAR(64),
    office_id          VARCHAR(64),
    origin             VARCHAR(3),
    destination        VARCHAR(3),
    departs_at         TIMESTAMPTZ,
    returns_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ,
    booked_at          TIMESTAMPTZ,
    ended_at           TIMESTAMPTZ,
    status             VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',
    currency           CHAR(3),
    booked_minor       BIGINT       NOT NULL DEFAULT 0,
    captured_minor     BIGINT       NOT NULL DEFAULT 0,
    refunded_minor     BIGINT       NOT NULL DEFAULT 0,
    credit_minor       BIGINT       NOT NULL DEFAULT 0,
    incremental_minor  BIGINT       NOT NULL DEFAULT 0,
    policy_violations  INTEGER      NOT NULL DEFAULT 0,
    approvals_requested INTEGER     NOT NULL DEFAULT 0,
    approvals_rejected INTEGER      NOT NULL DEFAULT 0,
    approvals_escalated INTEGER     NOT NULL DEFAULT 0,
    approvals_expired  INTEGER      NOT NULL DEFAULT 0,
    disruptions        INTEGER      NOT NULL DEFAULT 0,
    recoveries_failed  INTEGER      NOT NULL DEFAULT 0,
    cases_opened       INTEGER      NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, trip_id)
);
CREATE INDEX trip_fact_by_period ON trip_fact (tenant_id, booked_at);
CREATE INDEX trip_fact_by_cost_center ON trip_fact (tenant_id, cost_center_id);
