-- Phase 7: approval chains (sequential steps from the policy's chain), expiry with escalation, and
-- delegated approval authority.

ALTER TABLE approval ADD COLUMN step              INTEGER      NOT NULL DEFAULT 1;
ALTER TABLE approval ADD COLUMN chain_length      INTEGER      NOT NULL DEFAULT 1;
-- the roles of every step in order, comma-separated (MANAGER,FINANCE)
ALTER TABLE approval ADD COLUMN chain_roles       VARCHAR(200);
ALTER TABLE approval ADD COLUMN expires_at        TIMESTAMPTZ;
ALTER TABLE approval ADD COLUMN escalated_at      TIMESTAMPTZ;
ALTER TABLE approval ADD COLUMN escalated_to_role VARCHAR(30);
-- when a delegate decided: the employee whose authority they exercised
ALTER TABLE approval ADD COLUMN on_behalf_of      VARCHAR(128);
CREATE INDEX approval_pending_due ON approval (expires_at) WHERE status = 'PENDING';

-- A manager hands their approval authority to someone for a period (leave, travel). The delegate
-- decides in the delegator's name; the record says so.
CREATE TABLE approval_delegate (
    delegate_id            VARCHAR(40)  PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL,
    delegator_employee_id  VARCHAR(64)  NOT NULL,
    delegate_employee_id   VARCHAR(64)  NOT NULL,
    valid_from             TIMESTAMPTZ  NOT NULL,
    valid_until            TIMESTAMPTZ  NOT NULL,
    created_by             VARCHAR(128) NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    revoked_at             TIMESTAMPTZ,
    revoked_by             VARCHAR(128)
);
CREATE INDEX approval_delegate_by_delegate ON approval_delegate (tenant_id, delegate_employee_id);
CREATE INDEX approval_delegate_by_delegator ON approval_delegate (tenant_id, delegator_employee_id);
