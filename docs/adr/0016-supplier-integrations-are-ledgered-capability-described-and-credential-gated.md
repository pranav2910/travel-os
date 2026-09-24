# ADR-0016: Supplier integrations are ledgered, capability-described and credential-gated; a lost answer is a known unknown

Status: accepted (platform completion, Phase 4, 2026-09-24)

## Context

Every supplier in the repository was SIMULATED (`sandbox-*`). The sandboxes honour our
idempotency key and can be asked what they did with it, so the Order saga's "ask before you retry"
reconciliation worked. Real suppliers are not like that: Duffel does not dedupe order creation by
a client key and cannot find an order by one; Hotelbeds keeps a 20-character client reference and
lets us list bookings by it. Passengers need dates of birth, phones and documents, which the trip
snapshot never carried. Rail and car rental were folded into "ground". Locations had time zones
but no positions, so no hotel supplier could be asked "near here".

## Decision

1. **The gateway keeps a mutation ledger** (`supplier_mutation_attempt`, `V6`; `MutationLedger`).
   Every CreateOrder, ChangeOrder and CancelOrder is written down *before* the supplier is called,
   keyed by (tenant, provider, command, idempotency key) with a digest of the request. A retry with
   the same key and request is answered from the ledger (SUCCEEDED, or the same final refusal);
   the same key with a different request is refused (`IDEMPOTENCY_KEY_REUSED`). A lost answer
   (timeout, transport, 5xx) leaves the attempt UNKNOWN. On the next call the gateway asks the
   adapter to look the booking up by our key or by the reference it already knows; a booking it
   made is adopted, never made twice. When the supplier neither honours our key nor lets us look
   it up, the call is refused with `OUTCOME_UNKNOWN` (gRPC `ABORTED`) and nobody retries by
   machine. Cancellations use the external order id as their key, so cancelling twice is one call.
2. **The Order saga represents the unknown honestly.** An item whose booking outcome is unknown
   becomes `UNKNOWN` (never FAILED, never CONFIRMED) with an OPEN exposure `OUTCOME_UNKNOWN` for the
   full amount; the order is `PARTIALLY_FAILED` with `compensated=false` and the compensation
   events name the exposure. A person reconciles against the supplier and the ledger (`calls`,
   `external_ref` when known) and resolves the exposure; Phase 6 cases build on this.
3. **Capabilities say what an adapter really is.** `SupplierCapabilities` gains
   `mutations_idempotent`, `reconciliation_by_key_supported` and `negotiated_rates_supported`
   beside `integration` (SIMULATED | LIVE). The ledger reads them to decide whether a retry is
   safe; the capability matrix reads them to say what is verified.
4. **Two real adapters, present only when credentials are.** `duffel` (air; Duffel API v2:
   offer requests with private fares for negotiated corporate codes, offer re-pricing, instant
   orders paid from the Duffel balance, cancellation create + confirm, order lookup by id) and
   `hotelbeds` (hotels; APItude 1.0: availability by the catalogued airport's coordinates,
   check-rates, bookings with our client reference, cancellation with the refund computed from
   what the hotel keeps, lookup by reference and by client reference). Credentials come from
   `travelos.integrations.*` = `DUFFEL_ACCESS_TOKEN`, `HOTELBEDS_API_KEY`, `HOTELBEDS_SECRET`
   through the secrets mechanism only (compose passes the shell's values; kind takes them from the
   shell into the Secret; EKS maps Secrets Manager entries). No credential has a default; an
   adapter without one does not exist, and the gateway says SIMULATED for everything it has.
   Test tokens and the Hotelbeds test host are recognised and reported; nothing in the repository
   ever books against a live endpoint in a test.
5. **Passengers are read at booking time, not carried.** The workflow asks Enterprise Context for
   the passenger (purpose `BOOKING`, documents included) under the platform's own agent identity
   right before CreateOrder; Enterprise Context grants the SYSTEM relation to a non-human principal
   for that purpose only, logs the read under the agent, and refuses any other purpose. The Order
   service forwards the passenger and never persists it. When the profile is unknown or Enterprise
   Context is down, the trip's own snapshot (names, email) goes to the supplier; a supplier that
   needs more refuses with `PASSENGER_DETAILS_INCOMPLETE` / `PASSENGER_DOCUMENT_REQUIRED`.
6. **Rail and car rental are their own kinds.** `OfferType.RAIL` / `CAR`, `RailOffer` /
   `CarRentalOffer`, `SearchRail` / `SearchCars`, `RailSupplier` / `CarRentalSupplier`. No
   adapter is registered (neither SIMULATED nor LIVE); the gateway answers `NO_PROVIDER` so the
   absence is visible, not silent.
7. **Locations carry positions.** `Locations.Place` (city, country, latitude, longitude) for every
   catalogued airport, `distanceKm`, and `places()` for suppliers and the frontend. Hand-curated;
   a fuller dataset can replace the map without changing callers.
8. **Negotiated rates are marked, not invented.** `Offer.negotiated` / `rate_code` are set by the
   adapter from what the supplier reports (Duffel private fares); policy and reporting can read
   them. Supplier agreements as governed objects are Phase 7.

## Consequences

- A double booking now requires two different keys; a lost answer is visible in one table.
- Live verification is possible without code changes: set the credentials, run the
  credential-gated `LiveSupplierSmokeTest` (search only) and the stack. Until then the contract
  tests prove the request bodies and the response mapping against the documented shapes, and the
  matrix says "implemented, provider-test-blocked".
- Duffel's `gender` accepts m|f only; an X profile is sent as m and this is a documented
  limitation to raise with the supplier.
- Not here: supplier webhooks for Duffel order changes, Duffel order changes (a change is cancel +
  create, as the capability says), Hotelbeds rate re-check on RECHECK rates beyond the quote step.

## Verification

`MutationLedgerIntegrationTest` (same key answered from the ledger; key reuse refused; lossy
supplier → UNKNOWN then OUTCOME_UNKNOWN with exactly one booking attempted; reconcilable supplier
→ adopted by lookup; rail/car NO_PROVIDER; sandbox capabilities), `DuffelAirSupplierTest` and
`HotelbedsHotelSupplierTest` (scripted HTTP contract tests), `LiveSupplierSmokeTest`
(credential-gated, skipped without credentials), `OrderIntegrationTest` (unknown outcome →
UNKNOWN item + exposure), `ProfileIntegrationTest` (agent reads for BOOKING, logged; other
purposes refused), `TripWorkflowTest` (passenger from Enterprise Context, fallback to snapshot).
