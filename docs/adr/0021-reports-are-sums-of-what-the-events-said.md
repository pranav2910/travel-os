# ADR-0021: Reports are sums and counts of what the events said, computed in the audit service

Status: accepted (platform completion, Phase 9, 2026-09-24)

## Context

Finance and travel administrators need spend by cost center, project and month; the exceptions
(policy violations, approvals that stalled, budgets exceeded, cases and their turnaround); the
outcomes (bookings, cancellations, disruptions recovered with or without a person); and supplier
performance. The platform had a per-trip audit trail and a Learning summary, nothing that
aggregates.

## Decision

1. **The audit service computes the reports**, because it already holds every event of every
   service exactly once, in order, per tenant (ADR-0009). No other service is asked; no report
   reaches into another service's database.
2. **`trip_fact` is a derived row per trip**, maintained in the same transaction as each stored
   event: who, where (the allocation now travels on `travel.trip.created/booked`), when, what was
   booked, captured, refunded, credited and added by recoveries, and how many violations,
   approvals, escalations, disruptions and cases the trip caused. Spend and outcomes are SQL over
   it; exceptions and suppliers are SQL over `audit_event`'s JSON. Money stays in the trip's
   currency, in integer minor units; nothing is converted or estimated.
3. **Four reports, one shape each, JSON or CSV**: `spend` (grouped by cost center, project,
   department, legal entity, office, traveler, destination or month), `outcomes`, `exceptions`,
   `suppliers`, under `/api/v1/reports` for FINANCE and TRAVEL_ADMIN, over a half-open period of
   at most 400 days.

## Consequences

- A report is exactly as good as the events: a fact no service announced is not reported, and
  the same event replayed changes nothing.
- `trip_fact` can be rebuilt from `audit_event` at any time; it is a cache of sums, not a source.
- Multi-currency tenants see one row per currency in a group; conversion is Finance's, not the
  report's.
