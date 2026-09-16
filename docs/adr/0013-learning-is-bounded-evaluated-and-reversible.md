# ADR-0013: Learning from outcomes is a versioned, evaluated, bounded soft preference; policy stays deterministic

Status: accepted (Slice 5, 2026-09-16)

## Context

Slice 5 asks the platform to improve future rankings from what actually happened: which suppliers
honoured bookings, which cancelled on travelers, which refused to release a held item, what
travelers explicitly said afterwards. The design package names an analytics/learning consumer of the
event stream and keeps the optimizer as the only component that ranks. The rule of the house does
not change: the LLM understands, the optimizer chooses, the policy engine authorizes, the workflow
coordinates, transaction services execute, Kafka tells the rest. Every supplier in this repository is
still SIMULATED (`sandbox-*`), so every outcome the platform can observe today is synthetic evidence.

## Decision

1. **One service owns evidence and profiles: `services/learning`** (`learning` database,
   `learning_app` role, ADR-0006). It is a durable consumer of `travel.trip`, `travel.order`,
   `travel.disruption` and `travel.optimization`, an owner of an append-only **outcome ledger**, a
   builder and evaluator of **versioned profiles**, and the planner's read-only source of **learning
   inputs**. It writes nobody else's database and nobody else writes its own. Enterprise Context
   remains the owner of who a traveler is; Learning only records the traveler id the platform's own
   events name.
2. **The learning objective is narrow and stated** (`reliability-v1`, `LearningProperties`,
   `profile/ReliabilityModel`): per *supplier key* (`air:<carrier>`, `hotel:<property>`,
   `ground:<vendor>`) a Beta(8, 2)-smoothed reliability from SUCCESS/FAILURE outcomes inside a window
   (default 180 days, per build at most that), at least 3 observations before a key leaves the
   prior (no observations is *not* "unreliable"), and an adjustment of
   `clamp(scale * (estimate - 0.8), -10, +10)` score points; plus, per traveler and supplier key, a
   shrunk mean of explicit ratings bounded to ±5. The optimizer refuses anything above ±25 whatever a
   profile says. Learned utility is score points on the optimizer's soft dimensions, never money: the
   cost used for budgets, thresholds and the $100 autonomy rule is untouched. Hard policy, eligibility
   and demand rules, approval authority and the $100 rule stay deterministic and are not inputs to,
   or outputs of, learning. There is no separate ML platform.
3. **Outcomes are recorded, never inferred.** Each outcome kind is distinct (`OutcomeKind`):
   confirmation, supplier vs platform booking failure (transport and payment codes are not supplier
   quality), traveler cancellation, supplier disruption, recovery resolved/failed, compensation
   released/refused, exposure resolved (a person closing a record, not a refund), settled refund
   (only Finance says so, through the API), trip completion (only an attestation: the traveler after
   the last arrival, or an admin), and structured feedback. A logical outcome has a key and
   revisions; a later fact about the same outcome (impact confirmation after detection, a corrected
   refund, a revised rating) is a higher revision, and only the highest revision recorded by a
   cutoff counts. Kafka redelivery is suppressed by event id; the same fact told by two events lands
   on one row; nothing is updated or deleted, so any cutoff is reproducible. Free text (comments,
   notices) is stored for people and never parsed, never a label, never published.
4. **Evidence is tenant-scoped and class-marked.** Every outcome carries `SANDBOX` or `LIVE` from the
   provider that produced it; a profile is built from one class; a deployment declares its class
   (`LEARNING_DEPLOYMENT_CLASS`) and only a profile of that class can be eligible, so a sandbox-trained
   profile can never influence a live deployment. Travelers read outcomes and feedback of their own
   trips and their own learned preferences; TRAVEL_ADMIN manages, FINANCE reads; another tenant sees
   404.
5. **Profiles are versioned, reproducible artifacts built by a durable workflow.** A build request is
   an event; `LearningBuildWorkflow` (queue `learning-build`, workflow id = profile id) runs
   BeginBuild -> ComputeProfile -> EvaluateProfile as idempotent gRPC steps, so a crashed worker
   resumes without a duplicate or a partial artifact. The profile records cutoff, window, dataset
   fingerprint (SHA-256 over the ordered outcome revisions), algorithm version, every parameter,
   evidence counts, the artifact and its evaluation. Status: BUILDING -> BUILT -> ELIGIBLE | REJECTED,
   or FAILED; a failed or rejected build leaves the tenant's active profile exactly as it was.
6. **Evaluation before activation is chronological and leak-free** (`profile/ProfileEvaluator`): the
   decisions the platform actually made inside the window are replayed oldest first; the holdout is
   a suffix: at least the configured fraction (30%), extended backwards until it holds the minimum
   number of labeled decisions (a decision that was never executed, such as a recovery a person
   still has to approve, has no label and is reported as missing); for each held decision the estimate
   uses only outcomes observed and recorded before it and from other trips (records grouped by
   trip); the label is what later happened to the selected supplier on that trip. The report states
   sample sizes, label coverage, missing labels, the Brier score against the prior, how often the
   learned ranking would have chosen differently, hard-constraint violations, whether the evidence is
   synthetic, and the limits of the comparison (a different pick's outcome is unknown). Activation
   criteria, all required: algorithm compatible, evidence class equals the deployment's, at least one
   key with the minimum samples, finite bounded adjustments, zero hard-constraint violations, enough
   labeled decisions, Brier not worse than the prior by more than the tolerance.
7. **Activation is authorized, atomic and reversible.** Tenant mode is OFF | SHADOW (default) |
   ACTIVE. The active/previous profile and the mode live in one row changed under a row lock with a
   version; an optional expected version turns a concurrent change into a 409. Every change is a
   history row and a `travel.learning.*` event. Rollback restores the previous profile when it is
   still eligible, otherwise the baseline; an explicit `toBaseline` rollback deactivates learning
   outright. Activation of a stale profile (cutoff older than
   `stale-after`) is refused, and a profile that turns stale while active falls back to the baseline
   at resolution time.
8. **The planner pins its inputs.** A planning or recovery workflow resolves learning through one
   activity (`Resolve`, 3 bounded attempts) right before the optimizer call and never reads learned
   state otherwise; the result is in the workflow history, so a retry of the optimizer, a replay or a
   profile change under a running attempt cannot change what the attempt used. If the Learning
   service cannot answer, the plan proceeds with `LEARNING_UNAVAILABLE` and the baseline. The
   optimizer computes the baseline and the learned ranking, executes the baseline in SHADOW and the
   learned one in ACTIVE, and returns and publishes the evidence (profile version, per-candidate
   bounded contributions with reasons, both selections); the audit ledger narrates it without
   implying a guarantee, and the recovery decision record names the pinned mode and profile.

## Consequences

- A tenant can watch learning in SHADOW for as long as it likes: every plan records what learning
  would have done, nothing changes until an admin activates an eligible profile in ACTIVE mode.
- Adding a feature or changing the smoothing is a new algorithm version; old profiles become
  INCOMPATIBLE_ALGORITHM at resolution time and fall back to the baseline until rebuilt.
- What learning cannot do here: read live supplier behaviour (every supplier is SIMULATED, every
  profile is SANDBOX), infer completion or refund receipt, weigh free text, change a hard rule, spend
  money, or move a ranking by more than the bound. The evaluator's "ranking changed" figure counts
  decisions, not avoided failures: the counterfactual is unknown.
- kind and the Docker stack run `LEARNING_SCALE=100` so a handful of synthetic outcomes reaches the
  bound within a demo; the default (40) is the production setting.
