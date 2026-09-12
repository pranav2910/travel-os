# Architecture Decision Records

Short, numbered, immutable once accepted (supersede with a new one). The frozen design package in
`docs/architecture/00-design-package.md` is the baseline; ADRs record where implementation refined it.

| # | Decision |
|---|---|
| [0001](0001-monorepo-with-gradle-convention-plugins.md) | Monorepo, Gradle 9 + Kotlin DSL, convention plugins, one version catalog |
| [0002](0002-temporal-and-kafka-have-different-jobs.md) | Temporal coordinates processes; Kafka broadcasts facts |
| [0003](0003-llm-never-authorizes.md) | The LLM proposes; deterministic policy authorizes; agents are machine identities |
| [0004](0004-offer-is-not-order.md) | Offers are ephemeral supplier promises; Orders are transactional truth |
| [0005](0005-idempotent-transaction-commands.md) | Every mutating command carries an idempotency key; uniqueness enforced in the owning table |
| [0006](0006-one-database-per-service-enforced-by-credentials.md) | One Postgres database and one login role per service |
| [0007](0007-money-in-minor-units.md) | Money is (ISO currency, integer minor units); no floating point, no implicit FX |
| [0008](0008-ids-are-prefixed-ulids.md) | Ids are `<prefix>_<ULID>`; tenant ids are slugs, not UUIDs |
| [0009](0009-llm-gateway-task-shaped-and-evidenced.md) | One LLM gateway; task-shaped calls; every call leaves evidence; narration degrades, transactions do not |
