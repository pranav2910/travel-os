-- Disruption owns exactly this schema. Nobody else connects to this database (ADR-0006).

CREATE TABLE disruption (
    disruption_id       VARCHAR(40)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    trip_id             VARCHAR(64),
    order_id            VARCHAR(40),
    traveler_id         VARCHAR(64),
    segment_id          VARCHAR(80),
    type                VARCHAR(30)  NOT NULL,
    supplier            VARCHAR(100) NOT NULL,
    supplier_event_id   VARCHAR(128) NOT NULL,
    external_order_id   VARCHAR(100) NOT NULL,
    record_locator      VARCHAR(20),
    detected_at         TIMESTAMPTZ  NOT NULL,
    status              VARCHAR(40)  NOT NULL,
    severity            VARCHAR(20)  NOT NULL,
    raw_reference       VARCHAR(512),
    -- The supplier's own words. Data, never an instruction.
    reason              VARCHAR(2000),
    affected            JSONB        NOT NULL,
    -- What the recovery has established so far (RecoveryState as protobuf JSON).
    recovery            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    failure_stage       VARCHAR(40),
    failure_code        VARCHAR(80),
    -- The event that created it: a redelivered detected event maps here, never to a second row.
    source_event_id     VARCHAR(40)  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT disruption_supplier_event UNIQUE (tenant_id, supplier, supplier_event_id)
);

CREATE INDEX disruption_by_trip ON disruption (tenant_id, trip_id, detected_at DESC);
CREATE INDEX disruption_by_order ON disruption (tenant_id, order_id);

CREATE TABLE disruption_status_history (
    id             BIGSERIAL    PRIMARY KEY,
    disruption_id  VARCHAR(40)  NOT NULL REFERENCES disruption (disruption_id),
    tenant_id      VARCHAR(64)  NOT NULL,
    from_status    VARCHAR(40),
    to_status      VARCHAR(40)  NOT NULL,
    reason         VARCHAR(500),
    occurred_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX disruption_status_history_by_disruption ON disruption_status_history (disruption_id, id);

-- Kafka is at-least-once; this makes processing exactly-once per event id.
CREATE TABLE processed_event (
    event_id      VARCHAR(40)  PRIMARY KEY,
    event_type    VARCHAR(80)  NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL
);

-- The recovery decision record: written once, never updated, never deleted (the trigger below
-- makes the database itself refuse). One per disruption.
CREATE TABLE recovery_decision (
    decision_id    VARCHAR(40)  PRIMARY KEY,
    disruption_id  VARCHAR(40)  NOT NULL UNIQUE REFERENCES disruption (disruption_id),
    tenant_id      VARCHAR(64)  NOT NULL,
    record         JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE recovery_outcome (
    outcome_id     VARCHAR(40)  PRIMARY KEY,
    disruption_id  VARCHAR(40)  NOT NULL UNIQUE REFERENCES disruption (disruption_id),
    tenant_id      VARCHAR(64)  NOT NULL,
    status         VARCHAR(40)  NOT NULL,
    record         JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE OR REPLACE FUNCTION immutable_record() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'recovery records are immutable (% on %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recovery_decision_immutable
    BEFORE UPDATE OR DELETE ON recovery_decision
    FOR EACH ROW EXECUTE FUNCTION immutable_record();
CREATE TRIGGER recovery_outcome_immutable
    BEFORE UPDATE OR DELETE ON recovery_outcome
    FOR EACH ROW EXECUTE FUNCTION immutable_record();

-- A person's say on a recovery policy would not let the agent do alone.
CREATE TABLE recovery_approval (
    approval_id              VARCHAR(40)  PRIMARY KEY,
    disruption_id            VARCHAR(40)  NOT NULL REFERENCES disruption (disruption_id),
    tenant_id                VARCHAR(64)  NOT NULL,
    trip_id                  VARCHAR(64)  NOT NULL,
    traveler_id              VARCHAR(64)  NOT NULL,
    required_role            VARCHAR(40)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL,   -- PENDING | APPROVED | REJECTED
    policy_decision_id       VARCHAR(40),
    incremental_currency     CHAR(3),
    incremental_minor        BIGINT,
    requested_at             TIMESTAMPTZ  NOT NULL,
    decided_by               VARCHAR(128),
    decided_at               TIMESTAMPTZ,
    comment                  VARCHAR(2000),
    decision_idempotency_key VARCHAR(200)
);
CREATE INDEX recovery_approval_by_disruption ON recovery_approval (disruption_id, requested_at DESC);

-- Transactional outbox (libs/spring-outbox/src/main/resources/db/outbox-table.sql, verbatim).
CREATE TABLE outbox (
    event_id       VARCHAR(40)  PRIMARY KEY,
    topic          VARCHAR(64)  NOT NULL,
    partition_key  VARCHAR(128) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    trace_parent   VARCHAR(80)
);
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
