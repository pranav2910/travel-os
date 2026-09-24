# ADR-0018: Servicing is component-scoped; a traveler's request is a recovery with the person as the actor; unfinished business is a case

Status: accepted (platform completion, Phase 6, 2026-09-24)

## Context

Until Phase 5 the platform could cancel a whole trip, recover from a supplier's disruption, and
record an exposure when a supplier refused a release. It could not release one component of a booked
trip, could not act on a traveler's own wish to move a flight, and had no record of *who* was
handling an exposure, an unknown supplier outcome, an incomplete cancellation, a recovery stuck on
an approver, or a failed booking, nor by when, nor what came next.

## Decision

1. **Partial cancellation is a component-scoped release, not a smaller cancellation.**
   `CancelOrderCommand.component_ids` names the components; the Order service releases only those
   items at their suppliers (`OrderService.releaseComponents`), refunds and credits per item reach
   the finance ledger (ADR-0017), a refusal is an OPEN exposure on that item (never a machine
   retry), and the order keeps its status for the rest. The event `travel.order.items-released`
   carries the released items and the refused exposures. Travel Core exposes
   `POST /api/v1/trips/{id}/components/{componentId}/cancellation` (traveler, arranger or travel
   admin; BOOKED trips; CONFIRMED components): the component becomes CANCELLING, the trip stays
   BOOKED, `travel.trip.component-cancellation-requested` starts a component-scoped
   `TripCancellationWorkflow` (workflow id `cancel:<trip>:<components>`, one per request), and the
   workflow reports each component back as CANCELLED or CANCEL_FAILED with its failure code
   (`travel.trip.components-released`). The whole-trip path is unchanged.
2. **A traveler's request to move a flight is a disruption of type `TRAVELER_REQUEST`.** The
   Disruption service accepts `POST /api/v1/disruptions/requests` (the order's traveler or a travel
   admin; idempotent by key), confirms the impact against the Order service at once, and records the
   requested window and requester on the affected segment. The same recovery workflow runs, with
   two differences: the search and the constraints use the requested window (the intent is retimed
   for this recovery only), and policy is asked with the **person** as the actor, so the human
   rules apply (the manager approval threshold), not the agent's rebooking autonomy. The order
   change itself remains the agent's single idempotent mutation. No new machine, no new authority.
3. **Unfinished business is a case, in its own service.** `services/assistance` (port 8092,
   database `assistance`, ADR-0006 isolation) consumes `travel.order`, `travel.trip`,
   `travel.disruption` and `travel.finance` exactly once per event id and keeps one open case per
   fact (a dedupe key). Kinds: EXPOSURE, OUTCOME_UNKNOWN, CANCELLATION_INCOMPLETE,
   RECOVERY_APPROVAL, RECOVERY_FAILED, BOOKING_FAILED, PAYMENT_DECLINED, TRAVELER_REQUEST, SAFETY,
   OTHER. Every case has a queue (TRAVEL_OPS, FINANCE, APPROVALS, SAFETY), a priority with a
   service level (CRITICAL 1h, HIGH 4h, NORMAL 24h, LOW 72h), an owner, a status (OPEN,
   IN_PROGRESS, WAITING, ESCALATED, RESOLVED, CLOSED), a next action and the role that should take
   it, an escalation level and a due time. A sweep escalates overdue cases one level (raised
   priority, wider audience, fresh due time, up to level 3). The platform's own facts settle cases
   (an exposure resolved, a trip cancelled, a component released, a disruption resolved, a payment
   authorized); people assign, note, escalate, resolve and close over `/api/v1/cases`. Travelers see
   the cases on their own trips and may open one (a request, a safety concern). **A case never
   changes an order, a trip or a payment**: it points a person at the authoritative service that
   does. Events `travel.assistance.*` announce every change for audit and notifications.

## Consequences

- Servicing keeps the reservation, ticketing, cancellation, refund and credit states distinct: a
  released component is CANCELLED with its refund and credit recorded per item; the rest of the
  order is untouched; the trip's status describes the trip, its components describe themselves.
- Authorization semantics are unchanged: policy stays the authority for changes, the approval
  chain for what policy requires, the Order service for money and suppliers. A traveler's request
  gets no autonomy the agent would not have; it gets the human rule set instead.
- The assistance service is a consumer: it can be down without any order, trip or payment being
  affected, and catches up from the topics when it returns. Its cases are the operational view the
  frontend and notifications (Phase 8) build on.
- Not decided here: per-passenger changes (the trip model is single-traveler, ADR-0014), a
  supplier-side application of credits, and notifications of case changes to people.
