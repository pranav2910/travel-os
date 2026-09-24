# ADR-0017: Money is recorded before it moves; the Order service's finance ledger is authoritative; providers convert, the platform records

Status: accepted (platform completion, Phase 5, 2026-09-24)

## Context

Until Phase 4 the platform's idea of payment was a placeholder token string forwarded to suppliers,
and its idea of a refund was whatever a sandbox answered plus a statement a Finance user could type
into the Learning service. Nothing recorded an authorization, a capture, a void, a refund, what the
platform owed a supplier, or the value an airline kept as a credit. Nothing reconciled against a
provider. Currencies were single and conversions unrepresentable.

## Decision

1. **The finance ledger lives in the Order service** (`services/order`, package `finance`,
   migration `V6`): orders are transactional truth (ADR-0004) and their money is part of that
   truth. One database, one role, as before (ADR-0006).
2. **Instruments are tokens, never numbers.** `payment_instrument` holds the provider's opaque
   token (`pm_…`, or the sandbox's), a label and the last four characters. A value that passes the
   Luhn check is refused on registration (`PAN_NOT_ACCEPTED`) and at payment time. The Slice 1
   opaque token the workflow still carries resolves to an implicit sandbox instrument recorded once.
3. **Every movement is a row before it is a fact at the provider.** `payment` (one per order:
   authorized, captured, refunded amounts in the order's currency, the provider's reference, FX
   provenance) and `payment_event` (append-only: AUTHORIZE, CAPTURE, VOID, REFUND, DECLINE, each
   with an idempotency key the provider honours or the adapter enforces). The saga:
   authorize the order total before the first supplier call; a decline fails the order before any
   booking (`PAYMENT_DECLINED`); capture what the suppliers charged once every item is confirmed;
   void when the order fails before capture; refund per released item on cancellation, never more
   than captured; a costlier change is a second authorization and capture for the difference. A
   retried saga records nothing twice.
4. **Providers convert; the platform records provenance.** The instrument's currency may differ
   from the order's. The provider settles in its currency and reports the rate; the ledger keeps
   `settlementCurrency`, `settlementAmountMinor`, `rate`, `source`, `quotedAt` on the payment and in
   the events. The platform computes no rate (ADR-0007 stands).
5. **Two providers.** `sandbox-payments` (SIMULATED, deterministic, its own ledger table so
   reconciliation has a provider side, a static FX table labelled as such, declines tokens that say
   so) and `stripe` (LIVE, present only with `STRIPE_SECRET_KEY`: PaymentIntents with manual
   capture, off-session saved payment methods, capture, cancel, refunds, balance transactions;
   `Idempotency-Key` on every POST; `sk_test_` recognised as test mode). Contract-tested against
   documented shapes; no live payment is made by any test.
6. **What is owed to suppliers is a record.** `supplier_payable` per confirmed item with the
   provider's settlement method (`CARD_AT_SUPPLIER` settled at once; `BALANCE` and `INVOICE` DUE
   until Finance marks them INVOICED / PAID with a reference). Balances per provider and currency
   are the sum of what is due.
7. **Credits are value, not money.** A supplier's `credit` on cancellation (the sandbox airline
   keeps fare − fee for non-refundable tickets, valid a year) becomes a `travel_credit`
   (AVAILABLE → APPLIED when Finance records the supplier applied it, EXPIRED by a sweep). It never
   nets against a payment.
8. **Receipts and reconciliation are read from the ledger.** `GET /api/v1/orders/{id}/receipt`
   assembles order, payment, events, instrument, payables and credits.
   `GET /api/v1/finance/reconciliation?from&to` matches every successful payment event to the
   provider's transactions by reference: MATCHED, AMOUNT_MISMATCH, MISSING_AT_PROVIDER,
   MISSING_LOCALLY.
9. **The Learning refund endpoint moves no money and never did.** It records a Finance statement
   for the learning ledger. Learning now also consumes `travel.finance.payment-refunded`, so a
   settled refund reaches the outcome ledger from the authoritative record; the manual endpoint
   stays as a statement about refunds the platform did not process itself, and says so.
10. **`travel.finance` is a topic** with payment, payable and credit events (schema and examples in
    `contracts/events`), so Audit, Learning and reporting read the same facts.

## Consequences

- "What did we charge, refund, owe and credit for this trip?" has one answer per order.
- Stripe is verifiable without code changes: set the test secret, register a `pm_` token, book a
  sandbox trip; until then the matrix says "implemented, provider-test-blocked".
- Not here: multi-instrument split payments, traveler-paid (personal card) portions of
  TRAVELER_PAYS policy outcomes, invoicing documents (PDF), and applying credits automatically at a
  supplier during booking (the supplier applies; Finance records).

## Verification

`FinanceIntegrationTest` (instrument registration refuses a card number; authorize → capture with
events and provider references; a decline fails the order before any supplier call; cancellation
refunds per item and issues the airline's credit; receipt; payables by settlement method and their
settlement; reconciliation against the sandbox provider's ledger; FX provenance for a EUR
instrument), `StripePaymentProviderTest` (scripted HTTP contract), `LearningIntegrationTest`
(finance refund event → REFUND_SETTLED outcome), event contracts.
