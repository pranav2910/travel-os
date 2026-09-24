-- Phase 8: durable notifications with per-person preferences and per-channel deliveries; travel
-- safety advisories with affected travelers and check-ins. The trip index learns where a trip goes.

ALTER TABLE trip_index ADD COLUMN traveler_email VARCHAR(320);
ALTER TABLE trip_index ADD COLUMN traveler_name  VARCHAR(200);
ALTER TABLE trip_index ADD COLUMN status         VARCHAR(20);
ALTER TABLE trip_index ADD COLUMN origin         VARCHAR(3);
ALTER TABLE trip_index ADD COLUMN destination    VARCHAR(3);
ALTER TABLE trip_index ADD COLUMN departs_at     TIMESTAMPTZ;
ALTER TABLE trip_index ADD COLUMN returns_at     TIMESTAMPTZ;
ALTER TABLE trip_index ADD COLUMN cities         JSONB;
CREATE INDEX trip_index_by_window ON trip_index (tenant_id, departs_at, returns_at);

-- What a person should know. One row per fact and recipient (dedupe_key); read state is theirs.
CREATE TABLE notification (
    notification_id   VARCHAR(40)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    -- exactly one of: an employee, or a role (everyone holding it in the tenant)
    recipient_employee_id VARCHAR(64),
    recipient_role    VARCHAR(30),
    -- TRIP | APPROVAL | DISRUPTION | CASE | SAFETY | FINANCE
    category          VARCHAR(20)  NOT NULL,
    -- the event type or action that caused it
    kind              VARCHAR(80)  NOT NULL,
    title             VARCHAR(300) NOT NULL,
    body              TEXT         NOT NULL,
    -- what the person can open: a trip, a case, an advisory, an approval
    link_kind         VARCHAR(20),
    link_id           VARCHAR(64),
    trip_id           VARCHAR(64),
    dedupe_key        VARCHAR(300) NOT NULL,
    priority          VARCHAR(10)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    read_at           TIMESTAMPTZ,
    source_event_id   VARCHAR(40),
    UNIQUE (tenant_id, dedupe_key)
);
CREATE INDEX notification_inbox ON notification (tenant_id, recipient_employee_id, created_at DESC);
CREATE INDEX notification_role_inbox ON notification (tenant_id, recipient_role, created_at DESC);

-- One row per channel a notification goes out on; the dispatcher retries until it gives up.
CREATE TABLE notification_delivery (
    delivery_id       VARCHAR(40)  PRIMARY KEY,
    notification_id   VARCHAR(40)  NOT NULL REFERENCES notification (notification_id),
    tenant_id         VARCHAR(64)  NOT NULL,
    -- IN_APP | EMAIL | CHAT
    channel           VARCHAR(10)  NOT NULL,
    address           VARCHAR(320),
    -- PENDING | SENT | FAILED | SKIPPED
    status            VARCHAR(10)  NOT NULL,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ,
    last_error        VARCHAR(500),
    provider_ref      VARCHAR(200),
    sent_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX notification_delivery_due ON notification_delivery (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX notification_delivery_by_notification ON notification_delivery (notification_id);

-- How a person wants to be reached. Absent = in-app always, email when an address is known.
CREATE TABLE notification_preference (
    tenant_id         VARCHAR(64)  NOT NULL,
    employee_id       VARCHAR(64)  NOT NULL,
    email             VARCHAR(320),
    chat_handle       VARCHAR(200),
    -- {"TRIP": ["IN_APP","EMAIL"], ...}; a category absent here uses the defaults
    channels          JSONB        NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, employee_id)
);

-- A safety advisory: where, when, how serious; the travelers it affects are the booked trips in
-- those places and that window at the time it is issued (and later, on request).
CREATE TABLE safety_advisory (
    advisory_id       VARCHAR(40)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    title             VARCHAR(300) NOT NULL,
    -- LOW | MEDIUM | HIGH | CRITICAL
    severity          VARCHAR(10)  NOT NULL,
    countries         JSONB        NOT NULL,
    cities            JSONB        NOT NULL,
    starts_at         TIMESTAMPTZ  NOT NULL,
    ends_at           TIMESTAMPTZ  NOT NULL,
    text              TEXT         NOT NULL,
    source            VARCHAR(200),
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    issued_by         VARCHAR(128) NOT NULL,
    issued_at         TIMESTAMPTZ  NOT NULL,
    checkin_due_at    TIMESTAMPTZ
);
CREATE INDEX safety_advisory_by_tenant ON safety_advisory (tenant_id, active, issued_at DESC);

CREATE TABLE safety_affected (
    advisory_id       VARCHAR(40)  NOT NULL REFERENCES safety_advisory (advisory_id),
    tenant_id         VARCHAR(64)  NOT NULL,
    traveler_id       VARCHAR(64)  NOT NULL,
    trip_id           VARCHAR(64)  NOT NULL,
    notified_at       TIMESTAMPTZ  NOT NULL,
    case_id           VARCHAR(40),
    PRIMARY KEY (advisory_id, traveler_id, trip_id)
);

CREATE TABLE safety_checkin (
    checkin_id        VARCHAR(40)  PRIMARY KEY,
    advisory_id       VARCHAR(40)  NOT NULL REFERENCES safety_advisory (advisory_id),
    tenant_id         VARCHAR(64)  NOT NULL,
    traveler_id       VARCHAR(64)  NOT NULL,
    -- SAFE | NEEDS_HELP
    status            VARCHAR(12)  NOT NULL,
    note              VARCHAR(2000),
    recorded_at       TIMESTAMPTZ  NOT NULL,
    UNIQUE (advisory_id, traveler_id)
);
