-- Traveler profiles and organizational context (ADR-0014). Sensitive values (document numbers,
-- dates of birth, phones, loyalty numbers, emergency contacts) are stored encrypted (AES-256-GCM,
-- libs/common FieldCipher, key from the secrets mechanism); the *_enc columns never hold plaintext.

ALTER TABLE employee
    ADD COLUMN department_id   VARCHAR(64),
    ADD COLUMN cost_center_id  VARCHAR(64),
    ADD COLUMN legal_entity_id VARCHAR(64),
    ADD COLUMN office_id       VARCHAR(64);

CREATE TABLE org_unit (
    tenant_id        VARCHAR(64)  NOT NULL,
    unit_id          VARCHAR(64)  NOT NULL,
    kind             VARCHAR(20)  NOT NULL,   -- DEPARTMENT | LEGAL_ENTITY | OFFICE | COST_CENTER
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    parent_unit_id   VARCHAR(64),
    legal_entity_id  VARCHAR(64),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version          BIGINT       NOT NULL DEFAULT 1,
    updated_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, unit_id),
    UNIQUE (tenant_id, kind, code)
);

CREATE TABLE project (
    tenant_id        VARCHAR(64)  NOT NULL,
    project_id       VARCHAR(64)  NOT NULL,
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    client           VARCHAR(200),
    cost_center_id   VARCHAR(64),
    restricted       BOOLEAN      NOT NULL DEFAULT FALSE,   -- only members (and their managers) may see its travel
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version          BIGINT       NOT NULL DEFAULT 1,
    updated_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, project_id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE project_member (
    tenant_id        VARCHAR(64)  NOT NULL,
    project_id       VARCHAR(64)  NOT NULL,
    employee_id      VARCHAR(64)  NOT NULL,
    role             VARCHAR(20)  NOT NULL,   -- MEMBER | LEAD
    since            TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, project_id, employee_id)
);
CREATE INDEX project_member_by_employee ON project_member (tenant_id, employee_id);

CREATE TABLE traveler_profile (
    tenant_id            VARCHAR(64)  NOT NULL,
    traveler_id          VARCHAR(64)  NOT NULL,   -- an employee id or a guest id (gst_...)
    kind                 VARCHAR(10)  NOT NULL,   -- EMPLOYEE | GUEST
    given_name           VARCHAR(100) NOT NULL,
    family_name          VARCHAR(100) NOT NULL,
    middle_name          VARCHAR(100),
    email                VARCHAR(320) NOT NULL,
    phone_enc            TEXT,
    date_of_birth_enc    TEXT,
    gender               VARCHAR(10),
    nationality          CHAR(2),
    home_airport         CHAR(3),
    preferences          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    loyalty_enc          TEXT,                    -- JSON list, encrypted as a whole
    emergency_enc        TEXT,                    -- JSON object, encrypted as a whole
    sponsor_employee_id  VARCHAR(64),             -- guests: the employee responsible for them
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    version              BIGINT       NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, traveler_id)
);
CREATE INDEX traveler_profile_by_sponsor ON traveler_profile (tenant_id, sponsor_employee_id) WHERE sponsor_employee_id IS NOT NULL;

CREATE TABLE travel_document (
    tenant_id            VARCHAR(64)  NOT NULL,
    document_id          VARCHAR(64)  NOT NULL,
    traveler_id          VARCHAR(64)  NOT NULL,
    type                 VARCHAR(20)  NOT NULL,   -- PASSPORT | NATIONAL_ID | VISA
    number_enc           TEXT         NOT NULL,
    number_last4         VARCHAR(8)   NOT NULL,
    issuing_country      CHAR(2)      NOT NULL,
    nationality          CHAR(2),
    issued_on            DATE,
    expires_on           DATE         NOT NULL,
    holder_given_name    VARCHAR(100) NOT NULL,
    holder_family_name   VARCHAR(100) NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    revoked_at           TIMESTAMPTZ,
    retention_until      DATE         NOT NULL,   -- purged after this date (revoked or expired + the retention period)
    PRIMARY KEY (tenant_id, document_id)
);
CREATE INDEX travel_document_by_traveler ON travel_document (tenant_id, traveler_id);

-- Every profile change is recorded (field names only, never the sensitive values).
CREATE TABLE profile_change (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    traveler_id  VARCHAR(64)  NOT NULL,
    version      BIGINT       NOT NULL,
    changed_by   VARCHAR(128) NOT NULL,
    changed_at   TIMESTAMPTZ  NOT NULL,
    fields       JSONB        NOT NULL
);
CREATE INDEX profile_change_by_traveler ON profile_change (tenant_id, traveler_id, version);

-- Who may arrange travel for whom, explicitly. The HRIS manager relation and the TRAVEL_ADMIN role
-- are the only implicit bases; a MANAGER role alone grants nothing.
CREATE TABLE arranger_grant (
    tenant_id             VARCHAR(64)  NOT NULL,
    grant_id              VARCHAR(64)  NOT NULL,
    arranger_employee_id  VARCHAR(64)  NOT NULL,
    scope_kind            VARCHAR(20)  NOT NULL,   -- EMPLOYEE | ORG_UNIT | PROJECT | TENANT
    scope_id              VARCHAR(64),
    may_read_documents    BOOLEAN      NOT NULL DEFAULT FALSE,
    granted_by            VARCHAR(128) NOT NULL,
    granted_at            TIMESTAMPTZ  NOT NULL,
    expires_at            TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, grant_id)
);
CREATE INDEX arranger_grant_by_arranger ON arranger_grant (tenant_id, arranger_employee_id);

-- Every read of a document number or a full passenger snapshot: who, whose, why, when.
CREATE TABLE sensitive_access (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    principal    VARCHAR(128) NOT NULL,
    traveler_id  VARCHAR(64)  NOT NULL,
    kind         VARCHAR(40)  NOT NULL,   -- DOCUMENT_NUMBER | PASSENGER_SNAPSHOT | LOYALTY_NUMBER
    purpose      VARCHAR(80)  NOT NULL,
    at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX sensitive_access_by_traveler ON sensitive_access (tenant_id, traveler_id, at DESC);
