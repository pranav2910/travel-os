-- Assistance owns exactly this schema. Nobody else connects to this database (ADR-0006).

-- Kafka is at-least-once; this makes case handling exactly-once per event id.
CREATE TABLE processed_event (
    event_id             VARCHAR(40)  PRIMARY KEY,
    event_type           VARCHAR(80)  NOT NULL,
    processed_at         TIMESTAMPTZ  NOT NULL
);

-- What the platform said about a trip, so a case can name the traveler it concerns and a traveler
-- can see the cases on their own trips. Never authoritative for anything else.
CREATE TABLE trip_index (
    tenant_id            VARCHAR(64)  NOT NULL,
    trip_id              VARCHAR(64)  NOT NULL,
    traveler_id          VARCHAR(64),
    order_id             VARCHAR(64),
    updated_at           TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, trip_id)
);

-- A case: something a person must finish. Opened from an event or by a person, owned by one
-- principal at a time, due by its priority's service level, escalated when overdue, resolved
-- when the fact is settled (by the platform's own event or by the person), then closed.
CREATE TABLE assistance_case (
    case_id              VARCHAR(40)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    kind                 VARCHAR(40)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    priority             VARCHAR(10)  NOT NULL,
    queue                VARCHAR(40)  NOT NULL,
    title                VARCHAR(300) NOT NULL,
    summary              TEXT,
    trip_id              VARCHAR(64),
    order_id             VARCHAR(64),
    traveler_id          VARCHAR(64),
    disruption_id        VARCHAR(64),
    exposure_id          VARCHAR(64),
    component_id         VARCHAR(64),
    -- One open case per fact: the same event told twice, or two events about one exposure, land
    -- on one case.
    dedupe_key           VARCHAR(300) NOT NULL,
    owner                VARCHAR(200),
    next_action          VARCHAR(500) NOT NULL,
    next_action_role     VARCHAR(40)  NOT NULL,
    escalation_level     INTEGER      NOT NULL DEFAULT 0,
    due_at               TIMESTAMPTZ  NOT NULL,
    opened_at            TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    resolved_at          TIMESTAMPTZ,
    closed_at            TIMESTAMPTZ,
    resolution           TEXT,
    source_event_id      VARCHAR(40),
    source_event_type    VARCHAR(80),
    version              BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX assistance_case_open_by_key
    ON assistance_case (tenant_id, dedupe_key) WHERE status NOT IN ('RESOLVED', 'CLOSED');
CREATE INDEX assistance_case_by_queue ON assistance_case (tenant_id, status, queue, due_at);
CREATE INDEX assistance_case_by_trip ON assistance_case (tenant_id, trip_id);
CREATE INDEX assistance_case_by_traveler ON assistance_case (tenant_id, traveler_id);
CREATE INDEX assistance_case_due ON assistance_case (due_at) WHERE status NOT IN ('RESOLVED', 'CLOSED');

-- The case's history: every note, assignment, escalation and status change, by whom, when.
CREATE TABLE case_event (
    case_event_id        VARCHAR(40)  PRIMARY KEY,
    case_id              VARCHAR(40)  NOT NULL REFERENCES assistance_case (case_id),
    tenant_id            VARCHAR(64)  NOT NULL,
    kind                 VARCHAR(30)  NOT NULL,
    actor                VARCHAR(200) NOT NULL,
    message              TEXT         NOT NULL,
    data                 JSONB,
    occurred_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX case_event_by_case ON case_event (case_id, occurred_at);

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
