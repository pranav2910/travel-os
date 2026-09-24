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
