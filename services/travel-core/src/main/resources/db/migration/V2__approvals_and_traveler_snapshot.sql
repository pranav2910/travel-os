-- Who travels, as captured from the identity provider at request time (Enterprise Context replaces
-- this in a later slice), and the selected bundle's total once planned.
ALTER TABLE trip
    ADD COLUMN traveler_given_name  VARCHAR(100) NOT NULL DEFAULT '',
    ADD COLUMN traveler_family_name VARCHAR(100) NOT NULL DEFAULT '',
    ADD COLUMN traveler_email       VARCHAR(320) NOT NULL DEFAULT '',
    ADD COLUMN total_currency       CHAR(3),
    ADD COLUMN total_minor          BIGINT,
    ADD COLUMN failure_stage        VARCHAR(20),
    ADD COLUMN failure_code         VARCHAR(80);

-- Human approvals. Slice 1: one pending approval per trip, decided by any MANAGER / TRAVEL_ADMIN of
-- the tenant other than the traveler. Manager-of-record arrives with the Enterprise Context service.
CREATE TABLE approval (
    approval_id         VARCHAR(40)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    trip_id             VARCHAR(40)  NOT NULL REFERENCES trip (trip_id),
    required_role       VARCHAR(30)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    policy_decision_id  VARCHAR(40),
    requested_at        TIMESTAMPTZ  NOT NULL,
    decided_by          VARCHAR(128),
    decided_at          TIMESTAMPTZ,
    comment             VARCHAR(2000),
    decision_idempotency_key VARCHAR(160)
);

CREATE INDEX approval_by_trip ON approval (tenant_id, trip_id, requested_at DESC);
CREATE UNIQUE INDEX approval_one_pending_per_trip ON approval (trip_id) WHERE status = 'PENDING';
