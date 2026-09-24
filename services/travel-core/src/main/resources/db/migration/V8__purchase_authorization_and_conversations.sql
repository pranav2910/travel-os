-- Phase 3 (ADR-0015): planning is separate from purchase authorization.
--
-- A trip is QUOTED once it is planned and priced. Nothing is reserved until a purchase
-- authorization exists: a person's confirmation (basis HUMAN), or policy-granted autonomy recorded
-- as one (basis POLICY_AUTONOMY, auditable through the policy decision). An authorization is bound
-- to one plan (bundle), one total in one currency, the fare conditions, the traveler and their
-- profile version, and a quote expiry; a changed price or a different plan supersedes it. The move
-- to BOOKING consumes exactly one ACTIVE authorization; a second attempt finds it CONSUMED.
ALTER TABLE trip
    ADD COLUMN purchase_mode    VARCHAR(16)  NOT NULL DEFAULT 'POLICY',   -- POLICY | CONFIRM
    ADD COLUMN quote_expires_at TIMESTAMPTZ,
    ADD COLUMN alternatives     JSONB,                                     -- ranked TripAlternative[]
    ADD COLUMN search_preferences JSONB;                                   -- SearchPreferences of the intent

CREATE TABLE purchase_authorization (
    authorization_id  VARCHAR(40)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    trip_id           VARCHAR(40)  NOT NULL REFERENCES trip (trip_id),
    status            VARCHAR(16)  NOT NULL,     -- ACTIVE | CONSUMED | SUPERSEDED | REVOKED | EXPIRED
    basis             VARCHAR(24)  NOT NULL,     -- HUMAN | POLICY_AUTONOMY
    authorized_by     VARCHAR(200) NOT NULL,     -- principal id
    bundle_id         VARCHAR(64)  NOT NULL,
    total_currency    CHAR(3)      NOT NULL,
    total_minor       BIGINT       NOT NULL,
    conditions        TEXT,
    traveler_id       VARCHAR(64)  NOT NULL,
    profile_version   BIGINT       NOT NULL DEFAULT 0,
    trip_version      BIGINT       NOT NULL,
    policy_decision_id VARCHAR(64),
    idempotency_key   VARCHAR(128),
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    consumed_order_attempt VARCHAR(128),
    superseded_by     VARCHAR(40),
    superseded_reason VARCHAR(200)
);
CREATE INDEX purchase_authorization_by_trip ON purchase_authorization (tenant_id, trip_id, created_at DESC);
CREATE UNIQUE INDEX purchase_authorization_one_active ON purchase_authorization (tenant_id, trip_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX purchase_authorization_idempotency ON purchase_authorization (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- A conversation is the persisted, multi-turn way of asking for travel through the same APIs:
-- every turn that describes a trip becomes a regular trip (source CONVERSATION); the platform's
-- answers (a clarifying question, a quote, a failure) are messages too.
CREATE TABLE conversation (
    conversation_id VARCHAR(40)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    traveler_id     VARCHAR(64)  NOT NULL,
    created_by      VARCHAR(200) NOT NULL,
    status          VARCHAR(24)  NOT NULL,      -- OPEN | AWAITING_USER | PLANNED | CLOSED
    current_trip_id VARCHAR(40),
    idempotency_key VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT conversation_idempotency UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX conversation_by_traveler ON conversation (tenant_id, traveler_id, created_at DESC);

CREATE TABLE conversation_message (
    message_id      VARCHAR(40)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(40)  NOT NULL REFERENCES conversation (conversation_id),
    seq             INTEGER      NOT NULL,
    role            VARCHAR(16)  NOT NULL,      -- USER | ASSISTANT
    text            TEXT         NOT NULL,
    trip_id         VARCHAR(40),
    kind            VARCHAR(32),                -- REQUEST | CLARIFICATION | QUESTION | QUOTE | STATUS | FAILURE
    idempotency_key VARCHAR(128),               -- user turns: the key the turn was sent with
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT conversation_message_seq UNIQUE (conversation_id, seq)
);
