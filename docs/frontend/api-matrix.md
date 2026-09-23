# Web app: screens, endpoints, roles and states

The browser talks to one origin (the `web` edge: nginx serving the React app and proxying only the
public routes below to their owning services). Every request carries the Keycloak access token
(Authorization Code + PKCE, `travelos-web` public client, tokens in memory only) and, for every
mutation, an `Idempotency-Key` minted once per intended operation. Errors are RFC 9457 problem
details with the platform `code` (`libs/spring-web`); the app never retries a business rejection.

Roles come from the token (`roles` claim); the server decides on every request. Legend: T traveler
(owner), M MANAGER, A TRAVEL_ADMIN, F FINANCE.

| Screen | Method + path (service) | Who | Notes |
|---|---|---|---|
| Overview, Trips | `GET /api/v1/trips?limit=` (travel-core) | any | the caller's own trips |
| Overview, Approvals inbox | `GET /api/v1/trips?scope=tenant&status=AWAITING_APPROVAL` (travel-core) | M A F | new in this phase; travelers get 403 `NOT_TENANT_WIDE`; unknown status 422 |
| New trip | `POST /api/v1/trips` (travel-core) | any; `travelerId` for others M A | body `{request?, intent?, travelerId?, source:WEB}`; key fixed per payload until answered; replay returns the same trip, a changed body with an old key is 409 `IDEMPOTENCY_KEY_REUSED`; 400 `fields` for validation, 422 for domain refusals (e.g. `HOTEL_DETAILS_INSUFFICIENT`). **Submitting may book immediately** when policy allows it without approval. |
| Trip detail | `GET /api/v1/trips/{id}`, `/history`, `/decisions`, `/components` | T M A F | 404 for other tenants and other travelers (existence is not disclosed); polled with backoff until BOOKED/COMPLETED/CANCELLED/FAILED or AWAITING_APPROVAL (CANCELLING keeps polling) |
| Trip detail: bookings | `GET /api/v1/orders?tripId=` (order) | T M A F | items with supplier refs, flights (UTC), hotels/ground with IANA zones, changes, exposures |
| Trip detail: why | `GET /api/v1/audit/trips/{id}/decisions` (audit), `GET /api/v1/policy-decisions?tripId=` (policy) | T M A F | narrative, learning section (mode, applied, contributions), policy reasons |
| Trip detail: disruptions | `GET /api/v1/trips/{id}/disruptions` (disruption) | T M A F | |
| Trip detail: approve / reject | `POST /api/v1/trips/{id}/approval` `{decision, comment}` | M A, never the owner | 403 `SELF_APPROVAL`/not an approver; 409 when already decided or the plan changed (the page refreshes and asks for a new review) |
| Trip detail: cancel | `POST /api/v1/trips/{id}/cancellation` `{reason}` | T A | idempotent by state |
| Trip detail: attest completion | `POST /api/v1/trips/{id}/completion` | T after the last arrival, A any time | 409 `TRIP_NOT_OVER` before the last arrival; BOOKED never becomes COMPLETED by time alone |
| Trip detail: feedback | `GET/POST /api/v1/learning/feedback` (learning) | T only | rating 1..5, fixed tag vocabulary (422 `UNKNOWN_TAG`), comment stored as text; same values = same revision (200), changed = new revision (201); 403 `NOT_THE_TRAVELER`, 404 other tenant |
| Approvals inbox: recoveries | `GET /api/v1/disruptions?status=HUMAN_REQUIRED` (disruption) | M A F (travelers: own only) | new in this phase |
| Disruptions (operations) | `GET /api/v1/disruptions?status=` | M A F | pending / partially changed / unresolved shown as returned |
| Disruption detail | `GET /api/v1/disruptions/{id}` | T M A F | decision record (proto JSON, int64 money as strings), dependent component changes, exposures via the order |
| Disruption detail: decide | `POST /api/v1/disruptions/{id}/approval` `{decision, comment}` | M A, never the traveler | 403 self / not approver; 409 already decided |
| Finance: exposures | `GET /api/v1/orders/exposures?status=OPEN|RESOLVED` (order) | A F | new in this phase; others 403 `NOT_AN_EXPOSURE_RESOLVER` |
| Finance: resolve | `POST /api/v1/orders/{orderId}/exposures/{id}/resolution` `{resolution}` | A F | idempotent by key; closing a record is not a refund |
| Finance: settled refund | `POST /api/v1/learning/outcomes/refunds` `{tripId, orderId, itemId?, amountMinor, currency, reference}` | F only | same statement = same revision, corrected amount = next revision; `amountMinor` is an integer (the app converts typed major units exactly) |
| Finance: outcome ledger | `GET /api/v1/learning/outcomes?tripId=` | T A F | |
| Demand inbox / detail | `GET /api/v1/demand?status=&travelerId=`, `/{id}`, `/{id}/history`, `/{id}/evidence` (enterprise-context) | T, HRIS manager, A; F reads | evidence shown as escaped data |
| Demand: details / clear flag | `POST /api/v1/demand/{id}/details` `{destination?, startDate?, endDate?, clearReviewFlag?}` | T, manager, A | |
| Demand: dismiss | `POST /api/v1/demand/{id}/dismissal` `{reason?}` | T, manager, A | |
| Demand: convert | `POST /api/v1/demand/{id}/conversion` | T, manager, A | one key per candidate: repeated clicks, retries and reloads return the same trip; after conversion the page links the trip and offers no second conversion |
| Connectors | `GET /api/v1/connectors`, `/{id}`, `/{id}/runs` | A F | every provider is SIMULATED and labelled so |
| Connectors: manage | `POST /api/v1/connectors` `{kind, provider, config}`, `/{id}/status`, `/{id}/config`, `/{id}/sync` | A | no live OAuth flows exist; providers without an adapter are not offered |
| Learning | `GET /api/v1/learning/config`, `/summary`, `/profiles`, `/profiles/{id}`, `/history` | A F | mode, deployment class, active/previous, evidence counts, evaluation |
| Learning: mode | `PUT /api/v1/learning/config` `{mode, expectedVersion}` | A | 409 `VERSION_CONFLICT` on a stale version: the page refreshes and explains |
| Learning: build | `POST /api/v1/learning/profiles` `{window?, cutoff?, evidenceClass?}` | A | 202; the build workflow finishes it (BUILDING → BUILT → ELIGIBLE / REJECTED / FAILED) |
| Learning: activate | `POST /api/v1/learning/profiles/{id}/activation` `{expectedVersion}` | A | 422 `NOT_ELIGIBLE` / `CLASS_MISMATCH` / `STALE_PROFILE` / `INCOMPATIBLE_ALGORITHM`; 409 stale version |
| Learning: rollback | `POST /api/v1/learning/rollback` `{expectedVersion, toBaseline}` | A | previous eligible profile, or the baseline |
| My preferences | `GET /api/v1/learning/preferences` | any | only the caller's own learned preferences |

## Lifecycle states shown

- Trip: DRAFT · SUBMITTED · PLANNING · AWAITING_APPROVAL · APPROVED · BOOKING · BOOKED · COMPLETED · CANCELLING · CANCELLED · FAILED
  (CANCELLING: a booked trip whose reservation is being released at the suppliers; CANCELLED only once it is, or once a person resolved a refused release — `failureCode` CANCELLATION_INCOMPLETE meanwhile)
  (terminal: BOOKED, COMPLETED, CANCELLED, FAILED; the app stops polling there and at AWAITING_APPROVAL).
  An approved trip can go back to PLANNING: sandbox quotes live 20 minutes, so a plan approved after
  a longer wait is searched again from scratch (history reason "re-planning", replan reason
  QUOTE_EXPIRED) and the manager decides again on the new plan. If the quote is gone twice the trip
  ends FAILED at REVALIDATION with OFFER_GONE. The page shows both from the trip itself: the stepper
  moves back, the timeline names the reason, the failure alert quotes the code.
- Component / order item: PLANNED · QUOTED · REVALIDATING · BOOKING · CONFIRMED · FAILED · CANCELLED · CANCEL_FAILED · CHANGED · SKIPPED.
- Disruption: DETECTED · IMPACT_CONFIRMED · SEARCHING_ALTERNATIVES · OPTIMIZING · DECISION_READY · AUTO_ALLOWED · HUMAN_REQUIRED · CHANGING · RESOLVED · NO_ALTERNATIVE · FAILED · MANUAL_INTERVENTION_REQUIRED.
- Demand: NEEDS_REVIEW · ACTIONABLE · DISMISSED · WITHDRAWN · CONVERTED. Connector: ENABLED · DISABLED.
- Learning profile: BUILDING · BUILT · ELIGIBLE · REJECTED · FAILED; mode OFF · SHADOW · ACTIVE.

## Money and time

Amounts are `{currency, amountMinor}`; REST views serialize `amountMinor` as a JSON number (a Java
long), proto-JSON documents (recovery decisions) as a string. The app parses both into a `BigInt`
and formats with the currency's minor-unit exponent; zero is shown as `USD 0.00`, missing as `—`.
Flight times are instants on the UTC clock (the sandbox airline's semantics) and are labelled UTC;
hotel and ground times carry their IANA zone and are written in it. A contract local date
(`checkInDate`) is shown as written, never through a `Date`.

## How views follow the workflow

- A trip page polls its trip with bounded backoff (1.5 s doubling every 30 s to 15 s, stop after
  30 min or at a terminal state). `AWAITING_APPROVAL` and a recovery's `HUMAN_REQUIRED` are resting
  states: nothing moves until a person acts, so polling stops there too. When that person acts in
  this browser (approve, reject, cancel, complete), polling is re-armed for five minutes from the
  action, so the decision's consequences appear without a reload. Someone else's action shows up on
  the next visit or reload; the inboxes refetch every 15–20 s.
- The other views of a trip (components, bookings, disruptions, decision record, timeline,
  feedback) are refetched whenever the trip's status, version or `updatedAt` changed, and again 4 s
  and 12 s later, because the audit and order ledgers are written by consumers that run a moment
  after the trip itself moved.
- A deep link can arrive before the record: a disruption's id is returned by the supplier notice
  (202) a moment before the disruption service consumes the event that creates it. For 60 s after
  the page was opened, a 404 is shown as "Not here yet" and asked again every 2 s; after that it is
  a real "Not found".

## What the frontend never does

It does not authorize a booking, compute an approval threshold, reconstruct workflow state from
timers, recompute learning, or send a browser-chosen tenant or role. The edge proxies only the
routes in this table; everything else under `/api/` is 404 at the edge.
