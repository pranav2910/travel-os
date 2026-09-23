# Results summary — qa-20260923-cda5ccb

Generated 2026-09-23T03:42:13+00:00 from results.csv (251 rows, 211 effective after supersession, 34 superseded rows kept for the record).

## Totals

| PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total | executed | executed pass rate |
|---||---||---||---||---||---||---||---|
| 174 | 17 | 4 | 14 | 2 | 211 | 191 | 91% |

## By prompt
| prompt | PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total |
|---|---|---|---|---|---|---|
| 1 | 12 | 1 | 0 | 0 | 0 | 13 |
| 2 | 15 | 0 | 0 | 0 | 1 | 16 |
| 3 | 30 | 5 | 0 | 2 | 0 | 37 |
| 4 | 34 | 5 | 0 | 1 | 0 | 40 |
| 5 | 22 | 2 | 1 | 0 | 0 | 25 |
| 6 | 7 | 0 | 0 | 10 | 1 | 18 |
| 7 | 6 | 0 | 0 | 1 | 0 | 7 |
| 9 | 7 | 1 | 0 | 0 | 0 | 8 |
| 10 | 7 | 2 | 1 | 0 | 0 | 10 |
| 11 | 21 | 0 | 0 | 0 | 0 | 21 |
| 12 | 8 | 1 | 1 | 0 | 0 | 10 |
| 13 | 5 | 0 | 1 | 0 | 0 | 6 |

## By priority
| priority | PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total |
|---|---|---|---|---|---|---|
| P0 | 67 | 3 | 0 | 0 | 0 | 70 |
| P1 | 92 | 10 | 2 | 11 | 2 | 117 |
| P2 | 15 | 4 | 2 | 3 | 0 | 24 |

## By test layer
| layer | PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total |
|---|---|---|---|---|---|---|
| api | 143 | 14 | 0 | 4 | 1 | 162 |
| audit ledger | 1 | 0 | 0 | 0 | 0 | 1 |
| browser | 13 | 2 | 0 | 0 | 0 | 15 |
| container inspection | 1 | 0 | 0 | 0 | 0 | 1 |
| database read-only | 1 | 0 | 0 | 0 | 0 | 1 |
| heuristic | 0 | 0 | 1 | 0 | 0 | 1 |
| http | 2 | 1 | 0 | 0 | 0 | 3 |
| idp http | 1 | 0 | 0 | 0 | 0 | 1 |
| inventory | 5 | 0 | 2 | 10 | 1 | 18 |
| service fault | 2 | 0 | 0 | 0 | 0 | 2 |
| service fault on the iso | 4 | 0 | 0 | 0 | 0 | 4 |
| stack restart | 1 | 0 | 0 | 0 | 0 | 1 |
| — | 0 | 0 | 1 | 0 | 0 | 1 |

## Bugs and risks referenced

- BUG-01: P1-INV-02, P4-R20, P4-R21, P4-R22
- BUG-03: P3-VAL-14b, P4-R26
- BUG-04: P1-PERSONA-01b
- BUG-05: P3-ITN-03b
- BUG-06: P3-LIST-02b
- BUG-07: P3-MUT-02b
- BUG-08: P3-LIFE-01b, P9-RACE-02
- BUG-09: P11-HDR-01
- BUG-10: P4-R03b, P6-S13b
- BUG-11: P5-MONEY-ZERO-b
- BUG-12: P5-MONEY-WRONG-b
- BUG-13: P10-A11Y-01, P10-A11Y-02
- BUG-14: P12-EDGE-01
- RISK-01: P2-SES-05
- RISK-02: P7-FT-01
- RISK-03: P7-FT-03

## Failed tests (effective)

| test | priority | actual |
|---|---|---|
| P1-INV-02 | P0 | 202 and BOOKED: the sandbox airline manufactured flights from 'QQQ' and the platform booked them; no location validation anywhere in the chain |
| P3-LIFE-01b | P0 | trip CANCELLED (history 'cancel booked'), but the order and its item remain CONFIRMED with the sandbox: no CancelOrder was issued and no exposure/attention item was created. A traveler who cancels believes the reservatio |
| P9-RACE-02 | P0 | cancel 200 None; final=CANCELLED; orders=[('CONFIRMED', ['CONFIRMED'])]; history=[None, None, None, None, None, None] |
| P10-A11Y-01 | P1 | clean: ['/', '/trips', '/trips/new', '/approvals', '/operations', '/finance', '/demand', '/connectors', '/learning']; violations: {'/trips/:id': [('color-contrast', 'serious', 1)]} (detail in P10-A11Y-02) |
| P3-ITN-03b | P1 | accepted (202), then FAILED at OPTIMIZATION with failureCode 'ACTIVITY_io.grpc.StatusRuntimeException': an unknown currency reaches the optimizer, and a Java exception class name is surfaced as the failure code the trave |
| P3-MUT-02b | P1 | exactly one trip (duplicate prevention holds), but one of the five concurrent requests answered 500 instead of the same trip or a 409: the idempotency insert race is not handled; a client would see an error and retry, wh |
| P3-VAL-14b | P1 | accepted (202) and BOOKED: a trip departing 2025-01-10 was booked by the sandbox on 2026-09-23. No validation of the departure instant against the clock anywhere in the chain. |
| P4-R04 | P1 | 422 INTENT_INVALID -> REJECTED-422 |
| P4-R20 | P1 | 202  -> BOOKED / total={'currency': 'USD', 'amountMinor': 55130, 'display': 'USD 551.30'} |
| P4-R21 | P1 | 202  -> BOOKED / total={'currency': 'USD', 'amountMinor': 27446, 'display': 'USD 274.46'} |
| P4-R22 | P1 | 202  -> BOOKED / total={'currency': 'USD', 'amountMinor': 31977, 'display': 'USD 319.77'} |
| P4-R26 | P1 | 202  -> BOOKED / total={'currency': 'USD', 'amountMinor': 32627, 'display': 'USD 326.27'} |
| P5-MONEY-ZERO-b | P1 | a settled refund of USD 0.00 is accepted (200) and recorded as a REFUND_SETTLED outcome (learning evidence). Zero is not a refund; it should be refused like negatives. |
| P10-A11Y-02 | P2 | [('color-contrast', 'serious', '<li data-state="skipped">Approval (not needed)</li>', 'Fix any of the following:\n  Element has insufficient color contrast of 4.26 (foreground color: #6b7280, background color')] |
| P12-EDGE-01 | P2 | the edge answers nginx's HTML '502 Bad Gateway' page; the web client treats a non-JSON answer as an uncertain outcome and asks the user to check the list, which is honest but loses the reason. |
| P3-LIST-02b | P2 | 200 with one item: a negative limit is silently clamped; undocumented. |
| P5-MONEY-WRONG-b | P2 | accepted (200) and recorded in EUR without conversion (never summed with USD: correct), but the currency mismatch with the order is not refused or flagged. |

## Not implemented / blocked / not applicable (effective)

| test | status | why |
|---|---|---|
| P10-UX-01 | BLOCKED | every screen carries the sandbox chip; empty states name what would fill them; error states quote the platform's reason (NO_POLICY for zoe); terminology is consistent (trip, approval, disruption, expo |
| P12-FRESH-01 | BLOCKED | not run here: a disposable second stack does not fit the 8 GB VM beside the running one and `make stack-nuke` would erase the user's data (forbidden). Fresh-database migrations are exercised by every  |
| P13-VOL-02 | BLOCKED | not run: no isolated load environment (LOAD_LIMIT 10 users on the shared local stack); 1,000 trips would take >30 min of worker time on this laptop and add noise to the shared database. Proposed for a |
| P2-TOK-02 | NOT_APPLICABLE | travelos-web refuses the password grant (400 {"error":"unauthorized_client","error_description":"Client not allowed for direc): the browser client cannot be used as a wrong-environment token source |
| P3-EDIT-01 | NOT_IMPLEMENTED | PUT 405, PATCH 405, DELETE 405; no edit/delete route exists in TripController or the web app |
| P3-TRAV-01 | NOT_IMPLEMENTED | no traveler entity: one trip = one traveler (the principal, or a tenant employee id chosen by MANAGER/TRAVEL_ADMIN); no counts, names, birthdates or children; the only traveler test that exists (arran |
| P4-R03b | NOT_IMPLEMENTED | 422 INTENT_INVALID 'unknown location SJC: the platform does not know its clock': the itinerary path refuses airports outside the platform's 24-airport zone table. An honest refusal, but SJC is a real  |
| P5-SUP-08 | BLOCKED | the sandbox suppliers are in-process adapters behind the gateway's gRPC contract; the only documented seams are the fixture cities/codes above and stopping the gateway container (P1-FAIL-02). Wire-lev |
| P6-ML-EDIT | NOT_IMPLEMENTED | trips are immutable requests (see P3-EDIT-01); a changed plan is a new trip; unsupported structures are refused at validation (P3-ITN-*) |
| P6-S02 | NOT_IMPLEMENTED | no offer list is shown or selectable; the optimizer picks one bundle and explains it |
| P6-S03 | NOT_IMPLEMENTED | cost is one of five weighted criteria (cost 0.4, time 0.25, risk 0.15, preference 0.1, experience 0.1); 'cheapest' is not a user-selectable mode |
| P6-S04 | NOT_IMPLEMENTED | time is a weighted criterion, not a selectable mode |
| P6-S07 | NOT_IMPLEMENTED | no carrier filter in the request contract |
| P6-S08 | NOT_IMPLEMENTED | the request takes one airport code per leg; there is no metro expansion (a metro code is accepted verbatim: BUG-01) |
| P6-S09 | NOT_IMPLEMENTED | stops are governed by policy (maxStops=1, violation -> approval), not by the traveler |
| P6-S12b | NOT_IMPLEMENTED | 422 'leg 2 must depart from LHR, where leg 1 lands': legs must chain; open jaw is not supported by the itinerary contract (explicit feedback given). |
| P6-S13b | NOT_IMPLEMENTED | each refused with 422 'unknown location X: the platform does not know its clock'. Honest, but the zone table covers 24 airports only; multi-city trips through Rome, Bangor, Tokyo, Seoul, Singapore, Du |
| P6-S14 | NOT_IMPLEMENTED | one traveler per trip; no counts, ages or fees |
| P6-SUM-01 | NOT_APPLICABLE | by design there is no offer at confirmation time: the traveler confirms a request, the platform then searches, judges, picks and books; the page states 'Submitting books the trip' and shows the chosen |
| P7-AGENT-00 | NOT_IMPLEMENTED | no chat surface, no multi-turn context, no plan-only mode, no hold, no 'book that'; free text is a single request field whose extraction is ledgered; the stack runs LLM_PROVIDER=fake (deterministic st |
