# Prompt 1 — capability inventory (discovered, not assumed)

Sources: README, `docs/frontend/api-matrix.md`, ADR-0009…0012, controllers (grep of every `@*Mapping`), `web/src/App.tsx`, the Keycloak realm, live token claims, and the running stack at commit `cda5ccb`.

## Pages (web/src/App.tsx)

| Route | Page | Who sees it (navigation is built from token roles; the server enforces) |
|---|---|---|
| `/` | Overview (counts + recent trips; attention counts for approvers) | signed-in users |
| `/trips`, `/trips/new`, `/trips/:tripId` | trips list, new trip (round trip / one way / multi-city / free text), trip detail (progress, request, outcome, components, bookings, disruptions, decision record, timeline, feedback, completion attestation, cancel) | TRAVELER (own trips); MANAGER/TRAVEL_ADMIN/FINANCE read tenant-wide |
| `/approvals` | trips awaiting approval + recoveries needing a decision | MANAGER, TRAVEL_ADMIN |
| `/operations`, `/disruptions/:id` | disruption list and detail with approve/reject of a recovery | MANAGER, TRAVEL_ADMIN, FINANCE |
| `/finance` | open/resolved exposures, exposure resolution, settled-refund recording | FINANCE, TRAVEL_ADMIN |
| `/demand`, `/demand/:id` | detected demand inbox and detail (resolve missing fields, dismiss, convert) | all roles (own candidates) |
| `/connectors` | simulated connectors: create, enable/disable, schedule, sync, runs | TRAVEL_ADMIN, FINANCE |
| `/learning`, `/learning/profiles/:id` | learning mode, evidence, profiles, build/activate/rollback | TRAVEL_ADMIN (FINANCE read) |
| `/auth/callback`, `*` | OIDC callback, not found | — |

## API operations (49, all under `/api/v1`, all bearer-protected; the edge proxies only these prefixes)

- travel-core: `GET /trips` (mine | `scope=tenant&status=`), `POST /trips`, `GET /trips/{id}`, `/components`, `/decisions`, `/history`, `POST /trips/{id}/approval`, `/cancellation`, `/completion`
- policy: `GET /policies`, `/policies/{id}`, `/policies/{id}/versions/{v}`, `POST /policies`, `PUT /policies/default`, `GET /policy-decisions?tripId=`, `/policy-decisions/{id}`
- supplier-gateway: `POST /suppliers/{provider}/events` (HMAC-signed supplier notices; not a browser route)
- order: `GET /orders?tripId=`, `/orders/{id}`, `/orders/exposures?status=`, `POST /orders/{id}/exposures/{e}/resolution`
- audit: `GET /audit/events`, `/audit/trips/{id}`, `/audit/trips/{id}/decisions`, `/audit/demand/{id}`
- disruption: `GET /disruptions`, `/disruptions/{id}`, `/trips/{id}/disruptions`, `POST /disruptions/{id}/approval`
- enterprise-context: connectors CRUD-ish (`POST /connectors`, `/status`, `/config`, `/sync`, `/sandbox/items`, `/sandbox/faults`, `GET /connectors`, `/{id}`, `/{id}/runs`), demand (`GET /demand`, `/{id}`, `/evidence`, `/history`, `POST /demand/{id}/details`, `/dismissal`, `/conversion`), `POST /connectors/{provider}/events`
- learning: `GET/PUT /learning/config`, `GET /learning/summary`, `/outcomes`, `/feedback`, `/preferences`, `/profiles`, `/profiles/{id}`, `/history`, `POST /learning/profiles`, `/profiles/{id}/activation`, `/rollback`, `/feedback`, `/outcomes/refunds`

Every mutation requires an `Idempotency-Key`; errors are RFC 9457 problem documents (`status, code, title, detail`).

## Background work

- Temporal workflows: `TripWorkflow` (id = trip id; legacy round-trip path and the itinerary path `ItineraryFlow`), `RecoveryWorkflow` (disruption recovery), `DemandSyncWorkflow` (connector sync runs), `LearningBuildWorkflow` (profile builds).
- Kafka topics `travel.*` with transactional outboxes in every service; the audit service indexes every event into the ledger.
- No scheduler-driven booking; connectors can be scheduled.

## Booking states (documented lifecycle)

Trip: DRAFT → SUBMITTED → PLANNING → (AWAITING_APPROVAL ↔) APPROVED → BOOKING → BOOKED → COMPLETED; CANCELLED and FAILED are terminal; APPROVED → PLANNING (quote expired, re-plan) and APPROVED → FAILED added in `cda5ccb`. Component states: PLANNED, QUOTED, REVALIDATING, BOOKING, CONFIRMED, FAILED, SKIPPED, CANCELLED, CANCEL_FAILED. Order: CONFIRMED, CHANGED, PARTIALLY_FAILED, FAILED, CANCELLED; exposures OPEN/RESOLVED. Disruption: DETECTED, IMPACT_CONFIRMED, SEARCHING, OPTIMIZING, DECISION_READY, HUMAN_REQUIRED, CHANGING, RESOLVED, MANUAL_INTERVENTION, FAILED.

## Agent tools

- `llm-gateway` (Python): `ExtractIntent` (free-text request → structured intent, ledgered with model-call evidence), `ExplainTrip`, `ExplainDisruption` (narratives). The stack runs `LLM_PROVIDER=fake` (deterministic stub); no live model.
- There is **no in-app conversational booking agent**: no chat surface, no multi-turn context, no plan-only / "book that" intents, no hold. Free text is a one-shot request form. (Prompt 7/8 items depending on a conversation are NOT_IMPLEMENTED.)

## Roles and tenants (verified from token claims)

acme: alice TRAVELER (emp_1001), bob MANAGER+TRAVELER (emp_1002), carol TRAVEL_ADMIN+FINANCE+TRAVELER (emp_1003), dan TRAVELER (emp_1004). globex: zoe TRAVELER (emp_2001). Tokens carry `tenant_id`, `employee_id`, `roles`, audience `travelos-api`, issuer the local realm, 900 s lifetime.

## Checklist features: implemented / missing / excluded

| Feature family | Status |
|---|---|
| Flights (sandbox-air, 5 carriers, any 3-letter code accepted), hotels (sandbox-hotel), ground (sandbox-ground) | implemented (simulated) |
| Trip create (structured + free text), list (mine / tenant), detail, cancel, completion attestation | implemented |
| Trip **edit**, trip **delete**, editing/reordering legs after submission | NOT_IMPLEMENTED (trips are immutable requests; cancel is the only correction) |
| Travelers as entities (names, birthdates, children, passports) | NOT_IMPLEMENTED (one traveler = the principal, or `travelerId` set by MANAGER/TRAVEL_ADMIN; no counts) |
| Choosing among offers (compare five, pick the third, airline/airport filters) | NOT_IMPLEMENTED by design: the optimizer picks within policy; the traveler constrains through time windows, cities and dates only |
| Open jaw / multi-city / flight+hotel / transfers | implemented via itinerary legs, stays, transfers |
| Budget per trip | policy-owned (`trip.maxTotal`, thresholds), not a per-request field |
| Conversational agent, plan-only, hold, "book that", stop/continue | NOT_IMPLEMENTED |
| Approvals, self-approval refusal, stale decisions | implemented |
| Disruption recovery with the USD 100 autonomy rule, exposures, refunds | implemented |
| Demand detection from simulated calendar/CRM/HRIS/expense | implemented |
| Learning (OFF/SHADOW/ACTIVE, bounded) | implemented (sandbox evidence only) |
| Live providers, email ingestion, real payments | explicitly excluded (roadmap) |
| Editing a booked trip ("leave one day later") from the traveler side | NOT_IMPLEMENTED (changes come from supplier notices only) |
