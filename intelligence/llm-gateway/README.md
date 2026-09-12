# llm-gateway

The only door to language models (ADR-0003, ADR-0009). gRPC `travelos.llm.v1.LlmGateway`:

- `ExtractIntent`: untrusted free text -> structured `TravelIntent`, or a clarifying question, or
  "not a travel request". Structured JSON output pins the model to a schema that has no field for
  cabins, budgets, approvers or suppliers; the gateway then re-validates every field (IATA codes,
  date ordering, not in the past) and downgrades anything doubtful to a question.
- `ExplainTrip`: narrates a planning decision from its evidence (ranking, policy decision). It is
  never a source of truth; the evidence is, and it is stored with the trip.

Every response carries `ModelCall` evidence (provider, model, prompt id + version, tokens, cost,
latency, provider request id) which Travel Core writes to the agent-decision ledger.

Providers: `anthropic` (official SDK, `claude-opus-5`, prompt caching on the system prompt) and
`fake` (deterministic rules, offline). `LLM_PROVIDER` selects; unset means anthropic when
`ANTHROPIC_API_KEY` is present, fake otherwise. A per-tenant daily budget
(`LLM_TENANT_DAILY_BUDGET_USD`, default 5) returns `RESOURCE_EXHAUSTED` when exceeded.

Run: `make run-llm-gateway` (port 9087). Tests: `uv run --frozen pytest`.
