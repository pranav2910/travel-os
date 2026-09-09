# travel-os

An autonomous enterprise travel platform. It understands a travel need, loads enterprise context,
searches supplier inventory, filters by **deterministic** corporate policy, optimizes the compliant
choices, obtains human approval when policy demands it, books through a supplier adapter, and keeps
an immutable, explainable decision trail — then keeps servicing the trip when the world changes.

```
LLM understands and plans.      Optimizer chooses.        Policy engine authorizes.
Workflow engine coordinates.    Transaction services execute.    Kafka tells the rest what happened.
```

The agent is one component inside a reliable enterprise transaction system, not the system.

## Status

**Slice 1 in progress** — an employee explicitly requests a US-domestic, single-traveler, economy
round trip; the platform plans, governs, books and audits it end to end. Nothing else ships until
every box below is checked, in a real deployment, with real tests.

| Slice 1 definition of done | | |
|---|---|---|
| ☐ real authentication (OIDC) | ☐ tenant isolation | ☐ Postgres persistence |
| ☐ versioned APIs | ☑ provider abstraction (contract) | ☐ deterministic policy |
| ☐ optimization engine | ☐ durable workflow | ☐ booking idempotency |
| ☐ retry-safe supplier calls | ☑ Kafka event contracts | ☐ audit trail |
| ☐ distributed tracing | ☐ metrics | ☐ integration tests |
| ☑ contract tests | ☐ E2E happy path | ☐ failure-path tests |
| ☐ Docker images | ☐ Terraform | ☐ EKS deployment |
| ☑ CI pipeline | ☐ CD pipeline | ☐ rollback |

Roadmap after that: **Slice 2** autonomous disruption recovery · **Slice 3** hotel/ground/multi-city
· **Slice 4** calendar/CRM/HRIS/expense integration (detect demand before a request exists) ·
**Slice 5** learning from outcomes.

## Architecture in one screen

```
 Traveler / Manager / Admin (React)      Enterprise IdP (OIDC)      HRIS · Calendar · Expense
                │                                 │                          │
                ▼                                 ▼                          ▼
          ┌───────────────────────── Experience API / BFF ───────────────────────────┐
          │                                                                          │
          │  Travel Core ──► Temporal workflow ──► Context ─► Search ─► Policy ─► Optimizer
          │                        │                                    │           │
          │                        ├──────────────────────────► Approval (human)    │
          │                        ▼                                                │
          │                   Order service ──► Supplier Gateway ──► Sandbox / NDC / GDS
          │                        │
          └────────────────────────┼──────────────────────────────────────────────────┘
                                   ▼
                        Kafka  ──►  Audit · Notification · Expense · Disruption monitor
```

- **Temporal** answers "what step of this business process are we in?" (durable, resumable).
- **Kafka** answers "what happened?" (fan-out to everyone who cares). Neither does the other's job.
- **Policy** is a versioned rule engine that returns evidence (`decisionId`, `policyVersion`,
  `rulesEvaluated`, `reasonCodes`), never a bare `true`.
- **Agents** are machine identities (`agent/disruption-recovery/v1`) whose proposals pass schema
  validation → capability check → policy → business validation before any tool runs.

The complete design package is in [docs/architecture/00-design-package.md](docs/architecture/00-design-package.md).
Decisions that refine it are recorded as [ADRs](docs/adr/).

## Repository layout

```
contracts/    protobuf (internal gRPC) · JSON-schema events + examples · topic registry · OpenAPI
libs/         common (ids, money, tenant, idempotency, principal) · events (envelope, codec, topics)
services/     Spring Boot services — one directory, one database, one identity each
intelligence/ Python — optimization (OR-Tools), llm-gateway, agent-runtime
workflows/    Temporal workflows and workers
platform/     local docker compose; later kafka/temporal/observability platform config
infrastructure/ terraform · helm · argocd
docs/         architecture · adr · runbooks
```

## Local development

Prerequisites: JDK 21 (`brew install openjdk@21`), Docker Desktop (≥ 6 GB for the platform), Python 3.12+ with `uv`.

```bash
make up          # postgres, redis, kafka (topics created), temporal (+ UI :8233), keycloak (:8180)
make check       # compile, format check, all tests
make down        # stop; make nuke wipes data
```

Ports, credentials and how to mint a dev token: [docs/runbooks/local-dev.md](docs/runbooks/local-dev.md).

## Engineering rules

See [CLAUDE.md](CLAUDE.md) for the invariants every change must respect. The short version:
Offer ≠ Order · every command is idempotent · every row has a tenant · money is integer minor units ·
one database per service, enforced by credentials · every event is enveloped · the LLM never authorizes.
