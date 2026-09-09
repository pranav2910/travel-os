# ADR-0008: Ids are `<prefix>_<ULID>`; tenant ids are slugs

**Status:** accepted · **Date:** 2026-09-09

## Decision
- Business ids are `trip_01J...`, `ord_01J...`, `pd_01J...` etc. (`io.travelos.common.ids.Ids`).
  ULIDs are time-sortable (append-mostly indexes, natural "newest first") and the prefix makes any
  id self-describing in a log line or a support ticket. Stored as `VARCHAR(40)`, not `UUID`.
- Tenant ids are opaque lowercase slugs (`acme`), because they appear in JWT claims, cache keys
  (`tenant:acme:trip:...`), Kafka headers and dashboards. Stored as `VARCHAR(64)`.
- Deviation from the design package's `UUID PRIMARY KEY` columns is deliberate and applies uniformly.
