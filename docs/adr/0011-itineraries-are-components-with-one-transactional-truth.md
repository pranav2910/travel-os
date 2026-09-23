# ADR-0011: Itineraries are components; coordination state in Temporal, transactional truth in Order

**Status:** accepted (Slice 3). **Refines:** ADR-0002, ADR-0004, ADR-0005, ADR-0007, ADR-0010.

## Context

Slice 3 books a multi-city trip: several flight legs, hotel stays and scheduled ground transfers,
as one coherent, policy-compliant itinerary. The design package's Slice 3 also names
international travel, multiple real suppliers and preferred-supplier agreements; those are out of
this slice. The brief asks for explicit itinerary- and component-level states in Temporal, while
ADR-0002 keeps transactional truth in the Order service ("the workflow asks, it never decides").

## Decision

1. **The frozen intent carries an ordered itinerary.** `TravelIntent.itinerary` holds legs, stays
   and transfers with stable `cmp_<ulid>` component ids, dependencies (a stay hangs off the leg
   that lands and the leg that leaves; an airport transfer off the leg it meets), locations, the
   IANA zone of every place, and one currency. Invariants live in the domain record (legs chain
   and run forward, stays are in cities a leg lands in, unknown places are rejected). The Slice
   1/2 fields keep describing the first leg and the return, so every earlier reader keeps working.
   Component ids are minted when a request is parsed; the trip's idempotency fingerprint hashes the
   stated request without them, so a retried submission is the same trip, not a second one;
   legacy-shaped intents take exactly the Slice 1 path.
2. **One supplier abstraction, three kinds.** `SupplierAdapter` (quote, create, change, cancel,
   booking status, capabilities) is what every adapter implements; `AirSupplier`, `HotelSupplier`
   and `GroundSupplier` add their searches. The sandbox hotel and ground adapters are SIMULATED and
   say so in their capabilities; their faults are catalog fixtures chosen by city (re-price on
   revalidation, ten-second quotes, a committed booking whose answer is lost, refused bookings,
   refused cancellations, injected text, a foreign currency). A live adapter replaces a class and
   nothing above the gateway changes.
3. **Policy judges every offer and then the whole.** Each component's offers are evaluated on their
   own (cabin, stops, lowest logical fare per leg, nightly limit per stay, per-transfer limit); the
   composed itinerary is judged again for the whole-trip budget and the manager threshold. Nothing
   converts currencies: a component priced in another currency is denied. The recovery autonomy
   rule is unchanged: the agent may change an order when the incremental cost summed over every
   affected component is within the policy limit and every replaced leg flies inside its window.
4. **The optimizer composes.** `OptimizeItinerary` chooses one offer per component with CP-SAT
   under hard constraints (chronology with a connection buffer, transfer reachability, night
   coverage on the property's local calendar, budget, one currency) and explains infeasibility per
   component with stable codes. Optional components are skipped, never fatal.
5. **Two kinds of state, reconciled through idempotent calls.** The workflow keeps itinerary-level
   stages (`SEARCHING … AWAITING_APPROVAL, REVALIDATING, BOOKING, COMPENSATING`) and reports
   component states (`PLANNED, QUOTED, REVALIDATING, BOOKING, CONFIRMED, FAILED, CANCELLED,
   CANCEL_FAILED, CHANGED, SKIPPED`) to Travel Core, which serves them on the trip. The Order
   service remains the transactional truth: items carry component ids, are booked in dependency
   order (legs, stays, transfers) under per-item idempotency keys, and are released in reverse
   order when a later one fails. Before any retry of a mutation whose answer was lost, the Order
   service asks the supplier what it did with the key and adopts the booking it finds.
6. **Revalidation before the only mutation.** Every selected quote is checked again right before
   `CreateOrder`. An unchanged price (even after a re-quote) books as re-quoted. A higher total is
   a material change: policy judges the new plan, and when a person had approved the old one, or
   policy now asks for one, the trip goes back to `AWAITING_APPROVAL` (from `APPROVED`) with
   `travel.trip.replanned` naming the reason. A quote that cannot be re-quoted at all (the
   supplier's hold outlived the approval: sandbox offers live 20 minutes, approvals take as long as
   people take) is not a failure of the request: the trip goes from `APPROVED` back to `PLANNING`
   (`travel.trip.replanned`, reason `QUOTE_EXPIRED`), is searched, judged and composed again, and a
   person decides again whenever a person had decided on the plan that expired. One such re-plan is
   allowed per run; a second gone quote ends the trip `FAILED` at `REVALIDATION` with `OFFER_GONE`,
   and `APPROVED -> FAILED` is a legal transition so that outcome is recorded on the trip instead of
   only in the workflow's log (the defect that motivated this paragraph left a trip "Approved"
   with a failed component forever).
7. **Exposure is a record, not a log line.** A confirmed component whose cancellation is refused
   becomes `CANCEL_FAILED` with an `order_exposure` row (amount, reason, supplier reference); the
   order is `PARTIALLY_FAILED`, `travel.order.compensation-failed` escalates, and a TRAVEL_ADMIN or
   FINANCE person resolves it idempotently; when nothing is open the order is `FAILED` and
   compensated by people. The trip fails at stage `COMPENSATION` with `COMPENSATION_INCOMPLETE`.
8. **Connected recovery.** A cancelled leg's recovery (ADR-0010) also plans its dependants: the
   transfer that meets it is re-timed on the same vendor when the pickup no longer fits (preserved
   when it does), a stay whose first night moves is re-dated on the same property, and the next leg
   is re-chained only when the new landing breaks the connection. The replacement is one
   component-tagged bundle, one policy `order.change` decision on the summed incremental cost, one
   idempotent `ChangeOrder` that changes only the named components and preserves the rest.

## Consequences

* Slice 1 and 2 behaviour is unchanged by construction: legacy-shaped intents never enter the
  itinerary flow, and `hotel_required` remains what it was (a Slice 1 flag that was never booked).
* The chaos discipline carries over: the Slice 3 chaos parks the booking at `OptimizeItinerary`
  and the recovery at `Optimize` before removing the order service, with attempt tripwires.
* Out of scope, deliberately: real supplier integrations, international rules, currency
  conversion, preferred-supplier agreements, a frontend (after Slice 5).
