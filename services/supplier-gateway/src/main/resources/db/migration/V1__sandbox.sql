-- The sandbox supplier's own order ledger. A real supplier keeps this on their side; the sandbox
-- keeps it here so supplier-side idempotency (same idempotency key => same order) is real and
-- survives restarts, and so cancellations and changes have something to act on.
CREATE TABLE sandbox_order (
    external_order_id  VARCHAR(40)  PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    idempotency_key    VARCHAR(200) NOT NULL,
    provider_offer_id  VARCHAR(300) NOT NULL,
    record_locator     CHAR(6)      NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    currency           CHAR(3)      NOT NULL,
    charged_minor      BIGINT       NOT NULL,
    passengers         JSONB        NOT NULL,
    ticket_numbers     JSONB        NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT sandbox_order_idempotency UNIQUE (tenant_id, idempotency_key)
);
