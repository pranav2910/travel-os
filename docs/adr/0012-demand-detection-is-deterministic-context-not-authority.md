# ADR-0012: Travel demand is detected in Enterprise Context by deterministic rules; a person converts it

Status: accepted (Slice 4, 2026-09-15)

## Context

Slice 4 asks the platform to notice that an employee will need to travel before anyone types a
request: from the calendar (a confirmed in-person meeting away from home), the CRM (a scheduled
on-site visit), the HRIS (who the person is, where they work, who their manager is) and the expense
system (what was already spent or pre-approved). The design package puts org structures and traveler
profiles in an **Enterprise Context** service and forbids one service from writing another's
database (ADR-0006). The LLM never authorizes (ADR-0003). Every source carries free text written by
people outside the platform, some of it hostile.

## Decision

1. **One service owns identity and demand.** `services/enterprise-context` holds the verified
   employee directory (mirrored from the HRIS connector and nothing else), the tenant's connectors,
   every source item at every revision, and the demand candidates. Identity is resolved only through
   the directory's e-mail mapping; a display name, an attendee list or a CRM note never names a
   traveler or an approver. The manager relationship is the HRIS's, not a realm role.
2. **Eligibility is deterministic and versioned** (`rules-v1`, `detect/DetectionRules`). A calendar
   event qualifies when it is confirmed, accepted by the traveler (or organized by them), in person
   (no virtual mode, no conferencing-only invitation), at a place the platform can plan travel to,
   away from the traveler's work location, and in the future. A CRM record qualifies only as a
   scheduled on-site visit; a deal's value, stage or notes never do. Expense records never create
   demand: they enrich (past visits) or flag (overlapping pre-approval or trip report) a candidate.
   Every decision is explained in words; free text is quoted as evidence, bounded, never
   interpreted. No model runs in detection; the LLM gateway is not used in this slice.
3. **Candidates have one identity and keep every source.** The primary commitment keys the candidate
   (`primary_key`), so a redelivery updates it and never duplicates it. A CRM visit joins a calendar
   event's candidate through an explicit link (`calendarEventId`) or the documented rule (same
   verified traveler, same city, overlapping local dates); adjacent days are not merged but flagged
   `POSSIBLY_RELATED` on both. Candidates never merge across tenants or travelers. Source references
   are added, never replaced; every accepted revision is kept.
4. **The lifecycle is explicit**: `NEEDS_REVIEW` (something missing or a review flag), `ACTIONABLE`,
   `DISMISSED` (a person), `WITHDRAWN` (the source: cancelled, deleted, declined, traveler inactive;
   a later valid revision may restore it, an older one cannot), `CONVERTED`. Transitions are stored
   with reason, actor and detail. After conversion the candidate is never moved again: a source
   change is recorded (`CHANGED_AFTER_CONVERSION:*`, `travel.demand.candidate-changed-after-conversion`)
   and left to the trip's existing controls; nothing cancels or re-creates a trip on its own.
5. **Conversion is a person's act and Travel Core's creation.** Detection creates no trip and no
   booking. The traveler, their HRIS manager or a travel admin converts `ACTIONABLE` demand; the
   service derives a frozen intent deterministically (`plan-v1`: arrive two hours before the first
   commitment or the evening before an early start, leave after the last, a required stay for every
   night on the destination's calendar) and calls Travel Core's new `CreateTrip` RPC with the person
   as principal, their roles as validated by their token, source `DEMAND`, the candidate id as
   `sourceReference`, and one idempotency key per candidate. Travel Core applies its own arranger
   rule and idempotency; the trip then runs the unchanged policy, optimization, approval, booking and
   audit flow (self-approval stays forbidden there). The candidate row lock plus the fixed key make
   repeated and concurrent conversions one trip.
6. **Synchronization is a Temporal workflow driven page by page.** A run is requested (schedule,
   signed webhook, or a person) as a `travel.demand.sync-requested` event; the worker starts
   `DemandSyncWorkflow` (workflow id = run id). Each page is one `SyncPage` call whose fetch, item and
   revision writes, detection, events and checkpoint advance are one transaction: the checkpoint
   moves only with the page that justifies it. An older revision is ignored, the same revision is a
   no-op, so a repeated page (a worker that died before recording the answer) changes nothing.
   Retryable source trouble (outage, rate limit) is `UNAVAILABLE` / `RESOURCE_EXHAUSTED` and is waited
   out with backoff; a connector already running is waited for, not raced. Sandbox connectors are
   SIMULATED tables with the same paging contract and controllable faults.
7. **Slice 3 carry-overs closed here**: a legacy `hotelRequired=true` request becomes an explicit stay
   when its nights are unambiguous and is refused up front (`HOTEL_DETAILS_INSUFFICIENT`) otherwise;
   component bookings, compensations and open exposures are metrics with bounded labels; the
   sandbox airline serves every date in a search window and can move a disrupted passenger to the
   next day, so a recovery re-dating a hotel is proven live.

## Consequences

- A new database (`enterprise_context`), service, Helm alias, kind port (18090) and worker task
  queue (`demand-sync`); `travel.demand` joins the topic registry with nine event types.
- Demand data is evidence: candidates and their trails are readable by the traveler, their HRIS
  manager and travel admins (cross-tenant is 404), and the audit service indexes candidates like
  trips (`GET /api/v1/audit/demand/{candidateId}`); a trip's ledger names the demand it came from.
- Live calendar/CRM/HRIS/expense providers plug into `EnterpriseSource<T>` with their own paging and
  credentials (named by reference, resolved from the environment); none exists yet. Email as a
  source, learning from outcomes (Slice 5) and the frontend are out of scope.
