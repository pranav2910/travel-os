# Repair run harness — qa-20260923-repair-cda5ccb

The audit `qa/qa-20260923-cda5ccb` (commit cda5ccb) is preserved untouched. This run re-executes
the affected checks against the repaired code, in this directory, with its own `results.csv` and
`evidence/`. The scripts are copies of the audit's harness with expectations updated only where the
contract changed on purpose (each change is a comment in the script and a line in
`../final-report.md`); no assertion was weakened.

## Phases

1. **before** — the audited images (`cda5ccb`, tags `:local` as built for the audit) with the
   stack's preserved volumes: `python3 repair_regressions.py --phase before` reproduces every
   finding as `<BUG>-<n>-before` rows (expected: FAIL).
2. **after** — the repaired images (`make images && make stack-up`): `python3 repair_regressions.py
   --phase after` (`<BUG>-<n>-after` rows; each supersedes its `-before` row in the summary), then
   the suites below.

## Suites run in the after phase (from this folder)

| Suite | Command | Why it is rerun |
|---|---|---|
| Targeted regressions | `python3 repair_regressions.py --phase after` | one row per defect, application + order + supplier state |
| P0 smoke | `python3 p01_followup.py && (cd ../../../web && NODE_PATH=$PWD/node_modules node ../qa/qa-20260923-repair-cda5ccb/harness/p01_journey.js)` | the audit's P0 smoke |
| Faults at every stage | `python3 p01_failures.py` | workflow code changed (cancel signal, pre-booking checks) |
| Trips & lifecycle | `python3 p03_trips.py` | validation, idempotency race, cancellation, list limits |
| Routes & dates | `python3 p04_routes.py` | R20–R26 and the whole matrix (catalog and clock checks touch every create) |
| Suppliers, policy, money | `python3 p05_policy_money.py && python3 p05_followup.py` | refunds, exposures, LAX refusal |
| Booking styles | `python3 p06_booking_styles.py` | metro/catalog rows |
| Workflow & idempotency | `python3 p09_workflow.py` | cancel/booking race, integrity |
| Auth sessions (browser) | `node p02_session.js` | the edge now sends a CSP: sign-in, silent renew and logout must still work |
| Frontend | `node p10_frontend.js && node p10b_keyboard.js` | contrast (BUG-13), states incl. Cancelling |
| API & security | `python3 p11_security.py` | headers (BUG-09) |
| Outages & restart | `python3 p12_outages.py` (alone) | outage documents (BUG-14), recovery paths |
| Browser E2E (repository suite) | `cd ../../../ && make web-e2e` | critical journeys incl. `web/e2e/cancellation.spec.ts` |
| Ledger & summary | `python3 report.py` | rebuilds `results-summary.md/json` |
| Second pass (`run-after-2.sh`) | browser suites, `p11_security.py`, `P4_ONLY=R04`, `P6_ONLY=P6-ML-04`, `repair_extra.py`, `reeval.py` | after the edge fix found by the first pass (the CSP blocked the silent-renew iframe from landing on the app origin; the outage probe timed out on nginx's 60 s connect timeout) |
| Follow-up rows | `python3 repair_extra2.py` | after the first pass found a GBP trip accepted and then failed at OPTIMIZATION with an internal error: USD is the only trip currency, component reasons are bounded |
| Frontend rows | `python3 p10_record.py` | turns `p10-frontend.json` / `p10b-keyboard.json` into the ten P10 ledger rows (the audit did this step by hand) |

Harness fixes made in this copy (each a comment in the script): `p04_routes.py` applied a case's own windows
after the defaults (the audit's R04 was never a real test); `p06_booking_styles.py` lets a manager approve a
style that crosses the policy line so the style is judged on its booking; `p10_frontend.js` waits for the
sign-in button before tabbing and opens the trip page by its link's href; `repair_regressions.py`'s
concurrency key had spaces on its first run (refused as malformed, re-run as `bug07_before.py`).

Not rerun (unchanged code, unchanged verdict): P2 API auth negatives (`p02_auth.py`), P7 agent
phrasings, P13 load. Their audit results stand and are cited, not re-counted.

Conventions, waits, teardown: as in the audit's README (run-tagged trips remain as evidence; the
seed policy is restored after every mutation; no volume is deleted).
