-- Travel Core owns exactly this schema. Nobody else connects to this database (ADR-0006).

CREATE TABLE trip (
    trip_id             VARCHAR(40)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    traveler_id         VARCHAR(64)  NOT NULL,
    status              VARCHAR(40)  NOT NULL,
    source              VARCHAR(30)  NOT NULL,

    -- What the traveler said, verbatim, for intent extraction and for the audit trail.
    request_text        TEXT,

    -- The frozen intent. NULL until understood (structured request, or LLM extraction later).
    origin              CHAR(3),
    destination         CHAR(3),
    earliest_departure  TIMESTAMPTZ,
    arrival_deadline    TIMESTAMPTZ,
    return_after        TIMESTAMPTZ,
    latest_return       TIMESTAMPTZ,
    purpose             TEXT,
    hotel_required      BOOLEAN      NOT NULL DEFAULT FALSE,
    travelers           INTEGER      NOT NULL DEFAULT 1,

    -- Evidence chain, filled in as planning proceeds.
    selected_bundle_id  VARCHAR(40),
    optimization_run_id VARCHAR(40),
    policy_decision_id  VARCHAR(40),
    approval_id         VARCHAR(40),
    order_id            VARCHAR(40),

    created_by          VARCHAR(128) NOT NULL,
    -- Client-supplied Idempotency-Key, scoped by tenant: a retry returns the same trip.
    idempotency_key     VARCHAR(160) NOT NULL,
    -- SHA-256 of the canonical request: the same key with a different body is a client bug (422).
    request_fingerprint CHAR(64)     NOT NULL,

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT trip_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX trip_by_traveler ON trip (tenant_id, traveler_id, created_at DESC);
CREATE INDEX trip_by_status   ON trip (tenant_id, status);

-- Every status transition, forever. The explainability API reads this.
CREATE TABLE trip_status_history (
    id            BIGSERIAL    PRIMARY KEY,
    trip_id       VARCHAR(40)  NOT NULL REFERENCES trip (trip_id),
    tenant_id     VARCHAR(64)  NOT NULL,
    from_status   VARCHAR(40),
    to_status     VARCHAR(40)  NOT NULL,
    reason        VARCHAR(500),
    actor         VARCHAR(128) NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX trip_status_history_by_trip ON trip_status_history (trip_id, id);

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
