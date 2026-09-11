-- Order owns exactly this schema. Nobody else connects to this database (ADR-0006).

CREATE TABLE travel_order (
    order_id             VARCHAR(40)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    trip_id              VARCHAR(64)  NOT NULL,
    traveler_id          VARCHAR(64)  NOT NULL,
    bundle_id            VARCHAR(40)  NOT NULL,
    supplier             VARCHAR(100) NOT NULL,
    external_order_id    VARCHAR(100),
    status               VARCHAR(40)  NOT NULL,
    currency             CHAR(3)      NOT NULL,
    total_minor          BIGINT       NOT NULL,

    -- N retries of the same command => this one row (ADR-0005).
    idempotency_key      VARCHAR(200) NOT NULL,

    -- Evidence chain: the decisions that authorized this order.
    policy_decision_id   VARCHAR(40),
    optimization_run_id  VARCHAR(40),
    approval_id          VARCHAR(40),

    failure_code         VARCHAR(80),
    failure_message      VARCHAR(1000),
    compensated          BOOLEAN      NOT NULL DEFAULT FALSE,

    created_by           VARCHAR(128) NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT travel_order_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX travel_order_by_trip ON travel_order (tenant_id, trip_id);
CREATE INDEX travel_order_by_traveler ON travel_order (tenant_id, traveler_id, created_at DESC);

-- One row per component (Slice 1: the air offer). Each item is booked with its own supplier-side
-- idempotency key, so a resumed saga never double-books a component.
CREATE TABLE order_item (
    item_id            VARCHAR(40)  PRIMARY KEY,
    order_id           VARCHAR(40)  NOT NULL REFERENCES travel_order (order_id),
    tenant_id          VARCHAR(64)  NOT NULL,
    position           INTEGER      NOT NULL,
    offer_type         VARCHAR(20)  NOT NULL,
    provider           VARCHAR(100) NOT NULL,
    provider_offer_id  VARCHAR(300) NOT NULL,
    offer              JSONB        NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    external_ref       VARCHAR(100),
    record_locator     VARCHAR(20),
    currency           CHAR(3)      NOT NULL,
    total_minor        BIGINT       NOT NULL,
    failure_code       VARCHAR(80),
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX order_item_by_order ON order_item (order_id, position);

CREATE TABLE order_status_history (
    id           BIGSERIAL    PRIMARY KEY,
    order_id     VARCHAR(40)  NOT NULL REFERENCES travel_order (order_id),
    tenant_id    VARCHAR(64)  NOT NULL,
    from_status  VARCHAR(40),
    to_status    VARCHAR(40)  NOT NULL,
    reason       VARCHAR(500),
    occurred_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX order_status_history_by_order ON order_status_history (order_id, id);

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
