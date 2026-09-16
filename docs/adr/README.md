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
| [0010](0010-disruption-recovery-is-the-same-machine-with-a-second-trigger.md) | Disruption recovery reuses the planning machine: gateway-normalized notices, a Disruption aggregate with immutable decision/outcome records, a recovery workflow that asks policy `order.change`, one idempotent order change |
| [0011](0011-itineraries-are-components-with-one-transactional-truth.md) | Slice 3: itineraries are ordered components with stable ids; one supplier abstraction for air/hotel/ground with SIMULATED sandboxes; policy judges every offer and the whole; CP-SAT composes; Temporal keeps coordination state, Order keeps transactional truth; revalidation before the only mutation; exposure is a record; connected recovery |
| [0012](0012-demand-detection-is-deterministic-context-not-authority.md) | Slice 4: Enterprise Context owns identity and demand; deterministic, versioned rules with quoted evidence; one candidate per commitment with every source kept; explicit lifecycle; a person converts through Travel Core's CreateTrip (idempotent, arranger rule); sync is a Temporal workflow page by page with checkpoints at the write boundary; Slice 3 carry-overs |
| [0013](0013-learning-is-bounded-evaluated-and-reversible.md) | Slice 5: Learning owns an append-only outcome ledger and versioned profiles; `reliability-v1` is a smoothed, windowed, bounded soft preference (never money, never a hard rule); outcomes are recorded, never inferred, one row per logical revision; SANDBOX/LIVE classes never mix; chronological leak-free evaluation gates activation; OFF/SHADOW/ACTIVE with atomic, audited, reversible activation; planners pin inputs through one activity and fall back to the baseline |
