-- Learning owns exactly this schema. Nobody else connects to this database (ADR-0006).

-- Per-tenant learning configuration: the mode (OFF | SHADOW | ACTIVE), the active profile and the
-- profile it replaced (the rollback target). Changed under a row lock, every change audited.
CREATE TABLE tenant_config (
    tenant_id            VARCHAR(64)  PRIMARY KEY,
    mode                 VARCHAR(10)  NOT NULL,
    active_profile_id    VARCHAR(40),
    previous_profile_id  VARCHAR(40),
    version              BIGINT       NOT NULL DEFAULT 0,
    updated_by           VARCHAR(200) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);

-- Kafka is at-least-once; this makes processing exactly-once per event id.
CREATE TABLE processed_event (
    event_id             VARCHAR(40)  PRIMARY KEY,
    event_type           VARCHAR(80)  NOT NULL,
    processed_at         TIMESTAMPTZ  NOT NULL
);

-- What the platform said about a trip, kept so feedback can be authorized against the actual
-- traveler and trip and so completion can be attributed to the order's items. Never authoritative
-- for anything else.
CREATE TABLE trip_index (
    tenant_id            VARCHAR(64)  NOT NULL,
    trip_id              VARCHAR(64)  NOT NULL,
    traveler_id          VARCHAR(64),
    order_id             VARCHAR(64),
    status               VARCHAR(20)  NOT NULL,
    booked_at            TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, trip_id)
);

CREATE TABLE order_item (
    tenant_id            VARCHAR(64)  NOT NULL,
    order_id             VARCHAR(64)  NOT NULL,
    item_id              VARCHAR(64)  NOT NULL,
    trip_id              VARCHAR(64)  NOT NULL,
    component_id         VARCHAR(64),
    item_type            VARCHAR(10)  NOT NULL,
    provider             VARCHAR(60),
    supplier_key         VARCHAR(200),
    status               VARCHAR(20)  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, order_id, item_id)
);
CREATE INDEX order_item_by_trip ON order_item (tenant_id, trip_id);

-- The outcome ledger. One logical outcome (a booking of one item, a supplier's cancellation notice,
-- a traveler's feedback on one component) is a logical key; a correction or a revision is a higher
-- revision of the same key. Rows are appended, never updated or deleted: the evidence at any cutoff
-- is "the highest revision of each key recorded by then", which is what makes a rebuild reproduce
-- an aggregate. Nothing here is inferred: completion needs an attestation, a refund needs Finance.
CREATE TABLE outcome (
    outcome_id           VARCHAR(40)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    logical_key          VARCHAR(300) NOT NULL,
    revision             INTEGER      NOT NULL,
    kind                 VARCHAR(40)  NOT NULL,
    -- SUCCESS | FAILURE | NEUTRAL: how reliability-v1 reads the outcome for its supplier key.
    quality              VARCHAR(10)  NOT NULL,
    supplier_key         VARCHAR(200),
    provider             VARCHAR(60),
    -- SANDBOX | LIVE, from the provider that produced the outcome; never mixed in a profile.
    evidence_class       VARCHAR(10)  NOT NULL,
    trip_id              VARCHAR(64),
    order_id             VARCHAR(64),
    item_id              VARCHAR(64),
    component_id         VARCHAR(64),
    disruption_id        VARCHAR(64),
    traveler_id          VARCHAR(64),
    -- When the fact became true (the event's occurredAt, the attestation's time).
    observed_at          TIMESTAMPTZ  NOT NULL,
    recorded_at          TIMESTAMPTZ  NOT NULL,
    -- EVENT | API | FEEDBACK, with the event id / principal that produced it.
    source               VARCHAR(20)  NOT NULL,
    source_ref           VARCHAR(200) NOT NULL,
    provenance           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT outcome_revision UNIQUE (tenant_id, logical_key, revision)
);
CREATE INDEX outcome_by_supplier ON outcome (tenant_id, evidence_class, supplier_key, observed_at);
CREATE INDEX outcome_by_trip ON outcome (tenant_id, trip_id);

-- Explicit traveler feedback: structured values only feed learning; the free text is stored as
-- untrusted evidence for a person to read and is never parsed, never published, never a label.
CREATE TABLE feedback (
    feedback_id          VARCHAR(40)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    trip_id              VARCHAR(64)  NOT NULL,
    traveler_id          VARCHAR(64)  NOT NULL,
    component_id         VARCHAR(64)  NOT NULL DEFAULT '',
    supplier_key         VARCHAR(200),
    provider             VARCHAR(60),
    revision             INTEGER      NOT NULL,
    rating               INTEGER      NOT NULL,
    tags                 JSONB        NOT NULL DEFAULT '[]'::jsonb,
    comment              VARCHAR(2000),
    recorded_by          VARCHAR(200) NOT NULL,
    recorded_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT feedback_revision UNIQUE (tenant_id, trip_id, traveler_id, component_id, revision)
);

-- Every optimizer decision the platform announced: what was ranked, what was selected, what the
-- learning evidence said. Evaluation replays these chronologically.
CREATE TABLE decision (
    decision_id          VARCHAR(64)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    trip_id              VARCHAR(64)  NOT NULL,
    evidence_class       VARCHAR(10)  NOT NULL,
    decided_at           TIMESTAMPTZ  NOT NULL,
    selected_id          VARCHAR(64),
    selected_keys        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    candidates           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    learning             JSONB,
    source_event_id      VARCHAR(40)  NOT NULL
);
CREATE INDEX decision_by_time ON decision (tenant_id, evidence_class, decided_at);

-- A versioned, immutable-once-built learned profile: what it was built from (cutoff, window,
-- fingerprint, counts), how (algorithm, parameters), the artifact (body) and its evaluation.
CREATE TABLE profile (
    profile_id           VARCHAR(40)  PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,   -- BUILDING | BUILT | ELIGIBLE | REJECTED | FAILED
    algorithm_version    VARCHAR(40)  NOT NULL,
    evidence_class       VARCHAR(10)  NOT NULL,
    input_cutoff         TIMESTAMPTZ  NOT NULL,
    window_start         TIMESTAMPTZ  NOT NULL,
    parameters           JSONB        NOT NULL,
    dataset_fingerprint  VARCHAR(64),
    hard_outcomes        INTEGER      NOT NULL DEFAULT 0,
    feedback_outcomes    INTEGER      NOT NULL DEFAULT 0,
    supplier_keys        INTEGER      NOT NULL DEFAULT 0,
    body                 JSONB,
    evaluation           JSONB,
    verdict              VARCHAR(40),
    failure_code         VARCHAR(80),
    failure_message      VARCHAR(2000),
    requested_by         VARCHAR(200) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    built_at             TIMESTAMPTZ,
    evaluated_at         TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ
);
CREATE INDEX profile_by_tenant ON profile (tenant_id, created_at DESC);

-- Who activated, rolled back or changed the mode, when, from what to what.
CREATE TABLE activation_history (
    id                   BIGSERIAL    PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    action               VARCHAR(20)  NOT NULL,   -- ACTIVATED | ROLLED_BACK | MODE_CHANGED
    profile_id           VARCHAR(40),
    previous_profile_id  VARCHAR(40),
    mode                 VARCHAR(10)  NOT NULL,
    previous_mode        VARCHAR(10),
    config_version       BIGINT       NOT NULL,
    actor                VARCHAR(200) NOT NULL,
    occurred_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX activation_history_by_tenant ON activation_history (tenant_id, id);

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
