# ADR-0007: Money is (ISO currency, integer minor units)

**Status:** accepted · **Date:** 2026-09-09

## Decision
`Money { currency, amount_minor }` everywhere: protobuf, events, database (`BIGINT` + `CHAR(3)`),
Java (`io.travelos.common.money.Money`). `820.00 USD` is `82000`. Arithmetic is integer and
overflow-checked; combining currencies without an explicit FX step throws. Public REST renders
major units as strings (`"820.00"`) at the edge only.

## Consequences
- No floating-point drift in totals, deltas or policy thresholds.
- The design package's `DECIMAL(12,2)` columns become `BIGINT amount_minor` + `CHAR(3) currency`.
