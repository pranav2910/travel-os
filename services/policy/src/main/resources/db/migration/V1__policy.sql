-- Policy owns exactly this schema. Nobody else connects to this database (ADR-0006).

-- Every published version of every policy, forever. Versions are immutable; a change is a new row.
CREATE TABLE policy_version (
    tenant_id      VARCHAR(64)  NOT NULL,
    policy_id      VARCHAR(64)  NOT NULL,
    version        INTEGER      NOT NULL,
    name           VARCHAR(200) NOT NULL,
    document       JSONB        NOT NULL,
    document_hash  CHAR(64)     NOT NULL,
    published_by   VARCHAR(128) NOT NULL,
    published_at   TIMESTAMPTZ  NOT NULL,
    note           VARCHAR(500),
    PRIMARY KEY (tenant_id, policy_id, version)
);

-- Which policy applies to a tenant. Slice 1: one default per tenant; per-cost-center and
-- per-traveler-group assignment arrive with the Enterprise Context service.
CREATE TABLE policy_assignment (
    tenant_id   VARCHAR(64)  PRIMARY KEY,
    policy_id   VARCHAR(64)  NOT NULL,
    updated_by  VARCHAR(128) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

-- Immutable evidence. Every evaluation writes one row per candidate/action. The explainability
-- API and the decision ledger read from here; nothing updates or deletes these rows.
CREATE TABLE policy_decision (
    decision_id       VARCHAR(40)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    evaluation_id     VARCHAR(40)  NOT NULL,
    trip_id           VARCHAR(64)  NOT NULL,
    traveler_id       VARCHAR(64)  NOT NULL,
    bundle_id         VARCHAR(40),
    action            VARCHAR(40),
    policy_id         VARCHAR(64)  NOT NULL,
    policy_version    INTEGER      NOT NULL,
    outcome           VARCHAR(40)  NOT NULL,
    requires_approval BOOLEAN      NOT NULL,
    evaluated_for     VARCHAR(128) NOT NULL,
    decision          JSONB        NOT NULL,
    evaluated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX policy_decision_by_trip ON policy_decision (tenant_id, trip_id, evaluated_at);
CREATE INDEX policy_decision_by_evaluation ON policy_decision (tenant_id, evaluation_id);

-- Transactional outbox (libs/spring-outbox/src/main/resources/db/outbox-table.sql, verbatim).
CREATE TABLE outbox (
    event_id       VARCHAR(40)  PRIMARY KEY,
    topic          VARCHAR(64)  NOT NULL,
    partition_key  VARCHAR(128) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
