# Verification ledger — platform completion (branch `platform/complete-backend`)

Every phase records the exact commands run, on which commit, with the result. A capability is
"verified" in `capability-matrix.md` only when a row here proves it. Nothing below is a live
provider result unless the row says so.

## Phase 1 — baseline at `61fcf14` (2026-09-24)

| Command | Where | Result |
|---|---|---|
| `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew check` (Docker stack down; Testcontainers Postgres/Kafka) | local, `61fcf14` | BUILD SUCCESSFUL in 3m 1s; 263 run, 263 passed, 0 failed (sum of every module's JUnit summary) |
| `cd web && npm run check` (tsc + vite build) | local, `61fcf14` | exit 0 |
| CI `ci` run 35924659592 (gradle check, web, images, helm, terraform) | GitHub Actions, `61fcf14` | success |
| CI `kind-e2e` run 35924659615 (stack on kind, 18 Playwright specs) | GitHub Actions, `61fcf14` | success |

Note: the local baseline `check` was started before Phase 2 edits began and finished after the
first Phase 2 files were written; its compiled inputs are from `61fcf14` for every module it
reported, but the CI runs above are the authoritative baseline record.

## Phase 2 — traveler profiles, organization, explicit arranger authority (ADR-0014)

| Command | Result |
|---|---|
| `./gradlew --offline :services:enterprise-context:test --tests io.travelos.context.ProfileIntegrationTest` | 6 run, 6 passed |
| `./gradlew --offline :services:travel-core:test --tests io.travelos.travelcore.api.TripArrangerIntegrationTest` | 6 run, 6 passed |
| `./gradlew --offline :libs:common:check :contracts:protobuf:check :libs:events:check :services:enterprise-context:check :services:travel-core:check` (spotless + all tests) | libs/common 59/59, libs/events 63/63, enterprise-context 16/16, travel-core 81/81; BUILD SUCCESSFUL |

What the tests prove (implemented and test-verified; no live system involved):

- Sensitive profile fields and document numbers are ciphertext at rest (`v1:` prefix, plaintext
  absent from the row); reads are redacted by default; a revealed read needs a permitted
  relationship and is written to the access log with principal and purpose.
- The HRIS manager reads the profile but never a document number (403); an unrelated MANAGER-by-role
  gets 404 for profile, documents and trips; an arranger with a grant reads what the grant allows,
  and `mayReadDocuments` is a separate permission.
- `AuthorizeArranger` answers SELF / MANAGER / SPONSOR / GRANT / TRAVEL_ADMIN from records only;
  restricted projects admit members and their managers; a project-scoped grant reaches them; a
  department-wide grant and a travel admin naming a non-member do not.
- Travel Core books an arranged trip under the profile identity even when the request body names
  someone else; request fields cannot escalate; the allocation snapshot names the HRIS manager and
  the arranger; the manager sees the trip, the unrelated manager does not (detail, history, list);
  own travel for an unknown traveler still works on claims; Enterprise Context down → own travel
  proceeds, arranging answers 503; idempotent replay keeps exactly one allocation row.
- Offboarding revokes grants and documents; the retention purge deletes document rows after the
  retention date while the access log remains.

Not verified here: the web frontend against the new manager scoping (runs in CI `kind-e2e` on push
of the branch; the demand spec seeds Alice's HRIS manager as Bob, which the approval spec relies on).

## Phase 3 — planning vs purchase authorization (ADR-0015)

| Command | Result |
|---|---|
| `./gradlew --offline :services:policy:check` | 41 run, 41 passed (rule list now includes `BOOKING_HORIZON`, `PURCHASE_AUTONOMY`) |
| `./gradlew --offline :libs:events:test` | 65 run, 65 passed (two new trip events with examples) |
| `./gradlew --offline :workflows:trip-planning:test` | 65 run, 65 passed (7 new Phase 3 scenarios) |
| `./gradlew --offline :services:travel-core:test --tests io.travelos.travelcore.api.PurchaseApiIntegrationTest` | 6 run, 6 passed |
| `./gradlew --offline check --continue` (whole repository, after `spotlessApply`) | BUILD SUCCESSFUL in 3m 3s: audit 5, disruption 6, enterprise-context 16, learning 14, order 18, policy 41, supplier-gateway 26, trip-planning 65, travel-core 87, libs green; llm-gateway pytest 30/30 after its intent field-set guard learned the `preferences` message |
| `cd web && npm run check` | exit 0 (tsc + vite build; `QUOTED` added to the status union) |

What the tests prove (implemented and test-verified; suppliers simulated):

- Search never reserves: a `CONFIRM` trip is `QUOTED` with alternatives and books nothing until the
  purchase signal; a trip in `POLICY` mode without policy autonomy waits as well.
- Stale selections are unpurchasable: a re-selection or a higher re-quote supersedes the
  authorization; a confirmation naming another plan is refused; `BOOKING` without a covering
  authorization is refused by Travel Core with `PURCHASE_NOT_AUTHORIZED` and the workflow returns to
  the person.
- Repeated confirmation is one attempt: the same key or a second confirmation of a covered plan
  returns the same authorization; a repeated purchase signal yields one order; a second `BOOKING`
  move is the same state, not a second consumption.
- Policy-granted autonomy is recorded as a `POLICY_AUTONOMY` authorization with the policy decision
  and consumed at booking; the seed policy states it explicitly with a limit.
- Drafts plan nothing until submitted; conversations persist turns and plan through the same API.

Not verified here: the frontend has no confirm/select/refresh UI yet (Phase 11 handoff contract in
`api-contracts.md`); the sandbox suppliers' fare conditions are synthetic.

## Phase 4 — supplier integrations (ADR-0016)

| Command | Result |
|---|---|
| `./gradlew --offline :services:supplier-gateway:test` | 44 run, 42 passed, 2 skipped (the two `LiveSupplierSmokeTest` cases: no `DUFFEL_ACCESS_TOKEN` / `HOTELBEDS_API_KEY` in this environment); contract tests (Duffel 7, Hotelbeds 5), `MutationLedgerIntegrationTest` 4, existing sandbox suites |
| `./gradlew --offline :workflows:trip-planning:test` | 66 run, 66 passed (passenger from Enterprise Context; fallback to the snapshot) |
| `./gradlew --offline :services:enterprise-context:test --tests io.travelos.context.ProfileIntegrationTest` | 7 run, 7 passed (agent reads for BOOKING logged; other purposes refused) |
| `./gradlew --offline :services:order:test` | 19 run, 19 passed (unknown outcome → UNKNOWN item + `OUTCOME_UNKNOWN` exposure, order `PARTIALLY_FAILED`, events contract-valid) |
| `./gradlew --offline :libs:events:test` | 65 run, 65 passed (order event enums extended) |
| `./gradlew --offline check --continue` (whole repository, after `spotlessApply`) | BUILD SUCCESSFUL in 3m 23s; every module green (travel-core 87, trip-planning 66, supplier-gateway 42 + 2 skipped, policy 41, order 19, enterprise-context 17, learning 14, disruption 6, audit 5, libs, both Python suites 30 / 41) |

Status distinction for this phase, as the request demands it:

- **Implemented and test-verified**: mutation ledger (persist before side effects, ledger answers
  retries, key reuse refused, reconciliation by lookup, OUTCOME_UNKNOWN), Order's honest unknown
  item + exposure, passenger data at booking time (read, logged, never persisted), rail/car seams,
  coordinates and distances in the catalog, capability flags on the sandboxes.
- **Implemented, provider-test-blocked**: the Duffel and Hotelbeds adapters. Their contract tests
  run against scripted HTTP servers that follow the suppliers' documented v2 / 1.0 shapes; the
  credential-gated live smoke tests (search only, never a booking) exist and are skipped here
  because no `DUFFEL_ACCESS_TOKEN` / `HOTELBEDS_API_KEY` is available in this environment. No
  result in this repository is a live supplier result.
- **Absent**: rail and car adapters (simulated or live), Duffel webhooks, Duffel order changes.

## Phase 5 — finance ledger (ADR-0017)

| Command | Result |
|---|---|
| `./gradlew --offline :libs:events:test` | 75 run, 75 passed (topic `travel.finance`, 10 event types with examples) |
| `./gradlew --offline :services:order:test` | 35 run, 35 passed: `FinanceIntegrationTest` 8 (card number refused; authorize → capture with events and provider refs; decline fails before any supplier call; failed booking voids; cancellation refunds per item, credit kept as value; credits applied by Finance and swept on expiry; payables by settlement method and their settlement; reconciliation against the sandbox provider's ledger; EUR instrument with FX provenance), `StripePaymentProviderTest` 6 (scripted HTTP contract), existing 21 |
| `./gradlew --offline :services:learning:test` | 14 run, 14 passed (a `travel.finance.payment-refunded` event becomes the REFUND_SETTLED outcome with `recordedBy: finance-ledger`) |
| `./gradlew --offline :services:supplier-gateway:test` | 44 run, 42 passed, 2 skipped (sandbox airline now issues credits for non-refundable fares) |
| `./gradlew --offline check --continue` (whole repository, after `spotlessApply`) | BUILD SUCCESSFUL in 3m 33s; every module green (travel-core 87, trip-planning 66, supplier-gateway 42 + 2 skipped, policy 41, order 35, enterprise-context 17, learning 14, events 75, both Python suites) |

Status distinction: sandbox-payments flows are implemented and test-verified; Stripe is implemented
and provider-test-blocked (contract tests only; no `STRIPE_SECRET_KEY` in this environment; nothing
here is a live payment); invoice documents (PDF) and automatic credit application at suppliers are
absent.

## Phase 6 — servicing, partial cancellation, traveler requests, cases (ADR-0018)

| Command | Result |
|---|---|
| `./gradlew --offline :libs:events:test` | 83 run, 83 passed (topic `travel.assistance` with 5 event types; `travel.order.items-released`, `travel.trip.component-cancellation-requested`, `travel.trip.components-released`; `TRAVELER_REQUEST` disruption type and requested-window fields, all with examples) |
| `./gradlew --offline :services:order:test --tests '*OrderIntegrationTest'` | 20 run, 20 passed: `aPartialCancellationReleasesOnlyTheNamedComponentsAndKeepsTheOrder` (one hotel released and refunded per item, the flight untouched, the order CONFIRMED, a repeat releases nothing twice, a non-refundable component is CANCEL_FAILED with an exposure for a person, an unknown component is NOT_FOUND) |
| `./gradlew --offline :workflows:trip-planning:test --tests '*TripCancellationWorkflowTest'` | 10 run, 10 passed (3 Phase 6 cases: components reported CANCELLED with the trip left BOOKED; a refusal reported CANCEL_FAILED with its code; a trip that is not BOOKED left alone) |
| `./gradlew --offline :services:travel-core:test --tests '*ItineraryApiIntegrationTest'` | 7 run, 7 passed (`aComponentOfABookedTripIsReleasedOnItsOwnAndTheTripStaysBooked`: 404 for a stranger, 403 for a manager who only sees the trip, 202 CANCELLING for the traveler, one request event however often it is asked, the release reported and announced, the trip BOOKED throughout) |
| `./gradlew --offline :services:assistance:test` | 5 run, 5 passed (`AssistanceIntegrationTest`: an exposure is one case however often it is told and names the traveler; the traveler sees it read-only and a colleague or another tenant sees nothing; assignment, notes, WAITING, closure refused before resolution, the summary; settled by `travel.order.exposure-resolved`, then closed with the full history; a recovery approval case is CRITICAL, escalates by the sweep when overdue (2s SLA in the test profile), escalates by a person up to level 3, settles on `travel.disruption.resolved`; seven kinds of unfinished business open from seven events and four settle from the platform's own facts; a traveler opens a request on her own trip only, idempotently, and a SAFETY case is CRITICAL; every `travel.assistance.*` event contract-valid) |
| `./gradlew --offline :services:disruption:test` | 7 run, 7 passed (`aTravelerAsksToMoveHerFlightAndTheRequestBecomesARecoveryWithHerAsTheActor`: 404 for someone else's order, 201 IMPACT_CONFIRMED with the requested window and requester, the same key is the same disruption, past windows and unknown components refused, the contract-valid `impact-confirmed` event keyed by the trip) |
| `./gradlew --offline :workflows:trip-planning:test --tests '*RecoveryWorkflowTest'` | 17 run, 17 passed (`aTravelerRequestSearchesTheAskedWindowAndPolicyJudgesThePersonNotTheAgent`: the search window is the requested one, policy is asked with the HUMAN requester as the actor against the retimed intent, the order change stays the agent's single mutation, the decision record names the request) |
| `./gradlew --offline check --continue` (whole repository, after `spotlessApply`, Docker stack down) | BUILD SUCCESSFUL in 4m 12s; every module green (travel-core 88, trip-planning 70, supplier-gateway 42 + 2 skipped, policy 41, order 36, enterprise-context 17, learning 14, disruption 7, assistance 5, events 83, both Python suites) |

Status distinction: partial cancellation, traveler-requested changes and cases are implemented and
test-verified against the SIMULATED suppliers and payment provider; nothing here was exercised
against a live supplier. Not implemented: per-passenger changes (single-traveler trip model),
notifications of case changes to people (Phase 8), safety advisories and acknowledgements (Phase 8).

## Phase 7 — governance (ADR-0019)

| Command | Result |
|---|---|
| `./gradlew --offline :libs:events:test` | 85 run, 85 passed (`travel.approval.escalated/expired`, approval step fields, `APPROVAL_ESCALATED` case kind, all with examples) |
| `./gradlew --offline :services:policy:test` | 48 run, 48 passed: `GovernanceIntegrationTest` 4 (scope precedence project > cost center > tenant default and un-assignment; approval chain `[MANAGER, FINANCE above 200000]` with 24h expiry from the document; a hard budget denies what does not fit, two trips racing for the last of it from two threads: exactly one RESERVED, one EXCEEDED, the same trip again is the same reservation, `travel.trip.cancelled` releases and `travel.trip.booked` commits at what it cost, NO_BUDGET for an unfunded scope, another tenant's admin sees nothing; agreements steer GetGovernance and a non-preferred hotel needs a travel admin under a strict policy, nothing under the default), `PolicyEngineTest.Governance` 3, existing 41 |
| `./gradlew --offline :workflows:trip-planning:test` | 70 run, 70 passed (scope, chain, budget reservation and negotiated rates are null-safe when policy cannot answer) |
| `./gradlew --offline :services:travel-core:test` | 91 run, 91 passed: `ApprovalChainIntegrationTest` 3 (a chain decided step by step with Finance refused on the manager's step and vice versa, the workflow signalled once by the last step, two requested and two approved events with `finalStep`; a delegate of the manager decides in his name with `onBehalfOf`, sees the trip only while the delegation lasts, a traveler cannot delegate; an unanswered step escalates to TRAVEL_ADMIN after its expiry and expires as a rejection by `service/travel-core` after the next, signalled once, every event contract-valid), existing 88 |
| `./gradlew --offline :services:enterprise-context:test` | 23 run, 23 passed: `ScimIntegrationTest` 1 (no token / wrong token / a person's JWT refused; Okta create with enterprise extension; uniqueness conflict in the SCIM error shape; filters by userName and externalId; another tenant's token sees nothing; Entra patch of manager and displayName; deactivation by patch runs the platform's offboarding; PUT reactivates; DELETE deactivates and keeps the record), `LiveSourcesContractTest` 5 (Workday RaaS under basic auth with revoked = final and 503 = retry; Google service-account JWT bearer, sync token per user, 410 restarts a user; Microsoft Graph client credentials, delta links, 403 = revoked; Salesforce SOQL since the watermark with nextRecordsUrl and 429 = retry; Concur refresh token and offset paging), existing 17 |
| `./gradlew --offline :services:assistance:test` | 5 run, 5 passed |
| `./gradlew --offline check --continue` (whole repository, after `spotlessApply`, Docker stack down) | BUILD SUCCESSFUL in 4m 38s; every module green (travel-core 91, trip-planning 70, policy 48, supplier-gateway 42 + 2 skipped, order 36, enterprise-context 23, learning 14, disruption 7, assistance 5, events 85, both Python suites) |
| `bash -n deploy/keycloak/federate.sh` | syntax OK (the script itself needs a customer IdP and a running Keycloak to exercise) |

Status distinction: scoped policies, approval chains, delegation, expiry/escalation, budgets and
agreements are implemented and test-verified; SCIM provisioning is verified end to end against the
shapes Okta and Entra ID send but no real IdP cycle ran; federation is a Keycloak broker script
(syntax-checked, not exercised); the five enterprise adapters are contract-tested against scripted
provider responses and have not been run against any live tenant (no credentials here). Nothing in
this phase claims a live verification it did not perform.

## Phase 8 — notifications, safety, itinerary export (ADR-0020)

| Command | Result |
|---|---|
| `./gradlew --offline :libs:events:test` | 88 run, 88 passed (`travel.assistance.notification-sent / advisory-issued / checkin-recorded`; journey and contact fields on `travel.trip.created/booked` and `travel.approval.requested`) |
| `./gradlew --offline :services:assistance:test` | 9 run, 9 passed: `NotificationsAndSafetyIntegrationTest` 4 (a trip's story reaches the traveler once in the inbox and by email through a recording channel, the same event again adds nothing, a colleague sees nothing, deliveries per channel with masked addresses, read state per person; the allocation's manager is addressed directly and a role step reaches everyone with the role, the traveler is not told about internal steps, email switched off per category stays in-app only, in-app cannot be switched off, a provider outage is retried then given up and visible to a travel admin; an advisory finds the booked traveler in its city and window and not the cancelled or Boston trips, tells them as CRITICAL safety, a stranger sees no advisory, the silent traveler becomes a SAFETY case when the 2s test grace ends, a SAFE check-in settles it, NEEDS_HELP opens one at once; every `travel.assistance.*` event contract-valid), existing 5 |
| `./gradlew --offline :services:travel-core:test --tests '*ItineraryApiIntegrationTest'` | 8 run, 8 passed (`theItineraryExportsAsACalendarAndAsJson…`: 7 VEVENTs for 3 legs, 2 stays, 2 transfers; the confirmed leg's supplier reference and STATUS; all-day stays; lines folded at 75 octets; the JSON summary; 404 for another tenant) |
| `./gradlew --offline check --continue` (whole repository, after `spotlessApply`, Docker stack down) | BUILD SUCCESSFUL in 4m 59s; every module green (travel-core 92, trip-planning 70, policy 48, supplier-gateway 42 + 2 skipped, order 36, enterprise-context 23, learning 14, assistance 9, disruption 7, events 88, both Python suites) |

Status distinction: in-app notifications, preferences, deliveries with retry, safety advisories,
check-ins and the itinerary export are implemented and test-verified; email (SendGrid) and chat
(Slack webhook) channels are implemented and provider-test-blocked (no key or webhook in this
repository; the tests prove the delivery machinery through a recording channel, not a provider).

## Phase 9 — reporting (ADR-0021)

| Command | Result |
|---|---|
| `./gradlew --offline :libs:events:test` | 88 run, 88 passed (`allocation` on `travel.trip.created/booked`) |
| `./gradlew --offline :services:audit:test` | 6 run, 6 passed: `reportsSumWhatTheEventsSaidByCostCenterAndCountTheExceptions` (a trip's life from contract examples with an allocation, booked 1200.00, captured, 200.00 refunded, a disruption recovered for 73.00 more, a policy violation, an approval requested, escalated and approved, a case opened and resolved: spend by cost center and by project with net = captured + increment − refund, CSV export byte-exact, a traveler and a manager refused, an unknown grouping 422, outcomes with the median time to book and the autonomous recovery share, exceptions with violation reasons, approval and case durations, suppliers, an inverted period 422), existing 5 |
| `./gradlew --offline :services:travel-core:test --tests '*TripLifecycleIntegrationTest' --tests '*TripArrangerIntegrationTest'` | 19 run, 19 passed (events with the allocation stay contract-valid) |
| `./gradlew --offline check --continue` (whole repository, after `spotlessApply`, Docker stack down) | BUILD SUCCESSFUL in 4m 38s; every module green (travel-core 92, trip-planning 70, policy 48, supplier-gateway 42 + 2 skipped, order 36, enterprise-context 23, learning 14, assistance 9, disruption 7, audit 6, events 88, both Python suites) |

Status distinction: reports are implemented and test-verified from contract-example events; they
are exactly as complete as the events the services emit (nothing is estimated or converted).

## Phase 10 — performance measurement and improvement

| Command | Result |
|---|---|
| `make images && make stack-up` (local Docker stack; the pre-existing Postgres volume needed the `assistance` role and database created by hand, as `platform/local/postgres/init/01-databases.sql` does on a fresh volume) | 21 containers healthy |
| `python3 perf/load.py --label before-236d2d2 --samples 20 --concurrency 10 --burst 25` | `docs/program/performance/before-236d2d2.{json,md}`: create p95 26 ms, detail p95 7.7 ms, inbox p95 6.9 ms, spend report p95 18 ms, end-to-end to BOOKED p50 1.1 s / p95 1.6 s, 20/20 BOOKED; 10 concurrent users all BOOKED in 2.2 s; burst 25/25 accepted at 187 req/s |
| change: `libs/spring-outbox` after-commit nudge (`JdbcOutbox` transaction synchronization, `OutboxPublisher.nudge()` on one coalescing relay thread; poll kept as the safety net); images rebuilt; `make stack-up` | recreated containers run the rebuilt images |
| `python3 perf/load.py --label after-236d2d2-outbox-nudge --samples 20 --concurrency 10 --burst 25` | `docs/program/performance/after-236d2d2-outbox-nudge.{json,md}`: end-to-end p50 0.6 s (was 1.1 s), p95 1.6 s (unchanged: a 1.6 s mode in 5 of 20 samples, recorded as an open finding), 20/20 BOOKED; REST latencies unchanged within noise; 10 concurrent users all BOOKED in 2.2 s; burst 25/25 at 273 req/s |
| `./gradlew --offline :libs:spring-outbox:test` (stack down) | 6 run, 6 passed: `OutboxNudgeIntegrationTest` 2 (a committed append reaches Kafka within milliseconds with the poll set to 30 s; a rolled-back append publishes nothing), existing 4 |
| `make stack-down` | stack stopped before the test suites (Docker memory rule) |
| `./gradlew --offline check --continue` (whole repository, after `spotlessApply`, Docker stack down) | BUILD SUCCESSFUL in 5m 09s; every module green (travel-core 92, trip-planning 70, policy 48, supplier-gateway 42 + 2 skipped, order 36, enterprise-context 23, learning 14, assistance 9, disruption 7, audit 6, spring-outbox 6, events 88, both Python suites) |

Status distinction: the measurement is real and repeatable on this laptop's Docker stack with the
SIMULATED suppliers; it says nothing about live suppliers, a cluster, or more than ten concurrent
users. The improvement is verified by before/after runs and by an integration test; the remaining
p95 mode is an open finding, not a fix.

## Phase 11 — verification, production configuration, runbooks, hand-off

| Deliverable | Where |
|---|---|
| Capability matrix with evidence and the implemented / provider-test-verified / live-verified / blocked distinction | `docs/program/capability-matrix.md` |
| Every command and result, phase by phase | this file |
| API contracts for the frontend, per phase | `docs/program/api-contracts.md`, `docs/program/frontend-handoff.md` |
| Decisions | `docs/adr/0014` … `0021` |
| Production configuration reference (every variable, every secret, where each environment gets it, migrations, scheduled work) | `docs/program/production-config.md` |
| Go-live checklist, daily operations, alerts, replay, rollback, offboarding | `docs/runbooks/go-live.md` (with `kubernetes.md`, `supplier-credentials.md`, `enterprise-federation.md`) |
| Remaining external dependencies and what unblocks each | `docs/program/external-dependencies.md` |
| Performance findings with before/after evidence | `docs/program/performance.md` |

## CI on the branch (`platform/complete-backend`)

The workflows run on pushes to `main` and on pull requests; `ci.yml` gained `workflow_dispatch` so
the branch could be checked without opening a pull request (`gh workflow run ci.yml --ref
platform/complete-backend`, `gh workflow run kind-e2e.yml --ref platform/complete-backend`).

| Run | Result |
|---|---|
| `ci` at `49e6984` (run 36051534449) | FAILED in `gradle check`: `OutboxIntegrationTest.committedEventsReachKafkaInOrderWithTheCorrelationKey` saw seq 4 before seq 3 (web app, helm, terraform jobs green). Cause: the Phase 10 after-commit nudge relayed on its own thread while the scheduled poll relayed too; each `SKIP LOCKED` select took part of the same five-row backlog and published it in its own order. Never reproduced in six local runs; a race. |
| `kind-e2e` at `49e6984` (run 36051537780) | FAILED in the browser E2E: `cancellation.spec.ts` "a supplier that refuses keeps the trip Cancelling until Finance resolves the exposure" — the air legs were not `CANCELLED` (19 of 20 specs passed). Cause: the whole-order and partial release loops in `OrderService` sent every item's supplier cancellation under the command's idempotency key; the Phase 4 mutation ledger in the gateway refuses "same key, different request" (`IDEMPOTENCY_KEY_REUSED`), so after the LAX room's refusal each air leg was refused too. The Order tests never saw it because `FakeSupplierGateway` had no ledger. |
| fix | `OutboxPublisher.relay()` takes a lock shared by the poll and the nudge (per-key order within an instance; the cross-replica case is documented in `production-config.md`). `OrderService` keys each release `<command key>:<item id>` (a retry of the command repeats the keys and is answered from the ledger; a new command asks the supplier again). `FakeSupplierGateway` now applies the ledger's reuse rule. |
| `./gradlew --offline :services:order:test` with the fake's rule and without the saga fix | 36 run, 33 passed, 3 failed (`IDEMPOTENCY_KEY_REUSED` on every multi-item release: the rule reproduces the kind failure) |
| `./gradlew --offline :libs:spring-outbox:test :services:order:test` with both fixes | order 36 run, 36 passed; spring-outbox 6 run, 6 passed; the outbox suite repeated three more times, green each time |
