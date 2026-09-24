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
