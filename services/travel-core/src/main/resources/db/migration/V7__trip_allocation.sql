-- Profiles & organization (ADR-0014): the cost allocation and the arranger relationship a trip was
-- requested under, captured from Enterprise Context at creation and never re-derived. Trips from
-- before this migration (and trips created while Enterprise Context is not configured) have no
-- row: their visibility keeps the Slice 1 rule (MANAGER tenant-wide).
CREATE TABLE trip_allocation (
  tenant_id            VARCHAR(64)  NOT NULL,
  trip_id              VARCHAR(40)  NOT NULL,
  department_id        VARCHAR(64),
  cost_center_id       VARCHAR(64),
  legal_entity_id      VARCHAR(64),
  office_id            VARCHAR(64),
  project_id           VARCHAR(64),
  project_restricted   BOOLEAN      NOT NULL DEFAULT FALSE,
  manager_employee_id  VARCHAR(64),
  arranger_employee_id VARCHAR(64),
  arranger_basis       VARCHAR(32)  NOT NULL,   -- SELF | MANAGER | SPONSOR | GRANT | TRAVEL_ADMIN
  traveler_kind        VARCHAR(16)  NOT NULL DEFAULT 'EMPLOYEE',
  profile_version      BIGINT       NOT NULL DEFAULT 0,
  captured_at          TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (tenant_id, trip_id),
  FOREIGN KEY (trip_id) REFERENCES trip (trip_id)
);
CREATE INDEX trip_allocation_by_manager  ON trip_allocation (tenant_id, manager_employee_id);
CREATE INDEX trip_allocation_by_arranger ON trip_allocation (tenant_id, arranger_employee_id);
CREATE INDEX trip_allocation_by_project  ON trip_allocation (tenant_id, project_id) WHERE project_id IS NOT NULL;
