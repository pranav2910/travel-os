# optimization

Chooses among policy-permitted candidate bundles and returns the **whole ranking with a score
breakdown**, so "why this one?" is answerable without re-running anything. Implements
`travelos.optimization.v1.OptimizationService.OptimizeTrip` from `contracts/protobuf`.

```
./gradlew :intelligence:optimization:check     # ruff + pytest (also part of the root check)
make run-optimization                          # gRPC on :9083 (GRPC_PORT to override)
```

Or directly: `uv sync && uv run python scripts/gen_proto.py && uv run pytest`.

## How a candidate is scored

Hard constraints make a candidate infeasible (score 0, reasons attached): arrival deadline,
return-after, allowed cabins, max total, permitted providers, max stops, currency.

Soft objectives, each normalized to 0..100 across the *feasible* candidates of one request:

| objective | 100 means | 0 means |
|---|---|---|
| cost | the cheapest feasible bundle | the most expensive |
| time | the shortest total flying time | the longest |
| risk | nonstop journeys with comfortable connections | many stops or tight connections |
| preference | every carrier is a preferred carrier | none is |
| experience | departures at civilised hours | red-eyes and pre-dawn departures |

`score = Σ wᵢ·scoreᵢ / Σ wᵢ` with the caller's weights (defaults 0.40 / 0.25 / 0.15 / 0.10 / 0.10).
Selection is a CP-SAT model choosing exactly one feasible bundle that maximizes the score, with
lower cost and then bundle id as deterministic tie-breakers. Same input, same output, always.
