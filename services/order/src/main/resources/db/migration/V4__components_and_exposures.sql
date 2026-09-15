-- Slice 3: multi-component itineraries and money at risk after a failed compensation.

-- Which itinerary component an item fulfils (cmp_<ulid> from the frozen intent); NULL for the
-- Slice 1/2 single-component orders.
ALTER TABLE order_item ADD COLUMN component_id VARCHAR(40);
CREATE INDEX order_item_by_component ON order_item (order_id, component_id);

-- A confirmed component that could not be released after a later component failed. The amount is
-- what the company may have lost; a person resolves it (cancels by phone, accepts the loss) and
-- says how. Nothing here is ever deleted.
CREATE TABLE order_exposure (
    exposure_id    VARCHAR(40)  PRIMARY KEY,
    order_id       VARCHAR(40)  NOT NULL REFERENCES travel_order (order_id),
    tenant_id      VARCHAR(64)  NOT NULL,
    item_id        VARCHAR(40)  NOT NULL REFERENCES order_item (item_id),
    component_id   VARCHAR(40),
    provider       VARCHAR(100) NOT NULL,
    external_ref   VARCHAR(100) NOT NULL,
    currency       CHAR(3)      NOT NULL,
    amount_minor   BIGINT       NOT NULL,
    reason         VARCHAR(40)  NOT NULL,        -- COMPENSATION_FAILED | NON_REFUNDABLE
    detail         VARCHAR(2000),
    status         VARCHAR(20)  NOT NULL,        -- OPEN | RESOLVED
    resolved_by    VARCHAR(128),
    resolution     VARCHAR(2000),
    resolution_idempotency_key VARCHAR(160),
    created_at     TIMESTAMPTZ  NOT NULL,
    resolved_at    TIMESTAMPTZ
);
CREATE INDEX order_exposure_by_order ON order_exposure (order_id, created_at);
CREATE INDEX order_exposure_open ON order_exposure (tenant_id, status) WHERE status = 'OPEN';
