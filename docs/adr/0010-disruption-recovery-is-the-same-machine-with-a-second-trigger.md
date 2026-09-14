# ADR-0010: Disruption recovery is the same machine with a second trigger

**Status:** accepted (Slice 2). **Refines:** ADR-0002, ADR-0003, ADR-0005.

## Context

Slice 2 adds autonomous recovery from an airline cancelling a flight on a confirmed trip. The
temptation is a new "agent" that watches supplier feeds and rebooks. The design package's rule
is unchanged: the LLM understands, the optimizer chooses, policy authorizes, the workflow engine
coordinates, transaction services execute, Kafka tells the rest.

## Decision

1. **Supplier notices enter through the Supplier Gateway only.** A provider's webhook (HMAC-signed
   body, no tenant JWT) is normalized by its adapter into `travel.disruption.detected`, exactly once
   per supplier event (a `supplier_notification` row per provider + event id, in the same
   transaction as the outbox append). The gateway remembers what it booked
   (`supplier_order_ref`), so the notice is keyed by the trip from the first event. The supplier's
   free text travels as `reason`: data, never an instruction.
2. **A Disruption service owns the aggregate.** `Disruption` (ids, type, supplier, supplier event,
   detected time, status, severity, raw reference) with the state machine
   `DETECTED → IMPACT_CONFIRMED → SEARCHING_ALTERNATIVES → OPTIMIZING → DECISION_READY →
   AUTO_ALLOWED | HUMAN_REQUIRED → CHANGING → RESOLVED` and the terminal `NO_ALTERNATIVE`,
   `FAILED`, `MANUAL_INTERVENTION_REQUIRED`. Impact is confirmed by asking the Order service
   (`FindOrderByExternalRef`), the transactional truth. Two records are written once and made
   immutable by a database trigger: the **recovery decision** (original itinerary, trigger,
   candidate counts, every rejected candidate with its policy or feasibility reasons, the selected
   itinerary with its score components, incremental cost, the policy decision with its version, the
   autonomy verdict, agent and workflow version) and the **recovery outcome** (approval, supplier
   result, or failure stage and code). Recovery approvals live here too, with the same rules as
   trip approvals (MANAGER or TRAVEL_ADMIN, never the traveler themselves).
3. **The recovery is a Temporal workflow, id = disruption id, on its own task queue.** It runs the
   same steps as planning with the same services — search, deterministic policy filter, OR-Tools
   optimization — then asks policy one more question it did not ask before: *may this agent change
   this order for this incremental cost?* (`EvaluateAction`, action `order.change`, alias
   `CHANGE_EXISTING_ORDER`, with the trip's frozen intent so policy itself verifies the replacement
   meets the arrival deadline and return window). `ALLOW` changes the order; `ALLOW_WITH_APPROVAL`
   waits durably for a person (signal, with a periodic re-read so a lost signal only delays);
   `DENY` ends in `MANUAL_INTERVENTION_REQUIRED`. Every activity is idempotent, so a worker dying
   anywhere resumes with no second search, decision, approval request or supplier change.
4. **One change per recovery, by key.** `ChangeOrder` on the Order service is idempotent by
   `TRIP:<tripId>:DISRUPTION:<disruptionId>:CHANGE:1` (ADR-0005's grammar now allows a
   colon-separated scope). The order records the change (`order_change`), keeps the replaced item as
   `CHANGED`, and calls the supplier with its own key (`<orderId>:<changeId>`), which the sandbox
   airline honours: N calls, one reissue. `travel.order.change-requested` precedes the supplier
   call, `travel.order.changed` follows it.
5. **The LLM narrates only.** `ExplainDisruption` renders evidence to text with the supplier's
   notice fenced as untrusted data; the gateway has no tools and its output is stored as the
   disruption's `explanation`, read by people, read by nothing that decides.
6. **The sandbox airline is deliberately deterministic.** Its notice carries a reaccommodation knob
   (`fareDeltaMinor`): the cancelled flight disappears in every cabin for everyone, and for the
   disrupted trip only (the correlation id it searches with) every surviving nonstop is quoted at
   exactly the original fare plus the delta, every one-stop strictly more. Whichever nonstop the
   optimizer finds feasible therefore costs the same known amount, so tests can say "+$73 is
   inside the $100 autonomy limit" and "+$180 is not" and mean it, while other travelers on the
   route keep the published fares.

## Consequences

- Recovery reuses every Slice 1 guarantee instead of re-implementing it: tenant scoping in every
  query, idempotency keys in every command, outbox events, one trace across the whole flow.
- Duplicate signals collapse at three layers: the gateway (supplier event id), the Disruption
  service (Kafka event id and supplier event id), Temporal (workflow id reuse policy).
- A real NDC adapter changes one segment rather than reissuing the itinerary; the contract
  (`ChangeOrderRequest.new_provider_offer_id`) already carries what it needs. The gateway would
  normalize the vendor's own cancellation notice the same way the sandbox does.
- Approval timeouts and rejections end in `MANUAL_INTERVENTION_REQUIRED`, not silence: the
  outcome record and `travel.disruption.recovery-failed` say what stopped and where.
