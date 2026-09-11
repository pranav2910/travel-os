# travel-os — working notes for Claude Code

Autonomous enterprise travel platform. Java 21 / Spring Boot 4 services, Python for optimization + AI,
Temporal for workflows, Kafka for domain events, Postgres per service. The frozen design lives in
`docs/architecture/00-design-package.md`; decisions that deviate from or refine it are ADRs in `docs/adr/`.

## The one rule

```
LLM understands and plans.   Optimizer chooses.   Policy engine authorizes.
Workflow engine coordinates. Transaction services execute. Kafka tells the rest what happened.
```

An LLM proposal is never an authorization. Policy is deterministic and explainable; every decision
produces evidence (decision id, policy version, rules evaluated, reason codes).

## Hard invariants (tests exist or must be added for each)

- **Offer != Order.** Search results are ephemeral; orders are the transactional truth.
- **Every mutating command carries an `IdempotencyKey`** (`<scope>:<COMMAND>:<n>`, see `libs/common`).
  N retries => 1 logical booking. `UNIQUE(idempotency_key)` in the owning table.
- **No service writes another service's database.** Enforced by credentials: one Postgres DB + one role
  per service (`platform/local/postgres/init`).
- **Every business object carries `tenant_id`**, and every query filters by it. Knowing a UUID is not access.
- **Money is `(currency, amount_minor)`** — never floating point.
- **Events use the standard envelope** (`contracts/events/event-envelope.schema.json`); topic = first two
  segments of the event type (`travel.order.confirmed` -> `travel.order`). Topics are declared in
  `contracts/events/topics.yaml`, never auto-created.
- **Agents are machine identities** (`agent/<name>/<version>`) with scoped capabilities, never `*`.
- **Policy decisions carry economics** (reference fare, ceiling, traveler incentive, traveler pays): policy is an
  incentive, not only a gate. Every evaluation is persisted as evidence and emitted as an event.
- **gRPC is grpc-java hosted directly** (`libs/spring-grpc-support`), not spring-grpc: its Boot 4 line was not
  cleanly published when we started. Every internal call validates `RequestContext` (tenant, principal, correlation).

## Layout

- `contracts/` protobuf (internal gRPC), JSON-schema events, OpenAPI (public REST `/api/v1`)
- `libs/` shared Java (`common`: ids, money, tenant, idempotency, principal; `events`: envelope + codec + schema test fixtures;
  `spring-web`: JWT tenant principal, problem details, Idempotency-Key, test tokens; `spring-outbox`: transactional outbox;
  `spring-grpc-support`: grpc-java hosting, RequestContext validation)
- `services/` Spring Boot services, one directory each, own DB, own Flyway migrations
- `intelligence/` Python: optimization (OR-Tools), llm-gateway, agent-runtime
- `workflows/` Temporal workflow definitions + workers
- `platform/local/` docker compose for local dev (Postgres 17, Redis 8, Kafka 4 KRaft, Temporal, Keycloak)
- `docs/` architecture, ADRs, runbooks

## Build & test

- `make up` / `make down` — local platform. `make check` — full Gradle verification. `make fmt` — google-java-format.
- Build tool: Gradle 9 + Kotlin DSL, convention plugins in `build-logic/` (`travelos.java-library`,
  `travelos.spring-boot-service`). Versions only in `gradle/libs.versions.toml`.
- Gate checks must fail on non-zero exit codes; never pipe test output through `head`/`grep` in a way that hides a FAIL.
- Integration tests use Testcontainers; no test may depend on `make up` having been run.

## Delivery discipline

Slice 1 is the only scope until its definition-of-done is green (see README). Do not scaffold services
that Slice 1 does not deploy.
