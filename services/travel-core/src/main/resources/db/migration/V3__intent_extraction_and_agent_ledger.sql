-- Narration of the plan, produced from the evidence at planning time (empty when the LLM gateway
-- was unavailable: narration degrades, transactions do not).
ALTER TABLE trip ADD COLUMN explanation TEXT;

-- The agent-decision ledger: every conclusion a model influenced, with the call that produced it.
-- "Which model, which prompt version, what confidence, what did it cost" is answerable from here
-- alone (design package section 30). model_call_id is unique so a retried activity cannot record
-- the same call twice.
CREATE TABLE agent_decision (
    decision_id         VARCHAR(40)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    trip_id             VARCHAR(40)  NOT NULL REFERENCES trip (trip_id),
    agent               VARCHAR(128) NOT NULL,
    decision_type       VARCHAR(40)  NOT NULL,
    result              VARCHAR(40)  NOT NULL,
    confidence          DOUBLE PRECISION,
    assumptions         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    detail              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    model_call_id       VARCHAR(40)  UNIQUE,
    provider            VARCHAR(40),
    model               VARCHAR(80),
    prompt_id           VARCHAR(80),
    prompt_version      INTEGER,
    provider_request_id VARCHAR(120),
    input_tokens        BIGINT       NOT NULL DEFAULT 0,
    output_tokens       BIGINT       NOT NULL DEFAULT 0,
    cache_read_tokens   BIGINT       NOT NULL DEFAULT 0,
    latency_ms          BIGINT       NOT NULL DEFAULT 0,
    cost_micros         BIGINT       NOT NULL DEFAULT 0,
    occurred_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX agent_decision_by_trip ON agent_decision (tenant_id, trip_id, occurred_at);
