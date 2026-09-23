# QA harness — how to run, what each suite proves, teardown

Test-only code. It never modifies product code, it tags every resource it creates with the run id
(`qa-20260923-cda5ccb`), it mutates only run-tagged resources, and it never deletes volumes.

## Prerequisites

- The Docker stack up and healthy: `make images && make stack-up` (from the repository root).
- Python 3.12+ (standard library only); Node 22+ with the web app's dependencies installed (`make web-install`) for the browser suites; Playwright browsers Chromium and WebKit installed (`npx playwright install chromium webkit` inside `web/`). Firefox is not installed here.
- Environment placeholders (defaults in brackets): `APP_URL` [http://localhost:8080], `KC_URL` [http://localhost:8180], `SUPPLIER_URL` [http://localhost:8084], `CORE_URL` [http://localhost:8081], `QA_PASSWORD` [the realm's seeded dev password; never commit a real one].
- The realm's five seeded users. On kind use the 180xx ports and `E2E_WEBHOOK_SECRET` from `deploy/kind/.secrets.env`.

## Suites (run from this folder)

| Suite | Command | Layer | Proves |
|---|---|---|---|
| P0 smoke (fast) | `python3 p01_followup.py && (cd ../../../web && NODE_PATH=$PWD/node_modules node ../qa/qa-20260923-cda5ccb/harness/p01_journey.js)` | browser + API | sign-in, one booked trip persisted through reload/re-login, cancel-while-planning, policy denial, arranged trips |
| Faults at every stage | `python3 p01_failures.py` | API + short container faults | supplier outage recovery, lost booking answer reconciled, refused component compensated, worker restart, rejection |
| Auth & isolation | `python3 p02_auth.py` and `node p02_session.js` | API + browser | token negatives, cross-tenant/peer 404s, role matrix, spoofing, cache isolation, sessions |
| Trips & lifecycle | `python3 p03_trips.py` | API | validation, lists, idempotency (sequential + concurrent), cancellation, completion |
| Routes & dates | `python3 p04_routes.py` | API + workflow | R01–R30, D01–D07, instant ordering |
| Suppliers, policy, money | `python3 p05_policy_money.py && python3 p05_followup.py` | API + fixtures | sandbox fault fixtures, one-cent policy boundaries, versions, line-item sums, refunds, exposures |
| Booking styles | `python3 p06_booking_styles.py` | API + workflow | supported styles and structures; unsupported ones recorded |
| Agent (free text) | `python3 p07_agent.py` | API | one-shot extraction behaviour; no conversational agent |
| Workflow & idempotency | `python3 p09_workflow.py` | API + Temporal CLI + DB read-only | keys across tenants/users, completed-workflow protection, approval race, cancel race, integrity |
| Frontend | `node p10_frontend.js && node p10b_keyboard.js` | browser (Chromium, WebKit, axe) | a11y, states, offline/slow/retry, responsive widths, keyboard-only journey, stored-HTML rendering |
| API & security | `python3 p11_security.py` | API + HTTP | contract probes, allowlist, headers/CORS, redirect allowlist, bundle/log secrets |
| Outages & restart | `python3 p12_outages.py` | container faults + full restart | one outage at a time, recovery, volume-preserving restart, observability trace |
| Load & concurrency | `python3 p13_load.py` | API threads | latency percentiles, 10 users, 100 trips, stale-state, rapid searches |
| Ledger & summary | `python3 report.py` | — | rebuilds `results-summary.md/json` from `results.csv` |

Node scripts need `NODE_PATH=<repo>/web/node_modules` when run from this folder.

## Conventions

- Every result is one row in `results.csv` with an evidence file under `evidence/`; rows are appended, never rewritten. A corrected evaluation is a new row whose id ends in `b`; `report.py` treats the original as superseded but keeps it.
- Waits are condition-based and bounded (`qa.poll`, `qa.wait_trip`); no test sleeps to prove completion.
- Policy-seeding tests restore the seed policy in `finally`. Do not run two policy-seeding suites at the same time.
- The outage suite must run alone.
- Flaky reruns: rerun the suite; the first failure stays in the ledger.

## Teardown

Run-tagged trips remain in the stack's database on purpose (evidence). To remove them, cancel through the API (`POST /api/v1/trips/{id}/cancellation`) for trips whose purpose starts with the run id; there is no delete. The published policy is the seed document (`make seed-policy` restores it). No volumes are touched.

## Promotion to the repository's suites

The scenarios worth keeping permanently: BUG-08 (cancel leaves the reservation) as a Travel Core/Order integration test; BUG-01/03 as `TravelIntent` validation unit tests; the concurrent idempotency race (BUG-07) as a `TripApiIntegrationTest` case; the one-cent policy boundary as a policy unit test; the keyboard journey and the stored-HTML rendering as `web/e2e` specs.
