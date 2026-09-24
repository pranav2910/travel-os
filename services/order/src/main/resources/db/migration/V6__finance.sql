-- Phase 5 (ADR-0017): the authoritative money records of an order. Payments on the company's
-- instrument (authorize, capture, void, refund) with the provider's references and its FX
-- provenance; what the platform owes each supplier and how it is settled; travel credits suppliers
-- keep instead of money. Amounts are integer minor units with an explicit currency; the platform
-- never converts, it records what the provider did.

CREATE TABLE payment_instrument (
    instrument_id     VARCHAR(40)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    kind              VARCHAR(24)  NOT NULL,     -- CORPORATE_CARD | VIRTUAL_CARD | CENTRAL_BILL
    provider          VARCHAR(40)  NOT NULL,     -- sandbox-payments | stripe
    token             VARCHAR(200) NOT NULL,     -- the provider's opaque token; never a PAN
    label             VARCHAR(120),
    last4             VARCHAR(4),
    currency          CHAR(3)      NOT NULL,
    owner_employee_id VARCHAR(64),               -- null: the tenant's shared instrument
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by        VARCHAR(200) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT payment_instrument_token UNIQUE (tenant_id, provider, token)
);

CREATE TABLE payment (
    payment_id            VARCHAR(40)  PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    order_id              VARCHAR(40)  NOT NULL REFERENCES travel_order (order_id),
    trip_id               VARCHAR(64)  NOT NULL,
    instrument_id         VARCHAR(40)  NOT NULL,
    provider              VARCHAR(40)  NOT NULL,
    provider_ref          VARCHAR(160),
    status                VARCHAR(24)  NOT NULL,   -- AUTHORIZED | CAPTURED | VOIDED | PARTIALLY_REFUNDED | REFUNDED | DECLINED | FAILED
    currency              CHAR(3)      NOT NULL,
    authorized_minor      BIGINT       NOT NULL,
    captured_minor        BIGINT       NOT NULL DEFAULT 0,
    refunded_minor        BIGINT       NOT NULL DEFAULT 0,
    failure_code          VARCHAR(80),
    failure_message       VARCHAR(500),
    fx_settlement_currency CHAR(3),
    fx_settlement_minor   BIGINT,
    fx_rate               NUMERIC(18, 8),
    fx_source             VARCHAR(120),
    fx_quoted_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT payment_per_order UNIQUE (tenant_id, order_id)
);

CREATE TABLE payment_event (
    event_id        VARCHAR(40)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    payment_id      VARCHAR(40)  NOT NULL REFERENCES payment (payment_id),
    kind            VARCHAR(16)  NOT NULL,       -- AUTHORIZE | CAPTURE | VOID | REFUND | DECLINE
    idempotency_key VARCHAR(200) NOT NULL,
    currency        CHAR(3)      NOT NULL,
    amount_minor    BIGINT       NOT NULL,
    provider_ref    VARCHAR(160),
    outcome         VARCHAR(16)  NOT NULL,       -- SUCCEEDED | FAILED
    detail          VARCHAR(500),
    item_id         VARCHAR(40),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT payment_event_idempotency UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX payment_event_by_payment ON payment_event (payment_id, occurred_at);

CREATE TABLE supplier_payable (
    payable_id        VARCHAR(40)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    order_id          VARCHAR(40)  NOT NULL REFERENCES travel_order (order_id),
    item_id           VARCHAR(40)  NOT NULL,
    provider          VARCHAR(100) NOT NULL,
    external_ref      VARCHAR(160),
    currency          CHAR(3)      NOT NULL,
    amount_minor      BIGINT       NOT NULL,
    method            VARCHAR(24)  NOT NULL,     -- CARD_AT_SUPPLIER | BALANCE | INVOICE
    status            VARCHAR(16)  NOT NULL,     -- SETTLED (card) | DUE | INVOICED | PAID
    invoice_reference VARCHAR(120),
    settled_by        VARCHAR(200),
    settled_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT supplier_payable_item UNIQUE (tenant_id, item_id)
);
CREATE INDEX supplier_payable_open ON supplier_payable (tenant_id, provider, status);

CREATE TABLE travel_credit (
    credit_id           VARCHAR(40)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    traveler_id         VARCHAR(64)  NOT NULL,
    provider            VARCHAR(100) NOT NULL,
    reference           VARCHAR(120) NOT NULL,
    order_id            VARCHAR(40),
    item_id             VARCHAR(40),
    currency            CHAR(3)      NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL,   -- AVAILABLE | APPLIED | EXPIRED | VOID
    expires_at          TIMESTAMPTZ,
    applied_to_order_id VARCHAR(40),
    applied_by          VARCHAR(200),
    applied_at          TIMESTAMPTZ,
    note                VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT travel_credit_reference UNIQUE (tenant_id, provider, reference)
);
CREATE INDEX travel_credit_by_traveler ON travel_credit (tenant_id, traveler_id, status);

-- What a supplier kept as a credit when an item was released (beside the refund of V5).
ALTER TABLE order_item
    ADD COLUMN credit_currency  CHAR(3),
    ADD COLUMN credit_minor     BIGINT,
    ADD COLUMN credit_reference VARCHAR(120);

-- The SIMULATED payment provider's own ledger, so reconciliation has a provider side to read.
CREATE TABLE sandbox_payment_transaction (
    provider_ref  VARCHAR(160) PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    kind          VARCHAR(16)  NOT NULL,          -- AUTHORIZATION | CAPTURE | VOID | REFUND
    parent_ref    VARCHAR(160),
    currency      CHAR(3)      NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    settlement_currency CHAR(3) NOT NULL,
    settlement_minor    BIGINT  NOT NULL,
    order_id      VARCHAR(40),
    idempotency_key VARCHAR(200) NOT NULL,
    at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT sandbox_payment_idempotency UNIQUE (tenant_id, idempotency_key)
);
