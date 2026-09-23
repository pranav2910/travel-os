# TravelOS QA audit — final report (qa-20260923-cda5ccb)

Scope actually exercised: the travel-os sandbox platform at commit `cda5ccb` on the local Docker stack (21 containers, sandbox suppliers and sandbox enterprise sources only), through the web app at http://localhost:8080, the HTTP APIs behind the same edge, the Temporal CLI, the stack's Postgres (read-only) and the Kafka broker. Prompts 0–15 were executed in order; every claim below points at a row in `results.csv` and a file in `evidence/`. Product code was not modified during the audit.

## 1. Executive result: NOT READY for a controlled sandbox pilot

*Correction (2026-09-23): this section originally said "one launch-blocking defect" while listing three P0 bugs; all three are launch-blocking and the verdict stays NOT READY until each is fixed or its severity is explicitly reassessed.*

Three proven launch-blocking defects stand. The most serious: **a traveler who cancels a booked trip gets "Cancelled" while the reservation stays confirmed at the supplier** (BUG-08; P3-LIFE-01b, P9-RACE-02). That violates the gate's "truthful booking status" and, with a live supplier, would spend money on trips nobody takes. Two further P0 validation gaps let nonsense requests become sandbox bookings: any three-letter string is accepted as an airport (BUG-01) and departure dates in the past book (BUG-03).

Everything else on the critical list held: real sign-in (PKCE, tokens in memory, expiry at 900 s, revocation lag measured), tenant and traveler isolation (404 everywhere, no disclosure, no cache leakage), authorization (self-approval and role restrictions refused server-side), persistence through reload and re-login, duplicate-booking prevention (one trip for five concurrent identical requests; one order after worker restarts, supplier outages and lost booking answers), workflow recovery, hard-policy enforcement to the cent, refresh recovery, and sandbox clarity on every screen.

Path to READY: fix BUG-08 (cancellation must cancel the order or state that a person must), add location and past-date validation (BUG-01, BUG-03), then rerun the P0 smoke (`harness/README.md`) plus P3-LIFE-01b, P9-RACE-02, P4-R20..R26. No other P0 finding is open.

Passing these sandbox tests says nothing about real-money bookings: no live provider, payment or email path exists.

## 2. Counts

Effective results after supersession (a corrected evaluation is a new row; the original is kept and excluded from counts): **211 tests: 174 PASS, 17 FAIL, 4 BLOCKED, 14 NOT_IMPLEMENTED, 2 NOT_APPLICABLE.** Executed (PASS+FAIL) 191; executed pass rate 91 %. Full tables by prompt, priority and layer: `results-summary.md`.

| priority | PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total |
|---|---|---|---|---|---|---|
| P0 | 67 | 3 | 0 | 0 | 0 | 70 |
| P1 | 92 | 10 | 2 | 11 | 2 | 117 |
| P2 | 15 | 4 | 2 | 3 | 0 | 24 |

Layers: 162 API/integration, 15 browser (Chromium; WebKit smoke), 10 service-fault/restart, 8 database or container inspection, the rest inventory and cross-reference rows. 34 superseded rows and 5 note rows remain in `results.csv` for the record.

## 3. Bugs (details, steps and evidence in `bugs.md`)

P0: BUG-08 cancelled trip keeps its confirmed reservation · BUG-01 any code books as an airport · BUG-03 past dates book.
P1: BUG-07 one of five concurrent identical requests answers 500 (one trip is still created) · BUG-05 unknown currency reaches the optimizer and surfaces a Java exception name as the failure code · BUG-11 a refund of zero is accepted and ledgered · BUG-04 trips arranged for another traveler carry an empty traveler snapshot.
P2: BUG-06 negative list limit silently clamped · BUG-09 no CSP/nosniff/frame-options headers on the edge · BUG-10 itineraries know 24 airports while round trips accept any code · BUG-12 refund currency mismatch not flagged · BUG-13 one contrast failure (4.26:1) on the trip page's skipped step · BUG-14 an upstream outage returns nginx's HTML 502 instead of a problem document.
Risks/requirement gaps: RISK-01 revocation lag = token lifetime (15 min) · RISK-02 no plan-only mode (free text books) · RISK-03 no cabin/airline/traveler-count fields · RISK-04 no booking horizon or duration limit · RISK-05 no cursor pagination (lists clamp at 200).

## 4. Ledgers and matrices

- Source-to-test coverage: `coverage-ledger.md` (38 checklist sections, 30 booking methods, 15 agent flows, route/date rows, browser/device coverage).
- Route/date matrix: `results.csv` rows `P4-R01..R30`, `P4-D01..D07`, `P4-*-ORDER` (evidence `p4-*.json`). Pairwise generation across travelers/preferences/mode was not produced because those dimensions do not exist in the product; coverage is by category.
- Agent conversation matrix: `P7-*` (13 single-shot phrasings executed with the deterministic fake extractor; the 60 conversations are NOT_IMPLEMENTED because no conversational agent exists).
- Booking side-effect counts (fault → recovered state → supplier side effects):

| fault point | test | recovered state | orders / side effects |
|---|---|---|---|
| search outage (ZZZ) | P1-FAIL-01 | FAILED NO_OFFERS | 0 |
| cancel while planning | P1-FAIL-01c | CANCELLED | 0 |
| supplier gateway down 25 s mid-plan | P1-FAIL-02 | BOOKED | 1 |
| booking committed, answer lost (DEN hotel) | P1-FAIL-03 | BOOKED, hotel CONFIRMED once | 1 |
| refused component (AUS hotel) | P1-FAIL-04 | FAILED, flights CANCELLED | 0 live |
| worker restarted 0.8 s after submit | P1-FAIL-05 | BOOKED | 1 |
| refused transfer + hotel refuses cancel (LAX) | P5-SUP-05b | FAILED COMPENSATION_INCOMPLETE, 1 OPEN exposure resolved by Finance | 1 exposure |
| five concurrent identical submits | P3-MUT-02 | 1 trip | 1 (one 500 answer: BUG-07) |
| Temporal down at submit | P12-OUT-04/04b | BOOKED after recovery | 1 |
| Postgres down 15 s | P12-OUT-05 | recovered, next trip BOOKED | 1 |
| optimizer down mid-plan | P12-OUT-06/06b | BOOKED | 1 |
| full stack restart | P12-RESTART-01 | 183/183 run trips present, 13 topics | 0 new |
| cancel after approval / after booking | P9-RACE-02, P3-LIFE-01b | CANCELLED but order CONFIRMED | 1 live (BUG-08) |

- Browser/device coverage: Chromium 153 (all suites, widths 320–1920, tablet landscape, 200 % zoom, keyboard-only journey, axe on 10 routes: clean except BUG-13); WebKit 26.6 smoke (repository spec green in CI for this commit); Firefox, real Safari/Chrome/Edge, private mode, real phones, screen readers: BLOCKED.
- Performance (warm, sequential, local Docker; no SLOs exist): login p95 101 ms, list p95 9 ms, create p95 15 ms, detail p95 4 ms, submit-to-BOOKED p95 1.5 s; 10 concurrent users all booked (wall 16.7 s, the two approvers' trips waited on nothing but the worker); 100 trips accepted in 0.2 s and all booked within 10 s; list of 400 requested answered in 10 ms but clamped to 200 rows. Proposed thresholds are in P13-LAT-01. 1,000 trips and 50/100 users: BLOCKED (no isolated load environment).

## 5. Commands, artifacts, cleanup, blockers

- Reproduce: `harness/README.md` lists every suite and its command; the P0 smoke is the first row. Evidence index: `evidence/` (216 files, 5.1 MB: JSON per test, screenshots `p01-*.png`, `p02-*.png`, `p10-*.png`).
- Cleanup status: 183+ run-tagged trips (purpose prefix `qa-20260923-cda5ccb`) remain in the local database as evidence; nothing outside the run was modified; the seed policy was re-published after every mutation (current default is the seed document at a higher version number); the stack was restarted once with volumes preserved and is healthy now; no volume was deleted.
- Blockers that need a decision or access: Firefox (a browser download), real devices and screen readers, a disposable second stack (memory) or permission to erase local data for fresh-database startup, an isolated load environment, a wire-level supplier fault seam (product change), and a product decision on plan-only/agent scope (Prompts 7/8).

## 6. What was not done

No pairwise matrix generator; no 60-conversation agent run (no agent); no real-money or live-provider check; no human usability observation; Tempo trace search by trip tag returned nothing (the audit trail proved one correlation id across 120 events; the trace-store query needs the correct tag name and is left open).
