-- Slice 4: what a trip was made from (a detected demand candidate id when the source is DEMAND).
ALTER TABLE trip ADD COLUMN source_reference VARCHAR(128);
CREATE INDEX trip_by_source_reference ON trip (tenant_id, source_reference) WHERE source_reference IS NOT NULL;
