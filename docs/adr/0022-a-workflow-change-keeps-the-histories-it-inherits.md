# ADR-0022: A workflow change keeps the histories it inherits

Status: accepted (platform completion, follow-up, 2026-09-30)

## Context

Temporal does not store a workflow's state; it stores the history of what the workflow did and
re-executes the code against that history every time the execution wakes up. The code must
therefore issue the same commands, in the same order, as the version that recorded the history.
Phases 3 to 7 added commands to the trip planning workflows (a governance read before the search,
a purchase confirmation when policy did not grant autonomy, a budget reservation and a passenger
read before booking) without such a guard. When the completion program's worker replaced the
earlier one on the local stack, the five trips from the 2026-09-23 QA run that were still awaiting
approval could no longer be replayed: Temporal reported a non-determinism failure at the first new
command and retried the workflow task forever, and an approval signal sent to one of them was never
read. An approval can keep an execution in flight for days, so the same would happen on any real
deployment of a worker change.

## Decision

1. **Every workflow change that alters the sequence of commands is guarded by a version marker**
   (`Workflow.getVersion`). The marker is recorded once per execution, at the point where the first
   new command would appear, and every new command reads it: an execution recorded before the change
   keeps the sequence it recorded, a new execution takes the new path. Phases 3 to 7 are guarded by
   `TripWorkflowImpl.CHANGE_GOVERNED_PURCHASE`; for an execution recorded before it, the policy
   outcome and the approval it may have required are the purchase authority, as they were when it
   was recorded. That is not a change of purchase authorization semantics for new trips: it preserves
   the semantics each execution started under.
2. **Recorded histories are replayed in the build.** `WorkflowReplayTest` replays histories that
   the worker at commit `61fcf14` recorded from its own test harness (fake activities, fixture
   trips, nothing from any environment): a round trip and an itinerary, each parked at the approval
   and each booked after one. A change that breaks a replay fails the build, which is the signal to
   add a guard and to record new fixtures from the released code before merging.
3. **The go-live runbook treats a worker upgrade as a compatibility question**: histories are
   recorded from the released worker before the change, the replay test covers them, and the
   pending workflow tasks are watched after the rollout for `NON_DETERMINISTIC_ERROR`.

## Consequences

- An execution that was waiting for a person when the worker changed continues under the rules it
  started with; nothing in flight is stranded or re-planned by a deployment.
- A replay fixture is a promise about the past, not the present: new fixtures are recorded from the
  code that ships, and old ones are kept for as long as executions recorded by that code can exist
  (the approval and purchase timeouts bound that at 72 hours after the last such execution started).
- A guarded old path is still the old path: an execution recorded before Phase 3 books an expired
  sandbox offer the way the old code did (a failed order, `OFFER_EXPIRED`), while an itinerary
  re-plans, because that is what it recorded. The five stranded QA trips resolve that way after the
  fix; nothing pretends they were planned under the new rules.
- Temporal's worker versioning (build ids) would let an old worker keep serving old executions
  instead; it needs server configuration and two worker deployments per change, and is not used.
