# Results summary — qa-20260923-repair-cda5ccb

Generated 2026-09-23T05:44:52+00:00 from results.csv (324 rows, 205 effective after supersession, 47 superseded rows kept for the record).

## Totals

| PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total | executed | executed pass rate |
|---||---||---||---||---||---||---||---|
| 190 | 0 | 3 | 11 | 1 | 205 | 190 | 100% |

## By prompt
| prompt | PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total |
|---|---|---|---|---|---|---|
| 0 | 26 | 0 | 0 | 0 | 0 | 26 |
| 1 | 10 | 0 | 0 | 0 | 0 | 10 |
| 3 | 35 | 0 | 0 | 2 | 0 | 37 |
| 4 | 40 | 0 | 0 | 0 | 0 | 40 |
| 5 | 22 | 0 | 1 | 0 | 0 | 23 |
| 6 | 11 | 0 | 0 | 9 | 1 | 21 |
| 9 | 8 | 0 | 0 | 0 | 0 | 8 |
| 10 | 9 | 0 | 1 | 0 | 0 | 10 |
| 11 | 21 | 0 | 0 | 0 | 0 | 21 |
| 12 | 8 | 0 | 1 | 0 | 0 | 9 |

## By priority
| priority | PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total |
|---|---|---|---|---|---|---|
| P0 | 62 | 0 | 0 | 0 | 0 | 62 |
| P1 | 106 | 0 | 2 | 11 | 1 | 120 |
| P2 | 22 | 0 | 1 | 0 | 0 | 23 |

## By test layer
| layer | PASS | FAIL | BLOCKED | NOT_IMPLEMENTED | NOT_APPLICABLE | total |
|---|---|---|---|---|---|---|
| api | 162 | 0 | 0 | 2 | 0 | 164 |
| audit ledger | 1 | 0 | 0 | 0 | 0 | 1 |
| browser | 9 | 0 | 1 | 0 | 0 | 10 |
| container inspection | 1 | 0 | 0 | 0 | 0 | 1 |
| database read-only | 1 | 0 | 0 | 0 | 0 | 1 |
| db read-only | 1 | 0 | 0 | 0 | 0 | 1 |
| http | 3 | 0 | 0 | 0 | 0 | 3 |
| idp http | 1 | 0 | 0 | 0 | 0 | 1 |
| inventory | 4 | 0 | 2 | 9 | 1 | 16 |
| service fault | 2 | 0 | 0 | 0 | 0 | 2 |
| service fault on the iso | 4 | 0 | 0 | 0 | 0 | 4 |
| stack restart | 1 | 0 | 0 | 0 | 0 | 1 |

## Bugs and risks referenced


## Failed tests (effective)

| test | priority | actual |
|---|---|---|

## Not implemented / blocked / not applicable (effective)

| test | status | why |
|---|---|---|
| P10-UX-01 | BLOCKED | not run: no human participant; the guided journey screenshots (p01-*.png) stand in |
| P12-FRESH-01 | BLOCKED | not run here: a disposable second stack does not fit the 8 GB VM beside the running one and `make stack-nuke` would erase the user's data (forbidden). Fresh-database migrations are exercised by every  |
| P3-EDIT-01 | NOT_IMPLEMENTED | PUT 405, PATCH 405, DELETE 405; no edit/delete route exists in TripController or the web app |
| P3-TRAV-01 | NOT_IMPLEMENTED | no traveler entity: one trip = one traveler (the principal, or a tenant employee id chosen by MANAGER/TRAVEL_ADMIN); no counts, names, birthdates or children; the only traveler test that exists (arran |
| P5-SUP-08 | BLOCKED | the sandbox suppliers are in-process adapters behind the gateway's gRPC contract; the only documented seams are the fixture cities/codes above and stopping the gateway container (P1-FAIL-02). Wire-lev |
| P6-ML-EDIT | NOT_IMPLEMENTED | trips are immutable requests (see P3-EDIT-01); a changed plan is a new trip; unsupported structures are refused at validation (P3-ITN-*) |
| P6-S02 | NOT_IMPLEMENTED | no offer list is shown or selectable; the optimizer picks one bundle and explains it |
| P6-S03 | NOT_IMPLEMENTED | cost is one of five weighted criteria (cost 0.4, time 0.25, risk 0.15, preference 0.1, experience 0.1); 'cheapest' is not a user-selectable mode |
| P6-S04 | NOT_IMPLEMENTED | time is a weighted criterion, not a selectable mode |
| P6-S07 | NOT_IMPLEMENTED | no carrier filter in the request contract |
| P6-S08 | NOT_IMPLEMENTED | the request takes one airport code per leg; there is no metro search. A city code (NYC) is refused with the airports it stands for (UNKNOWN_LOCATION: JFK, EWR, LGA) — BUG-01 fixed; the search over sev |
| P6-S09 | NOT_IMPLEMENTED | stops are governed by policy (maxStops=1, violation -> approval), not by the traveler |
| P6-S12b | NOT_IMPLEMENTED | 422 'leg 2 must depart from LHR, where leg 1 lands': legs must chain; open jaw is not supported by the itinerary contract (explicit feedback given).  |
| P6-S14 | NOT_IMPLEMENTED | one traveler per trip; no counts, ages or fees |
| P6-SUM-01 | NOT_APPLICABLE | by design there is no offer at confirmation time: the traveler confirms a request, the platform then searches, judges, picks and books; the page states 'Submitting books the trip' and shows the chosen |
