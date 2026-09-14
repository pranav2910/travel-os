-- Slice 2: an order's itinerary can be replaced (disruption recovery). One row per logical change,
-- idempotent by the caller's key; the order's current items are whatever the last APPLIED change left.
CREATE TABLE order_change (
    change_id              VARCHAR(40)  PRIMARY KEY,
    order_id               VARCHAR(40)  NOT NULL REFERENCES travel_order (order_id),
    tenant_id              VARCHAR(64)  NOT NULL,
    disruption_id          VARCHAR(40),
    idempotency_key        VARCHAR(200) NOT NULL,
    status                 VARCHAR(20)  NOT NULL,          -- PENDING | APPLIED | FAILED
    previous_status        VARCHAR(40)  NOT NULL,          -- the order status to return to if the change fails
    previous_bundle_id     VARCHAR(40)  NOT NULL,
    replacement_bundle_id  VARCHAR(40)  NOT NULL,
    replacement_offer      JSONB        NOT NULL,          -- the offer as proposed, exactly what will be booked
    currency               CHAR(3)      NOT NULL,
    incremental_minor      BIGINT,
    policy_decision_id     VARCHAR(40),
    optimization_run_id    VARCHAR(40),
    approval_id            VARCHAR(40),
    external_order_id      VARCHAR(100),
    record_locator         VARCHAR(20),
    failure_code           VARCHAR(80),
    failure_message        VARCHAR(1000),
    requested_by           VARCHAR(128) NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT order_change_idempotency UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX order_change_by_order ON order_change (order_id, created_at);

-- Impacted-trip detection: which order did a supplier reference belong to?
CREATE INDEX order_item_by_external_ref ON order_item (tenant_id, provider, external_ref);
CREATE INDEX travel_order_by_external ON travel_order (tenant_id, supplier, external_order_id);

-- An item that was replaced keeps its row, marked CHANGED, so the history stays legible.
