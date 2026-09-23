# Bug reports — qa-20260923-cda5ccb

Environment for every report: travel-os `cda5ccb`, local Docker stack (`make stack-up`), sandbox providers only, execution clock 2026-09-23 UTC. Reproduction uses the run harness (`qa/qa-20260923-cda5ccb/harness/qa.py`, password-grant tokens from the realm's dev CLI client) or the web app at http://localhost:8080. Suspected causes are hypotheses from reading the code; they were not verified by changing product code.

## P0 — launch-blocking

### BUG-08 · A cancelled trip leaves its reservation confirmed at the supplier

- **Tests**: P3-LIFE-01b, P9-RACE-02 (also visible in P9-DONE-01: cancel BOOKED → CANCELLED, order untouched).
- **Steps**: book any round trip as alice (`POST /api/v1/trips`, wait for BOOKED); `POST /api/v1/trips/{id}/cancellation {"reason":"x"}`; read `GET /api/v1/orders?tripId={id}` a minute later.
- **Expected**: the order is cancelled at the supplier (order `CANCELLED`, items `CANCELLED`), or the trip page and the order state say plainly that the reservation is still held and needs a person (an attention item or exposure).
- **Actual**: trip `CANCELLED` (history: "cancel booked"), order and item remain `CONFIRMED` with the sandbox airline; no attention item, no exposure, nothing published for the Order service to act on. Evidence: `evidence/p3-cancel-order-later.json`, `evidence/p9-cancel-race.json`.
- **Impact**: the traveler believes the trip is gone while a live (sandbox) reservation remains; with a live supplier this is money spent on a trip nobody takes. Status on the trip page is untruthful at the trip level even though the Booking card still shows Confirmed.
- **Affected users**: every traveler; every tenant.
- **Suspected cause (hypothesis)**: `TripService.cancel` publishes `travel.trip.cancelled`, but no consumer in the Order service issues `CancelOrder` for a traveler-initiated cancellation (only disruption recoveries call `ChangeOrder`/`CancelOrder`); the policy's `autonomy.cancellation.enabled=false` may have been read as "never cancel", which does not cover an explicit human cancellation.
- **Fix area**: Order service consumer for `travel.trip.cancelled` → cancel saga (compensation path already exists for failed bookings); or the workflow's cancellation handling; plus the trip page wording until then.

### BUG-01 · Any three-letter code is accepted as an airport and booked

- **Tests**: P1-INV-02, P4-R20, P4-R21, P4-R22.
- **Steps**: `POST /api/v1/trips` with `origin: "QQQ"` (or `destination: "QQQ"`, or `origin: "NYC"`, a metro code), future dates.
- **Expected**: 422 (unknown location) or an honest no-results outcome; never a reservation for a place that does not exist.
- **Actual**: 202, then `BOOKED` with sandbox-air flights "from QQQ" (USD 551.30) and "to QQQ", and "from NYC" as if it were an airport. Evidence: `evidence/p1-probe-unknown-code.json`, `evidence/p4-R20.json`, `p4-R21.json`, `p4-R22.json`.
- **Impact**: the round-trip request path has no location validation; the sandbox airline manufactures inventory for any code, so nonsense requests become real (sandbox) bookings. With a live provider the search would fail; with the sandbox the platform books and pays.
- **Suspected cause**: `TravelIntent` validation checks the code shape only; `Locations` (24 airports) is consulted by the itinerary path ("the platform does not know its clock") but not by the legacy round-trip path; `SandboxInventory.generate` seeds schedules from any string.
- **Fix area**: Travel Core intent validation (shared with the itinerary path); optionally the sandbox airline refusing unknown codes.

### BUG-03 · Departure dates in the past are accepted and booked

- **Tests**: P3-VAL-14/14b, P4-R26.
- **Steps**: `POST /api/v1/trips` with `earliestDeparture: "2025-01-10T05:00:00Z"` (or 2026-01-10) and matching return.
- **Expected**: 422 (cannot travel in the past).
- **Actual**: 202 and `BOOKED` on 2026-09-23 for a January 2025 departure. Evidence: `evidence/p3-past-dates-final.json`, `evidence/p4-R26.json`.
- **Impact**: a typo in the year books a trip that cannot be taken; learning evidence and Finance data are polluted.
- **Suspected cause**: no comparison of `earliestDeparture` with the clock in `TravelIntent`/`ItineraryCodec` validation.
- **Fix area**: Travel Core validation (a clock is already injected for timestamps).

## P1 — major functional

### BUG-07 · One of five concurrent identical requests answers 500

- **Test**: P3-MUT-02/02b.
- **Steps**: five simultaneous `POST /api/v1/trips` with the same `Idempotency-Key` and payload.
- **Expected**: one trip; every response 202/200 with that trip, or 409.
- **Actual**: statuses `[202, 500, 202, 202, 202]`, one trip (duplicate prevention holds). Evidence: `evidence/p3-idempotency-concurrent.json`.
- **Impact**: a double-click or two tabs can show the user an error although the trip exists; a retry then succeeds idempotently.
- **Suspected cause**: the idempotency record is inserted after the trip; the concurrent unique-violation is not mapped to "return the first result" (the sequential path is).
- **Fix area**: Travel Core idempotency store (catch the unique violation and re-read).

### BUG-05 · An unknown currency reaches the optimizer and a Java exception name becomes the failure code

- **Test**: P3-ITN-03/03b.
- **Steps**: `POST /api/v1/trips` with `itinerary.currency: "XXX"`.
- **Expected**: 422 at validation.
- **Actual**: 202, then `FAILED` at `OPTIMIZATION` with `failureCode: "ACTIVITY_io.grpc.StatusRuntimeException"`. Evidence: `evidence/p3-P3-ITN-03.json`.
- **Impact**: the traveler sees an exception class name; the failure vocabulary is broken for this case.
- **Fix area**: itinerary validation (allowed currencies); the workflow's failure-code mapping for unexpected activity errors.

### BUG-11 · A settled refund of zero is accepted and ledgered

- **Test**: P5-MONEY-ZERO/-b.
- **Steps**: as carol, `POST /api/v1/learning/outcomes/refunds` with `amountMinor: 0`.
- **Expected**: 422 (negative amounts are refused; zero should be too).
- **Actual**: 200, a `REFUND_SETTLED` outcome for USD 0.00 is recorded as learning evidence. Evidence: `evidence/p5-refund-zero.json`.
- **Fix area**: refund request validation (`@Positive`).

### BUG-04 · A trip arranged for someone else has an empty traveler snapshot

- **Test**: P1-PERSONA-01b.
- **Steps**: as carol (TRAVEL_ADMIN), `POST /api/v1/trips` with `travelerId: "emp_1001"`.
- **Actual**: `travelerId` correct, but `traveler.givenName/familyName/email` are empty; the approval page shows no traveler name for such trips. Evidence: `evidence/p1-persona-arrange.json`.
- **Suspected cause**: the snapshot is taken from the creator's token claims, not from the traveler's identity.
- **Fix area**: Travel Core `TravelerSnapshot` for arranged trips (an HRIS lookup exists in the enterprise-context service).

## P2 — minor

- **BUG-06** · `GET /trips?limit=-1` answers 200 with one item (silently clamped; undocumented). Test P3-LIST-02b.
- **BUG-09** · The edge sends no `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` headers (CORS is correctly closed; no cookies, so CSRF does not apply). Test P11-HDR-01. Fix area: `docker/web/nginx.conf.template`.
- **BUG-10** · The itinerary path only knows 24 airports (BGR, SJC, NRT, DXB, DEL, ICN, SIN, FCO, SYD, HNL, AKL, ANC, MCO, PWM are refused with "the platform does not know its clock") while the round-trip path books the same codes (BUG-01): inconsistent and limiting for multi-city trips. Tests P4-R03b, P6-S13b.
- **BUG-12** · A refund in a currency different from the order's (EUR on a USD order) is recorded without a mismatch check (never converted, which is correct). Test P5-MONEY-WRONG-b.

## Risks and requirement gaps (not defects against the documented contract)

- **RISK-01** · Identity-provider revocation takes effect only when the 15-minute access token expires; a user logged out by an admin can still act for up to 15 minutes at the API. Measured in P2-SES-05. Document, or shorten the token lifetime / add token introspection for sensitive mutations.
- **RISK-02** · There is no plan-only mode: "plan" or "find" wording in the free-text form books when the extractor understands it; the form discloses this ("Submitting books the trip"). Prompt 7 expects plan-only. Tests P7-FT-01.
- **RISK-03** · The structured intent has no cabin, airline, airport-preference or traveler-count fields, so such constraints cannot survive free-text extraction; the fake extractor answers NEEDS_CLARIFICATION for them (honest) rather than silently dropping them. Tests P7-FT-03.
- **RISK-04** · No booking horizon and no duration limit: eleven months, one year and five years ahead all book in the sandbox (P4-R13, R27, D07). A live provider would refuse; the platform should say so before search.
- **RISK-05** · No cursor pagination on trip lists (limit only, clamped): large tenants cannot page (P3-LIST-07, P13-VOL-01).
