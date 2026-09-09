# ADR-0004: Offers are ephemeral supplier promises; Orders are transactional truth

**Status:** accepted · **Date:** 2026-09-09

## Context
Airline distribution (NDC / ONE Order) is built around Offers (what a seller will sell right now,
with an expiry) and Orders (what was actually sold). Conflating them produces phantom bookings,
stale prices at checkout and unrecoverable sagas.

## Decision
- `travelos.offer.v1.Offer` carries `provider`, `provider_offer_id`, `expires_at` and a normalized
  payload. Offers live in the search session and Redis; they are never the source of truth.
- `travelos.order.v1.Order` is created only by the Order service, only through an idempotent
  command, and carries the evidence chain (`policy_decision_id`, `optimization_run_id`, `approval_id`).
- Booking re-prices (`SupplierGateway.PriceOffer`) before `CreateOrder`; a changed price re-enters
  policy evaluation instead of being silently accepted.
- Services never see vendor payload shapes; the Supplier Gateway normalizes everything.

## Consequences
- Search can be cached, retried and discarded freely; orders cannot, and the code makes that obvious.
- Adding a GDS/NDC adapter changes zero business logic.
