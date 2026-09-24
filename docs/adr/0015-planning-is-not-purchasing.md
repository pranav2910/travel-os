# ADR-0015: Planning is not purchasing — an explicit, bound purchase authorization gates every booking

Status: accepted (platform completion, Phase 3, 2026-09-24)

## Context

Through Slice 5, submitting a request meant buying: once policy said ALLOW (and a manager approved
when a rule asked for one), the workflow booked. Search never reserved anything, but nothing in
the platform recorded *who* authorized spending money on *which* plan at *which* price, a person
could not compare options or refresh a stale price, drafts did not exist, and there was no way to
ask conversationally and keep the thread. Booking-horizon rules and search preferences were absent.

## Decision

1. **A trip is `QUOTED` between planning and any commitment.** Search, policy and optimization
   produce a plan and a price; the trip carries the ranked, permitted `alternatives` (id, total,
   summary, fare conditions), a `quoteExpiresAt`, and the selected plan. Nothing is reserved at a
   supplier while a trip is QUOTED (`PurchaseApiIntegrationTest`, `TripWorkflowTest`).
2. **Purchase authority is a record, never an inference.** `purchase_authorization` (Travel Core,
   `V8`) binds one plan (`bundle_id`), one total in one currency, the fare conditions in words, the
   traveler and their profile version, the trip version, the policy decision and an expiry. Two
   bases: `HUMAN` (the buyer confirmed through `POST /api/v1/trips/{id}/purchase`) and
   `POLICY_AUTONOMY` (the policy document's `autonomy.purchase` granted it for this plan, recorded
   by Travel Core when the workflow reports `autonomous_purchase` on a lifecycle move; auditable
   through `policy_decision_id`). The buyer is the traveler, the arranger or a travel admin; an
   approver by role is not a buyer.
3. **Travel Core is the gate.** The move to `BOOKING` finds exactly one `ACTIVE` authorization
   that covers the plan and the price (the authorized total is a ceiling; higher, another plan,
   another currency or expired is not covered) and moves it to `CONSUMED` with the order attempt;
   otherwise it is refused (`FAILED_PRECONDITION PURCHASE_NOT_AUTHORIZED`) and the workflow takes
   the trip back to a person. A re-quote or re-selection that the authorization does not cover
   supersedes it (`SUPERSEDED`, with the reason); cancellation revokes it. One authorization, one
   booking attempt: a second `BOOKING` move is the same state, not a second consumption.
4. **Confirming is idempotent by key and by state.** The same `Idempotency-Key`, or a second
   confirmation of a plan already covered, returns the same authorization; a confirmation naming a
   plan that is no longer the quoted one is `409 SELECTION_CHANGED`; an expired quote is `409
   QUOTE_EXPIRED` until refreshed.
5. **Who decides that a person must confirm.** `purchaseMode` on the request: `CONFIRM` always
   quotes first; `POLICY` (default) books when the decision carries `autonomous_purchase`. The
   policy engine grants it only when `autonomy.purchase.enabled`, the plan is not denied and the
   total is within `autonomy.purchase.maxTotal` (`PURCHASE_AUTONOMY` rule). Documents from before
   Phase 3 have no such section; for them the engine keeps the behaviour those tenants already
   had (autonomous, unbounded) and the seed policy now states it explicitly with a limit. A
   required approval stays a person's decision in every mode; approval follows the buyer's
   confirmation, never precedes it. This is the one place where the platform's own authority to
   spend is stated, and it is a policy setting, not code.
6. **Revalidation binds the price, not the plan's name.** The itinerary flow re-quotes every
   offer before the only mutation. A higher total supersedes the authorization: in `CONFIRM`
   mode (or when policy's autonomy does not reach the new total) the person confirms again
   before any renewed approval; otherwise policy's autonomy is re-recorded for the new total. A
   lower total is within what was authorized.
7. **The workflow waits, re-quotes and re-selects; a person acts through Travel Core.**
   Signals `purchaseAuthorized`, `selectionChanged`, `refreshQuote` (stage `AWAITING_PURCHASE`),
   each also recoverable by re-reading the trip, so a lost signal only delays. A quoted trip nobody
   confirms within 72 hours fails `PURCHASE_TIMED_OUT` and books nothing.
8. **Drafts, preferences, horizon rules, conversations.** `draft: true` keeps a trip in `DRAFT`
   (no event, no planning) until `POST /{id}/submission`; `PUT /{id}/draft` edits it.
   `intent.preferences` (cabin, nonstop, refundable-only, preferred carriers, max stops) narrow
   the search and feed the optimizer as soft preferences; policy still judges every offer.
   `trip.maxAdvanceDays`, `minLeadHours`, `maxDurationDays` are policy rules (`BOOKING_HORIZON`)
   judged against the workflow's reference time, never the engine's clock. A conversation
   (`/api/v1/conversations`) persists user and assistant turns; every planning turn is a trip
   created through `TripService.create` (source `CONVERSATION`), so authorization, idempotency,
   allocation and purchase rules are identical whichever door a request enters; a clarifying
   question parks the conversation `AWAITING_USER`, and the answer becomes a new turn whose request
   text carries the whole transcript.

## Consequences

- "Was this purchase authorized, by whom, for what price?" has one table and one event
  (`travel.trip.purchase-authorized`); `travel.trip.quoted` marks the moment a person could act.
- The frontend can now offer compare / confirm / refresh / drafts / chat (Phase 11 handoff);
  until it does, `CONFIRM`-mode trips wait in `QUOTED` for an API call.
- Existing behaviour is preserved for existing tenants and the web e2e suite: the seed policy
  grants autonomy up to the trip budget; trips carry `POLICY_AUTONOMY` authorizations they did not
  have before.
- Not in this ADR: payment authorization at a payment provider (Phase 5 binds a payment intent to
  the consumed authorization), multi-traveler purchases, and price-drop re-quotes after approval
  (booked at the lower price under the same authorization).

## Verification

`TripWorkflowTest` (CONFIRM waits and books nothing until the signal; a repeated signal yields one
order; policy without autonomy waits; re-selection and refresh re-quote before confirmation; a
lapsed authorization sends the trip back to the person; timeout fails without booking; preferences
narrow the search), `PurchaseApiIntegrationTest` (drafts; QUOTED reserves nothing; a person's
authorization bound to plan/price/conditions; idempotent confirmation; NOT_THE_BUYER; superseded
on selection or a higher re-quote, kept on a lower one; the BOOKING gate refuses without or with a
stale authorization and consumes once; policy autonomy recorded and consumed; conversations),
`PolicyEngineTest` (rule list incl. `BOOKING_HORIZON` and `PURCHASE_AUTONOMY`).
