-- The trace of the transaction that appended the row, restored when relayed (libs/spring-outbox).
ALTER TABLE outbox ADD COLUMN trace_parent VARCHAR(80);
