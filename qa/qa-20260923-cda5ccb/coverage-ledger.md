# Coverage ledger — qa-20260923-cda5ccb

Every checklist family, booking style, agent flow and route/date row maps to test ids in `results.csv`. Equivalent tests are deduplicated but keep their source mappings. Statuses are the effective ones after supersession (see `results-summary.md`).

## Original checklist sections

| # | Section | Tests | Outcome |
|---|---|---|---|
| 1 | Authentication | P2-TOK-01, P2-TOK-02, P2-TOK-03b, P2-SES-01..05, P11-REDIR-01, P11-TOKEN-01 | PASS; RISK-01 (revocation lag) |
| 2 | Authorization / isolation | P2-ISO-01..05, P2-ROLE-01, P2-SPOOF-01, P2-CACHE-01, P1-FAIL-07, P5-EXP-01, P9-IDEM-01/02 | PASS |
| 3 | First-time experience | P1-E2E-01, P1-PERSONA-03, P10 keyboard/empty states | PASS (heuristic; no human study) |
| 4 | Create | P3-VAL-01..18, P3-ITN-01..05, P1-PERSONA-02 | FAIL: BUG-01, BUG-03, BUG-05 |
| 5 | List | P3-LIST-01..07, P13-VOL-01 | FAIL P2: BUG-06; RISK-05 |
| 6 | Details | P3-DET-01..03, P1-E2E-01 | PASS |
| 7 | Edit | P3-EDIT-01, P6-ML-EDIT | NOT_IMPLEMENTED |
| 8 | Delete | P3-EDIT-01, P3-LIFE-01b (cancel) | NOT_IMPLEMENTED (delete); FAIL: BUG-08 (cancel) |
| 9 | Travelers | P3-TRAV-01, P1-PERSONA-01b | NOT_IMPLEMENTED; BUG-04 |
| 10 | Providers | P5-SUP-01..08, P1-FAIL-02/03/04 | PASS; wire-level faults BLOCKED |
| 11 | Optimization | P5-OPT-01, P6-S05/S06, P3-ITN-04 | PASS (bounded oracle) |
| 12 | Policy | P5-POL-01..08, P5-POL-HIST, P5-POL-ENF, P1-FAIL-06b, P1-PERSONA-03 | PASS (one-cent boundary exact) |
| 13 | Sandbox booking | P1-E2E-01, P6-*, P5-SUP-02b, P5-SUP-06 | PASS where implemented; 7 styles NOT_IMPLEMENTED |
| 14 | Temporal | P9-DISC-01, P9-RETRY-01, P1-FAIL-05, P12-OUT-04/04b, P12-OUT-06/06b | PASS |
| 15 | Data integrity | P9-INTEG-01, P3-MUT-01..03, P9-IDEM-01/02 | PASS; BUG-07 |
| 16 | Concurrent users | P13-CONC-01, P9-RACE-01, P9-RACE-02 | PASS / FAIL (BUG-08 via race) |
| 17 | Network | P10 offline submit, P1-FAIL-02 | PASS |
| 18 | Refresh | P1-E2E-01, P2-SES-02, P10 back/forward | PASS |
| 19 | Navigation | P2-SES-04, P10 back/forward | PASS |
| 20 | Browsers | P10 engines (Chromium, WebKit); Firefox/real Safari/Chrome/Edge/private | BLOCKED (not installed / not automatable) |
| 21 | Screens | P10 responsive (320..1920, tablet landscape, 200 % zoom) | see results |
| 22 | Accessibility | P10 a11y (axe on 9 pages + detail), keyboard journey, dialog focus | axe clean; screen reader BLOCKED |
| 23 | Misuse | P3-VAL-06/07/18, P11-XSS-01, P7-INJ-01, P2-SPOOF-01 | PASS |
| 24 | Security | P2-TOK-*, P11-HDR-01, P11-SECRETS-01b, P11-REDIR-01 | PASS; BUG-09 (headers) |
| 25 | API validation | P11-API-01..15 | PASS |
| 26 | Date/time | P4-R05, R10, R14..R17, R24..R28, D01..D07, P4-*-ORDER | PASS; BUG-03 (past) |
| 27 | Currency | P5-MONEY-01, P5-MONEY-*, P4-R09/R09b, P3-ITN-03b | PASS core; BUG-05, BUG-11, BUG-12 |
| 28 | Errors | P10 tripsApiDown, P11-API-* problem documents | PASS |
| 29 | Loading | P10 slowLoading | see results |
| 30 | Empty states | P10 states, P1-PERSONA-03 | PASS |
| 31 | Volume | P13-VOL-01, P13-VOL-02 | PASS (100); 1,000 BLOCKED |
| 32 | Outages | P12-OUT-01..06b | see results |
| 33 | Docker/restarts | P12-RESTART-01, P12-FRESH-01 | see results; fresh stack BLOCKED |
| 34 | Observability | P12-OBS-01 | see results |
| 35 | Performance | P13-LAT-01 | measurements only (no SLOs) |
| 36 | User confusion | P1-PERSONA-02/03, P7-FT-02 | PASS |
| 37 | Personas | P1-PERSONA-01b/02/03, P1-FAIL-07 (bob, dan), P5-EXP-01 (carol) | PASS |
| 38 | Full journey | P1-E2E-01 | PASS |

## 30 booking methods (Prompt 6 styles + ticket structures)

| # | Style | Test | Outcome |
|---|---|---|---|
| 1 | search, select, confirm | P1-E2E-01 | PASS (the platform selects) |
| 2 | compare five, choose the third | P6-S02 | NOT_IMPLEMENTED |
| 3 | cheapest eligible | P6-S03 | NOT_IMPLEMENTED (weighted criterion) |
| 4 | shortest journey | P6-S04 | NOT_IMPLEMENTED |
| 5 | departure after 18:00 | P6-S05 | PASS (window) |
| 6 | arrival before 09:00 | P6-S06 | PASS (honest NO_FEASIBLE) |
| 7 | specific airline | P6-S07 | NOT_IMPLEMENTED |
| 8 | JFK only vs any NYC | P6-S08 | NOT_IMPLEMENTED (metro code accepted verbatim: BUG-01) |
| 9 | nonstop vs one stop | P6-S09 | NOT_IMPLEMENTED (policy-owned) |
| 10 | one way | P6-S10 | PASS |
| 11 | round trip | P6-S11 | PASS |
| 12 | open jaw | P6-S12b | NOT_IMPLEMENTED (legs must chain) |
| 13 | multi-city NYC→LON→PAR→ROM→NYC | P6-S13b, P6-ML-02 | NOT_IMPLEMENTED for FCO (BUG-10); 4-leg US itinerary PASS |
| 14 | multiple travelers/children | P6-S14 | NOT_IMPLEMENTED |
| 15 | flight + hotel + transfer | P6-S15b | PASS |
| 16–20 | cancel-before-confirmation, rapid confirm, duplicate, refresh/close during confirmation, stale availability | P1-FAIL-01c, P3-MUT-01/02, P10 offline/rapid, P5-SUP-03 | PASS (BUG-07 on one 500) |
| 21–23 | price change, supplier failure, pending outcome/retry | P5-SUP-02/02b, P5-SUP-05b, P5-SUP-04b | PASS |
| 24–27 | multi-leg sequences ML-01..04 | P6-ML-01..04 | ML-02 PASS; others NOT_IMPLEMENTED (BUG-10) |
| 28–30 | edit/remove/reorder a middle leg; unsupported structure feedback | P6-ML-EDIT, P3-ITN-04, P6-S12b | NOT_IMPLEMENTED (edit); refusals explicit |

## 15 agent flows (Prompt 8 A–M) and Prompt 7 conversations

| Flow | Test | Outcome |
|---|---|---|
| A change plan mid-search | P7-AGENT-00 | NOT_IMPLEMENTED (no conversation) |
| B expired offer then "book it" | cda5ccb live proof + P5-SUP-03, ADR-0011 | PASS (re-plan + re-approval) |
| C quoted total changes | P5-SUP-02/02b | PASS (re-approval, booked total = re-quoted) |
| D "book it" repeated/concurrent | P3-MUT-01/02 | PASS (BUG-07 on one response code) |
| E/F stop / continue | P1-FAIL-01c, P9-RACE-02 | PASS before commit; FAIL after (BUG-08) |
| G option 2 then option 3 | P7-AGENT-00 | NOT_IMPLEMENTED |
| H close/refresh/expire/logout/worker restart | P1-E2E-01, P2-SES-*, P1-FAIL-05 | PASS |
| I flight succeeds, hotel fails | P1-FAIL-04, P5-SUP-05b | PASS (compensation contract, no hotel-only retry by design) |
| J A times out but may have succeeded | P1-FAIL-03, P5-SUP-04b | PASS (reconciled) |
| K "leave one day later" after booking | capability map | NOT_IMPLEMENTED |
| L alice and bob concurrently, same route | P13-CACHE-01, P2-CACHE-01 | PASS |
| M two conversations, "book that" scope | P7-AGENT-00 | NOT_IMPLEMENTED |
| Prompt 7 conversations (60) | P7-FT-01..04, P7-INJ-01, P7-FAB-01 | 13 single-shot phrasings executed; conversations NOT_IMPLEMENTED |

## Route / date matrix

R01–R30 and D01–D07: one test each (`P4-Rxx`, `P4-Dxx`) plus instant-ordering checks for R06, R10, R28. Additional families (Seattle→Sydney R10, Boston→Toronto not run, Dallas→Cancún not run, Paris→Rome via FCO refused (BUG-10), Tokyo→Singapore via ML-03 refused, London→Dubai via ML-04 refused, Delhi→New York not run, Sydney→Los Angeles not run, Bangor→New York ML-01 refused as itinerary / booked as round trip, Chicago→Bangor not run). Pairwise generation across route × dates × account × travelers × budget × preferences × mode × network was not produced: travelers, preferences and mode do not exist as request dimensions, so the meaningful pairs collapse to route × date × account × budget × network, which the executed tests cover by category rather than by generated matrix (see `results.csv` filters `P4-`, `P5-POL-`, `P13-`, `P1-FAIL-`).

## Browser / device coverage

| Engine / device | Coverage |
|---|---|
| Chromium 1243 (Desktop Chrome) | full journeys, recovery, a11y, keyboard |
| Chromium Pixel 7 emulation | existing E2E (mobile.spec) at cda5ccb; responsive widths in P10 |
| WebKit 2359 (Desktop Safari emulation) | smoke (sign-in, trips) |
| Firefox | BLOCKED (not installed) |
| Real Safari / Chrome / Edge / private mode / real phone / screen reader | BLOCKED (not automatable here) |
