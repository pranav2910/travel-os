# ADR-0009: One LLM gateway; task-shaped calls; every call leaves evidence; narration degrades, transactions do not

**Status:** accepted · **Date:** 2026-09-12

## Context
ADR-0003 says the model never authorizes. This ADR says how the model is *used*. Slice 1 needs two
things from a language model: turn "I need to be in Seattle before 9am Tuesday" into a
`TravelIntent`, and tell a person why the plan is what it is. Both are easy to do badly: raw chat
endpoints scattered through services, prompts nobody versions, no cost ceiling, and a model whose
self-reported "I extracted it" is trusted.

## Decision
- **One service** (`intelligence/llm-gateway`, gRPC `travelos.llm.v1.LlmGateway`) is the only
  process that holds a model API key. Services and workflows call it; nothing else imports an SDK.
- **The API is task-shaped, never "a completion".** `ExtractIntent(request_text, reference_time,
  timezone, home_airport)` and `ExplainTrip(evidence)`. The output schema for extraction has no
  field for cabin, budget, approver, supplier or payment: instructions smuggled into the text have
  nowhere to land. Structured JSON output pins the model to that schema.
- **The model's answer is a proposal.** The gateway re-validates every field (IATA codes, date
  ordering, not in the past relative to `reference_time`, travelers 1-9) and downgrades anything
  doubtful to `NEEDS_CLARIFICATION` with one concrete question. Travel Core validates again when it
  freezes the intent. Policy evaluates the frozen intent like any other.
- **Every call leaves evidence.** `ModelCall {call_id, provider, model, prompt_id, prompt_version,
  provider_request_id, tokens, cache reads, latency, cost}` travels with the result and is written
  to Travel Core's `agent_decision` ledger, keyed by `model_call_id` so a retried activity cannot
  ledger a call twice. Request text is never logged by the gateway.
- **Budgets are enforced where the money is spent.** A per-tenant daily ceiling returns
  `RESOURCE_EXHAUSTED`; the workflow fails the trip at stage INTENT instead of retrying forever.
- **Narration degrades; transactions do not.** `ExplainTrip` runs after optimization with a short
  retry policy; if it fails the trip proceeds without an explanation. Intent extraction is on the
  critical path by nature: if the gateway is down the trip fails cleanly at INTENT and the traveler
  can resubmit with a structured intent.
- **Providers are pluggable and the fake one is first-class.** `anthropic` (official SDK, Claude
  Opus 5, prompt caching on the system prompt) and `fake` (deterministic rules). CI, tests and the
  end-to-end script run on `fake`; the same code path runs on `anthropic` with a key.

## Consequences
- "Why did the system think I wanted BOS?" is answered from the ledger: model, prompt version,
  confidence, stated assumptions.
- Prompt changes are versioned data (`prompt_id` + `prompt_version` in every evidence row), so an
  eval set can be built against them later (Slice 5).
- The free-text path costs one model call per trip plus one for narration; the budget bounds it.
- `home_airport` is empty until the Enterprise Context service exists; requests that omit an origin
  get a clarifying question rather than a guess.
