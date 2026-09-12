-- Every domain event, once. This table is append-only and the database enforces it: no role,
-- not even the owner, can UPDATE or DELETE a row through the trigger. A compromised application
-- identity cannot rewrite history from here.
CREATE TABLE audit_event (
    event_id        VARCHAR(40)  PRIMARY KEY,
    event_type      VARCHAR(80)  NOT NULL,
    event_version   INTEGER      NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    correlation_id  VARCHAR(128) NOT NULL,
    causation_id    VARCHAR(128),
    producer        VARCHAR(64)  NOT NULL,
    topic           VARCHAR(64)  NOT NULL,
    partition       INTEGER      NOT NULL,
    kafka_offset    BIGINT       NOT NULL,
    data            JSONB        NOT NULL
);

CREATE INDEX audit_event_by_correlation ON audit_event (tenant_id, correlation_id, occurred_at, event_id);
CREATE INDEX audit_event_by_type ON audit_event (tenant_id, event_type, occurred_at DESC);

CREATE FUNCTION audit_event_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (attempted % on %)', TG_OP, OLD.event_id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_rewrite
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();

-- Messages that could not be decoded as an envelope. Never dropped, never blocking the partition.
CREATE TABLE audit_quarantine (
    id            BIGSERIAL    PRIMARY KEY,
    topic         VARCHAR(64)  NOT NULL,
    partition     INTEGER      NOT NULL,
    kafka_offset  BIGINT       NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL,
    reason        TEXT         NOT NULL,
    payload       TEXT         NOT NULL
);

-- Who a trip belongs to, from travel.trip.created, so a traveler can read their own trail.
CREATE TABLE trip_index (
    tenant_id    VARCHAR(64)  NOT NULL,
    trip_id      VARCHAR(40)  NOT NULL,
    traveler_id  VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, trip_id)
);
