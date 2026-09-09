# ADR-0005: Every mutating command carries an idempotency key; uniqueness enforced in the owning table

**Status:** accepted · **Date:** 2026-09-09

## Context
Double clicks, browser retries, gRPC retries and Temporal activity retries can all deliver the same
`CreateOrder` twenty times. Twenty airline tickets is a company-ending bug.

## Decision
- `RequestContext.idempotency_key` is required on `CreateOrder`, `ChangeOrder`, `CancelOrder`,
  approval decisions and refunds. Format `<scope>:<COMMAND>:<n>` (see `IdempotencyKey` in `libs/common`).
- The owning table has `UNIQUE(idempotency_key)`. The first command creates; every repeat returns
  the existing row. No Redis lock is the source of this guarantee.
- `<n>` is bumped only for a genuinely new business transaction (re-plan after a cancellation),
  never for transport retries.
- Supplier calls behind the gateway are made retry-safe per adapter (supplier-side idempotency
  where offered; otherwise hold-then-confirm with reconciliation).

## Consequences
- A chaos test that replays every command N times must leave exactly one order. It is a release gate.
