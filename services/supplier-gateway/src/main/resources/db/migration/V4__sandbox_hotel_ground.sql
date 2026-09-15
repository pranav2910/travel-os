-- Slice 3: the sandbox hotel and ground suppliers keep their own booking ledgers here, like the
-- sandbox airline does in sandbox_order, so supplier-side idempotency (same key => same booking),
-- status lookup after a lost answer, cancellation terms and changes are real and survive restarts.
CREATE TABLE sandbox_booking (
    booking_id         VARCHAR(40)  PRIMARY KEY,           -- SBH-<ulid> (hotel) | SBG-<ulid> (ground)
    kind               VARCHAR(10)  NOT NULL,              -- HOTEL | GROUND
    tenant_id          VARCHAR(64)  NOT NULL,
    idempotency_key    VARCHAR(200) NOT NULL,
    provider_offer_id  VARCHAR(400) NOT NULL,
    confirmation       CHAR(6)      NOT NULL,
    status             VARCHAR(20)  NOT NULL,              -- CONFIRMED | CHANGED | CANCELLED
    currency           CHAR(3)      NOT NULL,
    charged_minor      BIGINT       NOT NULL,
    -- what the supplier keeps on cancellation, per the terms sold with the offer
    penalty_minor      BIGINT       NOT NULL,
    guests             JSONB        NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT sandbox_booking_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE sandbox_booking_change (
    change_id          VARCHAR(40)  PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    idempotency_key    VARCHAR(200) NOT NULL,
    booking_id         VARCHAR(40)  NOT NULL REFERENCES sandbox_booking (booking_id),
    previous_offer_id  VARCHAR(400) NOT NULL,
    new_offer_id       VARCHAR(400) NOT NULL,
    incremental_minor  BIGINT       NOT NULL,
    charged_minor      BIGINT       NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT sandbox_booking_change_idempotency UNIQUE (tenant_id, idempotency_key)
);
