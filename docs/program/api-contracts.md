# API contracts added by the platform-completion program

Frontend handoff reference. Every endpoint is tenant-scoped by the JWT; errors are
`{ "code": "...", "message": "..." }` with the HTTP status the code implies (404 when the caller may
not know the object exists; 403 when they may know but may not act; 409 conflicts; 422 invalid).
Mutations that create or change state take an `Idempotency-Key` header where noted.

## Phase 2 — traveler profiles, organization, arrangers (Enterprise Context, port 8090; ADR-0014)

### Profiles `/api/v1/travelers`

| Method & path | Who | Body / query | Returns |
|---|---|---|---|
| `GET /me?reveal=false` | any employee | `reveal=true` decrypts phone, date of birth, loyalty numbers, emergency contact (logged) | `ProfileView` |
| `PUT /me` | any employee | `ProfileRequest` (partial: only present fields change; `expectedVersion` optional, 409 `PROFILE_VERSION_STALE`) | `ProfileView` |
| `GET /{travelerId}?reveal=&purpose=` | SELF, TRAVEL_ADMIN, SPONSOR, MANAGER (HRIS), GRANT holder, FINANCE | `purpose` `^[A-Z_:0-9-]{1,80}$` (default `PROFILE_VIEW`) written to the access log on reveal | `ProfileView` with `relation` (why you may see it) and `sensitiveRevealed` |
| `PUT /{travelerId}` | SELF, TRAVEL_ADMIN, SPONSOR, GRANT holder | `ProfileRequest` | `ProfileView` |
| `GET /{travelerId}/changes` | anyone who may read the profile | — | `[{version, changedBy, changedAt, fields[]}]` (field names, never values) |
| `GET /{travelerId}/access-log` | SELF, TRAVEL_ADMIN | — | `[{principal, kind, purpose, at}]` newest first |
| `GET /guests` | any employee | — | guests the caller sponsors |
| `POST /guests` | any employee | `ProfileRequest` (givenName, familyName, email required) | 201 `ProfileView`, `travelerId` = `gst_…`, `sponsorEmployeeId` = caller |
| `GET /{travelerId}/documents` | anyone who may read the profile | — | `[DocumentView]` masked (`number` null, `numberLast4`) |
| `POST /{travelerId}/documents` | writers | `{type: PASSPORT|NATIONAL_ID|VISA, number, issuingCountry, nationality?, issuedOn?, expiresOn, holderGivenName?, holderFamilyName?}` | 201 `DocumentView` masked |
| `GET /documents/{documentId}/number?purpose=` | SELF, TRAVEL_ADMIN, SPONSOR, grant with `mayReadDocuments` | — | `DocumentView` with `number`; 403 `DOCUMENT_ACCESS_DENIED`; 409 `DOCUMENT_REVOKED`; logged |
| `DELETE /documents/{documentId}` | writers | — | 204; the row enters retention |

`ProfileView`: `travelerId, kind (EMPLOYEE|GUEST), givenName, familyName, middleName, email, phone, dateOfBirth, gender (M|F|X), nationality (ISO alpha-2), homeAirport (IATA), preferences{}, loyalty[{program, memberNumber, memberNumberLast4}], emergencyContact{name, phone, relation}, sponsorEmployeeId, active, version, relation, sensitiveRevealed, updatedAt`.
Redacted fields are `null` unless revealed.

### Organization `/api/v1/org`

| Method & path | Who | Body | Returns |
|---|---|---|---|
| `GET /units` | any | — | `[OrgUnit{unitId, kind (DEPARTMENT|LEGAL_ENTITY|OFFICE|COST_CENTER), code, name, parentUnitId, legalEntityId, active, version}]` |
| `POST /units` | TRAVEL_ADMIN | `{kind, code, name, parentUnitId?, legalEntityId?}` | 201 `OrgUnit`; 409 `ORG_UNIT_EXISTS` |
| `GET /projects` | any | — | all projects for TRAVEL_ADMIN/FINANCE; for others every open project plus restricted ones they are a member of |
| `GET /projects/{projectId}` | members, TRAVEL_ADMIN, FINANCE (404 otherwise when restricted) | — | `Project{projectId, code, name, client, costCenterId, restricted, active, version}` |
| `POST /projects` | TRAVEL_ADMIN | `{code, name, client?, costCenterId?, restricted}` | 201 `Project` |
| `GET /projects/{projectId}/members` | as above | — | `[{projectId, employeeId, role, since}]` |
| `POST /projects/{projectId}/members` | TRAVEL_ADMIN | `{employeeId, role?}` | 201 |

### Arranger grants `/api/v1/arrangers`

| Method & path | Who | Body | Returns |
|---|---|---|---|
| `GET ?arrangerEmployeeId=` | TRAVEL_ADMIN/FINANCE (all or one arranger's); others see their own | — | `[ArrangerGrant{grantId, arrangerEmployeeId, scope (EMPLOYEE|ORG_UNIT|PROJECT|TENANT), scopeId, mayReadDocuments, grantedBy, grantedAt, expiresAt, revokedAt}]` |
| `POST` | TRAVEL_ADMIN; a traveler for `scope=EMPLOYEE, scopeId=self` | `{arrangerEmployeeId, scope, scopeId?, mayReadDocuments, expiresAt?}` | 201 |
| `DELETE /{grantId}` | TRAVEL_ADMIN; the traveler for a self-delegation | — | 204; 409 `GRANT_ALREADY_REVOKED` |

### Offboarding

`POST /api/v1/employees/{employeeId}/deactivation` (TRAVEL_ADMIN, `Idempotency-Key`) →
`{employeeId, employeeDeactivated, profileDeactivated, grantsRevoked, documentsRevoked}`.

### Travel Core additions (port 8081)

- `POST /api/v1/trips` body gains `projectId?`. Authorization errors: 403 `NOT_AN_ARRANGER`,
  `TRAVELER_INACTIVE`, `TRAVELER_UNKNOWN`, `PROJECT_RESTRICTED`, `PROJECT_UNKNOWN`; 503
  `CONTEXT_UNAVAILABLE` when arranging for someone else while Enterprise Context is down.
  The `traveler` block is used only when Enterprise Context knows no profile.
- `GET /api/v1/trips/{tripId}` gains `allocation?`: `{departmentId, costCenterId, legalEntityId,
  officeId, projectId, projectRestricted, managerEmployeeId, arrangerEmployeeId, arrangerBasis
  (SELF|MANAGER|SPONSOR|GRANT|TRAVEL_ADMIN|LEGACY_ROLE), travelerKind}`; absent on legacy trips.
- `GET /api/v1/trips?scope=arranged` — trips the caller arranged for others. `scope=tenant` for a
  MANAGER now returns their reports' and arranged trips only.
- `POST /api/v1/trips/{tripId}/approval` may answer 403 `NOT_THE_APPROVER`.

### gRPC (`travelos.context.v1.EnterpriseContextService`)

`AuthorizeArranger`, `GetTravelerSnapshot` — see `contracts/protobuf/.../context/v1/context.proto`.
`travelos.trip.v1.Trip.allocation` and `CreateTripRequest.project_id` are additive.

## Phase 3 — planning vs purchase authorization (Travel Core, port 8081; ADR-0015)

### Trip request additions

- `POST /api/v1/trips`: `purchaseMode?` (`POLICY` default | `CONFIRM`), `draft?` (boolean),
  `intent.preferences?` `{cabin, nonstopOnly, refundableOnly, preferredCarriers[], maxStops}`.
- `TripResponse` gains `purchaseMode`, `purchase?` (the ACTIVE authorization), `alternatives?`
  `[{bundleId, total, summary, rank, refundable, changePenalty, conditions, selected}]`,
  `quoteExpiresAt?`, and `intent.preferences?`. New status value: `QUOTED`.

| Method & path | Who | Body / notes | Returns |
|---|---|---|---|
| `PUT /api/v1/trips/{id}/draft` | traveler, arranger, TRAVEL_ADMIN | same body as create (request/intent/purchaseMode); 409 `TRIP_NOT_A_DRAFT` | `TripResponse` |
| `POST /api/v1/trips/{id}/submission` (`Idempotency-Key`) | same | DRAFT → SUBMITTED, planning starts; idempotent by state | 202 `TripResponse` |
| `POST /api/v1/trips/{id}/purchase` (`Idempotency-Key`) | the buyer: traveler, arranger, TRAVEL_ADMIN (403 `NOT_THE_BUYER` otherwise) | `{bundleId?}`; trip must be `QUOTED` (409 `TRIP_NOT_QUOTED`), the named plan must be the quoted one (409 `SELECTION_CHANGED`), quote not expired (409 `QUOTE_EXPIRED`); idempotent by key and by state | `PurchaseView {authorizationId, status, basis, authorizedBy, bundleId, total, conditions, expiresAt, createdAt, supersededReason}` |
| `GET /api/v1/trips/{id}/purchase` | anyone who may read the trip | — | `[PurchaseView]` newest first (ACTIVE, CONSUMED, SUPERSEDED, REVOKED) |
| `POST /api/v1/trips/{id}/selection` (`Idempotency-Key`) | the buyer | `{bundleId}` from `alternatives`; 422 `ALTERNATIVE_UNKNOWN`; supersedes the authorization; the workflow re-quotes | `TripResponse` |
| `POST /api/v1/trips/{id}/quote-refresh` (`Idempotency-Key`) | the buyer | trip must be `QUOTED`; the workflow answers with a new quote (poll the trip) | 202 `TripResponse` |

Lifecycle a client should expect in `CONFIRM` mode: `SUBMITTED → PLANNING → QUOTED → (purchase) →
[AWAITING_APPROVAL → APPROVED | APPROVED] → BOOKING → BOOKED`. A higher re-quote before booking
returns the trip to `QUOTED`. In `POLICY` mode with an in-policy plan the sequence is unchanged
from before (`PLANNING → APPROVED → BOOKING → BOOKED`) and `purchase.basis` is `POLICY_AUTONOMY`.

### Conversations `/api/v1/conversations`

| Method & path | Who | Body | Returns |
|---|---|---|---|
| `POST` (`Idempotency-Key`) | any employee | `{text, purchaseMode?, projectId?}` | 202 `ConversationView {conversationId, travelerId, status (OPEN|AWAITING_USER|PLANNED|CLOSED), currentTripId, messages[{messageId, seq, role (USER|ASSISTANT), text, tripId, kind, createdAt}]}` |
| `GET` / `GET /{id}` | the traveler, the creator, TRAVEL_ADMIN (404 otherwise) | — | `ConversationView` |
| `POST /{id}/messages` (`Idempotency-Key`) | same | `{text}`; a new turn cancels an unbought plan of the previous turn and creates a new trip whose request text is the whole transcript | 202 `ConversationView` |

### Policy document additions

`autonomy.purchase {enabled, maxTotal}` (absent = legacy: enabled, unlimited), `trip.maxAdvanceDays`,
`trip.minLeadHours`, `trip.maxDurationDays`, `trip.onHorizonViolation`. `PolicyDecision` gains
`autonomous_purchase` and `autonomous_purchase_limit`.

### Events

`travel.trip.quoted`, `travel.trip.purchase-authorized` (see `contracts/events/trip-events.schema.json`).

## Phase 4 — supplier integrations (Supplier Gateway, gRPC; ADR-0016)

- `SupplierCapabilities` gains `mutations_idempotent`, `negotiated_rates_supported`,
  `reconciliation_by_key_supported`; `integration` is `SIMULATED` or `LIVE`. Providers: `sandbox-air`,
  `sandbox-hotel`, `sandbox-ground` (always), `duffel` and `hotelbeds` (only with credentials).
- `Offer` gains `negotiated`, `rate_code`; `OfferType` gains `RAIL`, `CAR` with `RailOffer` /
  `CarRentalOffer` details; `SearchRail` / `SearchCars` answer `NO_PROVIDER` until an adapter exists.
- `Passenger` gains `phone`, `date_of_birth`, `gender`, `title`, `documents[]`, `loyalty_program`;
  the workflow fills them from Enterprise Context at booking time. The Order service forwards and
  never stores them.
- Mutations: a retry with the same `ctx.idempotency_key` is answered from the gateway's ledger;
  the same key with a different request is `INVALID_ARGUMENT IDEMPOTENCY_KEY_REUSED`; a lost answer
  the gateway cannot reconcile or safely retry is `ABORTED OUTCOME_UNKNOWN`. The Order service then
  records the item as `UNKNOWN` with an `OUTCOME_UNKNOWN` exposure (order `PARTIALLY_FAILED`,
  `compensated=false`) for a person to resolve through the existing exposure resolution endpoint.
- Configuration (secrets mechanism only): `DUFFEL_ACCESS_TOKEN`, `DUFFEL_BASE_URL`,
  `HOTELBEDS_API_KEY`, `HOTELBEDS_SECRET`, `HOTELBEDS_BASE_URL`; see
  `docs/runbooks/supplier-credentials.md`.
- Locations: `Locations.place(iata)` / `places()` / `distanceKm(a, b)` in `libs/common` (a REST
  catalog endpoint is a Phase 11 handoff item).

## Phase 5 — finance ledger (Order service, port 8085; ADR-0017)

| Method & path | Who | Body / query | Returns |
|---|---|---|---|
| `GET /api/v1/orders/{id}/receipt` | the traveler, MANAGER/TRAVEL_ADMIN/FINANCE | — | `Receipt {orderId, tripId, travelerId, status, total, payment{paymentId, provider, providerRef, status, currency, authorizedMinor, capturedMinor, refundedMinor, failureCode, fx{settlementCurrency, settlementMinor, rate, source, quotedAt}}, paymentEvents[{kind, amount, providerRef, succeeded, detail, itemId, occurredAt}], instrument{kind, provider, label, last4, currency}, payables[], credits[], issuedAt}` |
| `GET /api/v1/finance/instruments` | FINANCE, TRAVEL_ADMIN | — | `[InstrumentView]` (tokens are never echoed in full; last4 only) |
| `POST /api/v1/finance/instruments` | FINANCE, TRAVEL_ADMIN | `{kind (CORPORATE_CARD|VIRTUAL_CARD|CENTRAL_BILL), provider (sandbox-payments|stripe), token, label?, last4?, currency, ownerEmployeeId?}`; 422 `PAN_NOT_ACCEPTED` for a card number | 201 `InstrumentView` |
| `DELETE /api/v1/finance/instruments/{id}` | FINANCE, TRAVEL_ADMIN | — | 204 (deactivated) |
| `GET /api/v1/finance/payables?provider=&status=&limit=` | FINANCE, TRAVEL_ADMIN | status SETTLED|DUE|INVOICED|PAID | `[PayableView {payableId, orderId, itemId, provider, externalRef, amount, method, status, invoiceReference, settledBy, settledAt}]` |
| `GET /api/v1/finance/balances` | FINANCE, TRAVEL_ADMIN | — | `{provider: {currency: amountMinor}}` of DUE + INVOICED |
| `POST /api/v1/finance/payables/{id}/settlement` (`Idempotency-Key`) | FINANCE | `{status: INVOICED|PAID, invoiceReference?, tripId?}`; 409 `PAYABLE_NOT_OPEN` | `PayableView` |
| `GET /api/v1/finance/credits?travelerId=&status=` | traveler (own); FINANCE/TRAVEL_ADMIN (tenant) | — | `[CreditView {creditId, travelerId, provider, reference, orderId, itemId, amount, status, expiresAt, appliedToOrderId, appliedBy, appliedAt, note}]` |
| `POST /api/v1/finance/credits/{id}/application` (`Idempotency-Key`) | FINANCE | `{orderId, note?}`; 409 `CREDIT_NOT_AVAILABLE` | `CreditView` (moves no money) |
| `GET /api/v1/finance/reconciliation?from=&to=` | FINANCE | ISO instants, ≤ 92 days | `{matched, mismatched, missingAtProvider, missingLocally, lines[{provider, providerRef, kind, local, atProvider, match, paymentId}]}` |

Order behaviour: `travel.order.failed` with `reasonCode PAYMENT_DECLINED` when the instrument
declines before any supplier is called; the payment token on `CreateOrderCommand` is either a
registered instrument's token or the legacy opaque token (an implicit sandbox instrument).
Configuration: `STRIPE_SECRET_KEY` (secrets mechanism; absent = sandbox only),
`travelos.finance.settlement.<provider>` = CARD_AT_SUPPLIER | BALANCE | INVOICE.
Events: topic `travel.finance` (schema `contracts/events/finance-events.schema.json`).

## Phase 6 — servicing, partial cancellation, traveler requests, cases (ADR-0018)

### Travel Core (port 8081)

| Method & path | Who | Body / query | Returns |
|---|---|---|---|
| `POST /api/v1/trips/{tripId}/components/{componentId}/cancellation` (`Idempotency-Key`) | the traveler, the arranger, TRAVEL_ADMIN (a manager who merely sees the trip: 403; a stranger: 404) | `{reason}`; 409 `TRIP_NOT_BOOKED`, `COMPONENT_NOT_CONFIRMED`, `NO_ORDER`; 404 unknown component | 202 `ComponentView` with `status: CANCELLING`; later `CANCELLED` or `CANCEL_FAILED` + `failureCode` on `GET /api/v1/trips/{id}` (`components[]`); the trip stays `BOOKED`. Repeats return the current state. |

Events: `travel.trip.component-cancellation-requested {tripId, orderId, componentIds[], reason, requestedBy}`,
`travel.trip.components-released {tripId, orderId, components[{componentId, type, status, provider, externalRef, total, failureCode, summary}]}`.

### Order (gRPC `CancelOrderCommand.component_ids`, port 9085)

A non-empty `component_ids` releases only those items (`ITEM_CANCELLED` with `refund`, or
`ITEM_CANCEL_FAILED` with an OPEN exposure); the order's status is unchanged; `FAILED_PRECONDITION
ORDER_NOT_CONFIRMED` unless CONFIRMED/CHANGED; `NOT_FOUND COMPONENT_UNKNOWN` when none of the ids is
on the order. Refunds and credits appear on `GET /api/v1/orders/{id}/receipt` and
`GET /api/v1/finance/credits`. Event `travel.order.items-released {orderId, tripId, items[],
refused[exposure], refund, reason, releasedBy}`.

### Disruption (port 8089)

| Method & path | Who | Body / query | Returns |
|---|---|---|---|
| `POST /api/v1/disruptions/requests` (`Idempotency-Key`) | the order's traveler, TRAVEL_ADMIN (anyone else: 404) | `{tripId, orderId, componentId, notBefore, notAfter?, reason?}`; 404 unknown order/component; 409 `ORDER_NOT_CHANGEABLE`, `COMPONENT_NOT_CONFIRMED`, `ORDER_SERVICE_UNAVAILABLE`; 422 `COMPONENT_NOT_RETIMEABLE` (not a flight), `WINDOW_IN_PAST`, `WINDOW_INVALID` | 201 `DisruptionView` with `type: TRAVELER_REQUEST`, `status: IMPACT_CONFIRMED`, `affected.requestedNotBefore/requestedNotAfter/requestedBy`; the same key again returns the same disruption. The recovery then runs as for any disruption (`GET /api/v1/disruptions/{id}`, approval at `/approval` when policy requires a manager). |

### Assistance (new service, port 8092; nginx route `/api/v1/cases`)

| Method & path | Who | Body / query | Returns |
|---|---|---|---|
| `GET /api/v1/cases?status=&queue=&kind=&owner=me|<principal>&tripId=&overdue=&includeClosed=&limit=` | TRAVEL_ADMIN, FINANCE (all); a traveler (own trips only) | open cases by default, most urgent first | `[CaseView {caseId, kind, status, priority, queue, title, summary, tripId, orderId, travelerId, disruptionId, exposureId, componentId, owner, nextAction, nextActionRole, escalationLevel, dueAt, overdue, openedAt, updatedAt, resolvedAt, closedAt, resolution, sourceEventType, version}]` |
| `GET /api/v1/cases/summary` | TRAVEL_ADMIN, FINANCE | — | `{open, overdue, byQueue{queue{status: count}}}` |
| `GET /api/v1/cases/{id}` / `GET /api/v1/cases/{id}/events` | as above; 404 otherwise | — | `CaseView` / `[EventView {caseEventId, kind (OPENED, LINKED, NOTE, ASSIGNED, STATUS, ESCALATED, RESOLVED, REOPENED, CLOSED), actor, message, data, occurredAt}]` |
| `POST /api/v1/cases` (`Idempotency-Key`) | a traveler (own trip), TRAVEL_ADMIN, FINANCE | `{kind (TRAVELER_REQUEST, SAFETY, OTHER, …), priority?, title, summary?, tripId?, orderId?}` | 201 `CaseView` (SAFETY → CRITICAL on the SAFETY queue) |
| `POST /api/v1/cases/{id}/assignment` | TRAVEL_ADMIN, FINANCE (a traveler: 403) | `{owner: "me" \| principal id}`; 409 `CASE_NOT_OPEN` | `CaseView` (`IN_PROGRESS`) |
| `POST /api/v1/cases/{id}/notes` | anyone who may read it | `{text}` | 201 `CaseView` |
| `PUT /api/v1/cases/{id}/status` | TRAVEL_ADMIN, FINANCE | `{status: IN_PROGRESS \| WAITING \| OPEN (reopen a resolved case), reason?}`; 409 `STATUS_TRANSITION_INVALID`; 422 `STATUS_NOT_SETTABLE` | `CaseView` |
| `POST /api/v1/cases/{id}/escalation` | TRAVEL_ADMIN, FINANCE | `{reason}` | `CaseView` (level +1, priority raised, fresh `dueAt`, `nextActionRole` widens to TRAVEL_ADMIN then TRAVEL_ADMIN+FINANCE) |
| `POST /api/v1/cases/{id}/resolution` | TRAVEL_ADMIN, FINANCE | `{resolution}` | `CaseView` (`RESOLVED`) |
| `POST /api/v1/cases/{id}/closure` | TRAVEL_ADMIN, FINANCE | `{reason?}`; 409 `CASE_NOT_RESOLVED` | `CaseView` (`CLOSED`) |

Cases open by themselves from `travel.order.compensation-failed` / `items-released` (EXPOSURE per
open exposure), `travel.order.failed` items with status UNKNOWN (OUTCOME_UNKNOWN),
`travel.trip.cancellation-incomplete` and `components-released` CANCEL_FAILED
(CANCELLATION_INCOMPLETE), `travel.trip.failed` (BOOKING_FAILED),
`travel.disruption.approval-required` (RECOVERY_APPROVAL, CRITICAL, APPROVALS queue),
`travel.disruption.recovery-failed` (RECOVERY_FAILED), `travel.finance.payment-declined`
(PAYMENT_DECLINED); and resolve by themselves from `exposure-resolved`, `trip.cancelled`,
`components-released` CANCELLED, `disruption.resolved`, `payment-authorized/captured`. SLAs:
`travelos.assistance.sla.{critical,high,normal,low}` (1h/4h/24h/72h), sweep
`travelos.assistance.escalation-sweep` (1m), max level 3. Events: topic `travel.assistance`
(`case-opened`, `case-assigned`, `case-escalated`, `case-resolved`, `case-closed`; schema
`contracts/events/assistance-events.schema.json`). Configuration: `ASSISTANCE_DB_URL/USER/PASSWORD`
(secrets mechanism), `ASSISTANCE_URL` on the web edge.
