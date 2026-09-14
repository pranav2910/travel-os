-- Slice 2: the sandbox airline can cancel flights and reissue tickets; the gateway remembers what it
-- booked (so a supplier notice can be tied back to a trip) and never processes one notice twice.

-- What the gateway booked, by supplier reference: the door remembers who walked through it.
CREATE TABLE supplier_order_ref (
    provider           VARCHAR(100) NOT NULL,
    external_order_id  VARCHAR(100) NOT NULL,
    tenant_id          VARCHAR(64)  NOT NULL,
    correlation_id     VARCHAR(128) NOT NULL,
    record_locator     VARCHAR(20),
    booked_at          TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (provider, external_order_id)
);

-- Every supplier notice, once. A redelivered webhook maps to the same disruption id.
CREATE TABLE supplier_notification (
    provider           VARCHAR(100) NOT NULL,
    supplier_event_id  VARCHAR(128) NOT NULL,
    disruption_id      VARCHAR(40)  NOT NULL,
    tenant_id          VARCHAR(64)  NOT NULL,
    event_id           VARCHAR(40)  NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL,
    payload            JSONB        NOT NULL,
    PRIMARY KEY (provider, supplier_event_id)
);

-- Sandbox: a cancelled flight and the deterministic reaccommodation inventory that replaces it,
-- keyed by tenant + route + dates (what a search asks for).
CREATE TABLE sandbox_reaccommodation (
    tenant_id          VARCHAR(64)  NOT NULL,
    origin             CHAR(3)      NOT NULL,
    destination        CHAR(3)      NOT NULL,
    outbound_date      DATE         NOT NULL,
    inbound_date       DATE,
    cabin              VARCHAR(20)  NOT NULL,
    cancelled_slot     INTEGER      NOT NULL,
    cancelled_flight   VARCHAR(10)  NOT NULL,
    replacement_slot   INTEGER      NOT NULL,
    original_fare_minor BIGINT      NOT NULL,
    delta_minor        BIGINT       NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, origin, destination, outbound_date, cabin)
);

-- Sandbox: one row per reissue, idempotent by the caller's key (ADR-0005 on the supplier side).
CREATE TABLE sandbox_order_change (
    change_id            VARCHAR(40)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    idempotency_key      VARCHAR(200) NOT NULL,
    external_order_id    VARCHAR(40)  NOT NULL REFERENCES sandbox_order (external_order_id),
    previous_offer_id    VARCHAR(300) NOT NULL,
    new_offer_id         VARCHAR(300) NOT NULL,
    incremental_minor    BIGINT       NOT NULL,
    charged_minor        BIGINT       NOT NULL,
    ticket_numbers       JSONB        NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT sandbox_order_change_idempotency UNIQUE (tenant_id, idempotency_key)
);

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
