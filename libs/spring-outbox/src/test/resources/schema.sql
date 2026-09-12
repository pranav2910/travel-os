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
