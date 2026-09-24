-- Phase 4 (ADR-0016): every supplier mutation is written down BEFORE the supplier is called, so a
-- lost answer is a known unknown, never a silent second booking. One row per
-- (tenant, provider, command, idempotency key); the response is kept so a retry with the same key
-- answers from the ledger without touching the supplier again.
CREATE TABLE supplier_mutation_attempt (
    attempt_id       VARCHAR(40)  PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    provider         VARCHAR(100) NOT NULL,
    command          VARCHAR(16)  NOT NULL,      -- CREATE | CHANGE | CANCEL
    idempotency_key  VARCHAR(160) NOT NULL,
    request_digest   VARCHAR(64)  NOT NULL,      -- sha256 of the canonical request: same key, same request
    status           VARCHAR(16)  NOT NULL,      -- STARTED | SUCCEEDED | FAILED | UNKNOWN
    external_ref     VARCHAR(160),
    response         BYTEA,                      -- the protobuf response, once known
    failure_code     VARCHAR(80),
    failure_message  VARCHAR(1000),
    calls            INTEGER      NOT NULL DEFAULT 0,
    correlation_id   VARCHAR(128),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT supplier_mutation_attempt_key UNIQUE (tenant_id, provider, command, idempotency_key)
);
CREATE INDEX supplier_mutation_attempt_open ON supplier_mutation_attempt (status, updated_at) WHERE status IN ('STARTED', 'UNKNOWN');
