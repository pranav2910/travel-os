-- A cancelled flight is gone for everyone; the reaccommodation FARES are quoted to the disrupted
-- passenger only (keyed by the correlation id the platform books and searches with). Other
-- travelers on the same route and day keep the published inventory.
ALTER TABLE sandbox_reaccommodation ADD COLUMN correlation_id VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE sandbox_reaccommodation DROP CONSTRAINT sandbox_reaccommodation_pkey;
ALTER TABLE sandbox_reaccommodation ADD PRIMARY KEY (tenant_id, correlation_id, origin, destination, outbound_date, cabin);
CREATE INDEX sandbox_reaccommodation_by_route ON sandbox_reaccommodation (tenant_id, origin, destination, outbound_date, cabin);
