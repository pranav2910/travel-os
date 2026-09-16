-- Enterprise Context owns exactly this schema. Nobody else connects to this database (ADR-0006).

-- The verified employee directory, mirrored from the tenant's HRIS through its connector. Identity
-- (who is this calendar attendee?), work location (where do their trips start?), manager (who may
-- act for them?) and active status come from here and from nowhere else.
CREATE TABLE employee (
    tenant_id            VARCHAR(64)  NOT NULL,
    employee_id          VARCHAR(64)  NOT NULL,
    email                VARCHAR(320) NOT NULL,
    display_name         VARCHAR(200) NOT NULL,
    work_location        CHAR(3)      NOT NULL,
    time_zone            VARCHAR(64)  NOT NULL,
    manager_employee_id  VARCHAR(64),
    active               BOOLEAN      NOT NULL,
    source_revision      BIGINT       NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, employee_id)
);
CREATE UNIQUE INDEX employee_by_email ON employee (tenant_id, lower(email));

-- One connector per (tenant, kind, provider). Configuration holds no secrets: providers' secrets are
-- named by reference and resolved from the environment.
CREATE TABLE connector (
    connector_id         VARCHAR(40)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    kind                 VARCHAR(20)  NOT NULL,
    provider             VARCHAR(60)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    config               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Durable checkpoint: the watermark every later run starts from. Advanced only inside the
    -- transaction that stored the page it summarizes.
    checkpoint           VARCHAR(256) NOT NULL DEFAULT '',
    running_run_id       VARCHAR(40),
    next_sync_at         TIMESTAMPTZ,
    last_run_id          VARCHAR(40),
    last_sync_at         TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    last_error_code      VARCHAR(80),
    last_error_message   VARCHAR(2000),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_by           VARCHAR(200) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT connector_unique UNIQUE (tenant_id, kind, provider)
);

CREATE TABLE sync_run (
    run_id               VARCHAR(40)  PRIMARY KEY,
    connector_id         VARCHAR(40)  NOT NULL REFERENCES connector (connector_id),
    tenant_id            VARCHAR(64)  NOT NULL,
    trigger              VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    since                VARCHAR(256) NOT NULL DEFAULT '',
    watermark            VARCHAR(256) NOT NULL DEFAULT '',
    pages                INTEGER      NOT NULL DEFAULT 0,
    items_seen           INTEGER      NOT NULL DEFAULT 0,
    items_changed        INTEGER      NOT NULL DEFAULT 0,
    candidates_touched   INTEGER      NOT NULL DEFAULT 0,
    last_cursor          VARCHAR(256) NOT NULL DEFAULT '',
    failure_code         VARCHAR(80),
    failure_message      VARCHAR(2000),
    requested_by         VARCHAR(200),
    notification_id      VARCHAR(256),
    started_at           TIMESTAMPTZ  NOT NULL,
    finished_at          TIMESTAMPTZ
);
CREATE INDEX sync_run_by_connector ON sync_run (connector_id, started_at DESC);

-- What a source told us, normalized, at its latest revision. Repeated delivery of the same
-- (source, revision) is a no-op; an older revision arriving later is stale and ignored.
CREATE TABLE source_item (
    tenant_id            VARCHAR(64)  NOT NULL,
    connector_id         VARCHAR(40)  NOT NULL REFERENCES connector (connector_id),
    source_id            VARCHAR(256) NOT NULL,
    kind                 VARCHAR(20)  NOT NULL,
    revision             BIGINT       NOT NULL,
    status               VARCHAR(20)  NOT NULL,        -- ACTIVE | CANCELLED | DELETED
    normalized           JSONB        NOT NULL,
    candidate_id         VARCHAR(40),
    first_seen_at        TIMESTAMPTZ  NOT NULL,
    observed_at          TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, connector_id, source_id)
);
CREATE INDEX source_item_by_candidate ON source_item (tenant_id, candidate_id);

-- Every revision ever observed: the evidence behind a candidate is never overwritten, only added to.
CREATE TABLE source_item_revision (
    id                   BIGSERIAL    PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    connector_id         VARCHAR(40)  NOT NULL,
    source_id            VARCHAR(256) NOT NULL,
    revision             BIGINT       NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    normalized           JSONB        NOT NULL,
    run_id               VARCHAR(40),
    observed_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT source_item_revision_unique UNIQUE (tenant_id, connector_id, source_id, revision)
);

CREATE TABLE demand_candidate (
    candidate_id         VARCHAR(40)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    traveler_id          VARCHAR(64)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    origin               CHAR(3),
    destination          CHAR(3),
    start_date           DATE,
    end_date             DATE,
    time_zone            VARCHAR(64),
    window_start         TIMESTAMPTZ,
    window_end           TIMESTAMPTZ,
    -- The commitment's own words (a calendar title, an account name). Data, never an instruction.
    purpose              VARCHAR(500),
    missing              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    review_reasons       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    sources              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    rules_version        VARCHAR(20)  NOT NULL,
    explanation          VARCHAR(2000) NOT NULL,
    trip_id              VARCHAR(64),
    conversion_key       VARCHAR(200),
    -- Keys the candidate to its primary commitment so a redelivery updates it, never duplicates it.
    primary_key          VARCHAR(400) NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT demand_primary UNIQUE (tenant_id, primary_key)
);
CREATE INDEX demand_by_traveler ON demand_candidate (tenant_id, traveler_id, updated_at DESC);
CREATE INDEX demand_by_status ON demand_candidate (tenant_id, status, updated_at DESC);

CREATE TABLE demand_transition (
    id                   BIGSERIAL    PRIMARY KEY,
    candidate_id         VARCHAR(40)  NOT NULL REFERENCES demand_candidate (candidate_id),
    tenant_id            VARCHAR(64)  NOT NULL,
    from_status          VARCHAR(20),
    to_status            VARCHAR(20)  NOT NULL,
    reason               VARCHAR(80)  NOT NULL,
    detail               VARCHAR(2000),
    actor                VARCHAR(200) NOT NULL,
    occurred_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX demand_transition_by_candidate ON demand_transition (candidate_id, id);

-- Provider notifications, deduplicated by the provider's own event id.
CREATE TABLE notification_receipt (
    provider             VARCHAR(60)  NOT NULL,
    notification_id      VARCHAR(256) NOT NULL,
    tenant_id            VARCHAR(64)  NOT NULL,
    connector_id         VARCHAR(40),
    run_id               VARCHAR(40),
    received_at          TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (provider, notification_id)
);

-- SIMULATED sources: what the sandbox calendar / CRM / HRIS / expense "systems" currently hold.
-- Items are appended with a sequence so a source page is "everything after the watermark".
CREATE TABLE sandbox_item (
    seq                  BIGSERIAL    PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    connector_id         VARCHAR(40)  NOT NULL REFERENCES connector (connector_id),
    source_id            VARCHAR(256) NOT NULL,
    revision             BIGINT       NOT NULL,
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
    payload              JSONB        NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX sandbox_item_by_connector ON sandbox_item (connector_id, seq);

-- SIMULATED faults, per connector: how many fetches still fail, which page is rate limited once.
CREATE TABLE sandbox_fault (
    connector_id         VARCHAR(40)  PRIMARY KEY REFERENCES connector (connector_id),
    unavailable_calls    INTEGER      NOT NULL DEFAULT 0,
    rate_limit_page      INTEGER      NOT NULL DEFAULT -1,
    rate_limit_hits      INTEGER      NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ  NOT NULL
);

-- Transactional outbox (libs/spring-outbox/src/main/resources/db/outbox-table.sql, verbatim).
-- Reference DDL for the outbox table. Each service owns its schema (ADR-0006), so copy this into
-- the service's first Flyway migration verbatim. The library's integration test runs this file.
CREATE TABLE outbox (
    event_id       VARCHAR(40)  PRIMARY KEY,
    topic          VARCHAR(64)  NOT NULL,
    partition_key  VARCHAR(128) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    -- W3C traceparent of the transaction that appended the row; restored when relayed.
    trace_parent   VARCHAR(80)
);
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
