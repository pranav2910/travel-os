-- Phase 7: SCIM 2.0 provisioning. An identity provider creates, updates and deactivates employees;
-- the IdP's own id is kept so its lookups by externalId work.
ALTER TABLE employee ADD COLUMN scim_external_id VARCHAR(200);
ALTER TABLE employee ADD COLUMN provisioned_by   VARCHAR(40);
ALTER TABLE employee ADD COLUMN provisioned_at   TIMESTAMPTZ;
CREATE INDEX employee_by_external_id ON employee (tenant_id, scim_external_id);
