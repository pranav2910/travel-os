# Frontend hand-off (Phase 11)

The web app (`web/`) covers Slices 1–5 and the guided tour. The backend phases added the surfaces
below; every endpoint, body and response is specified in `docs/program/api-contracts.md`
(sections per phase), every event in `contracts/events`. The rules that the UI must respect are
the backend's, not the UI's: policy decides, approvals are people's, money is the Order service's.

| Screen or flow | Backend | Contract section |
|---|---|---|
| Traveler profile, documents, loyalty; guest travelers; arranger grants; org units and projects | Enterprise Context `/api/v1/travelers`, `/api/v1/arrangers`, `/api/v1/org` | Phase 2 |
| Draft → submit; quote with alternatives; confirm purchase / choose another / refresh price; conversations | Travel Core `/api/v1/trips/{id}/{draft,submission,purchase,selection,quote-refresh}`, `/api/v1/conversations`; trip status `QUOTED` | Phase 3 |
| Payment instruments, payables, balances, credits, receipts, reconciliation (Finance) | Order `/api/v1/finance/*`, `/api/v1/orders/{id}/receipt` | Phase 5 |
| Cancel one component of a booked trip; ask to move a flight | Travel Core `POST …/components/{id}/cancellation`; Disruption `POST /api/v1/disruptions/requests` | Phase 6 |
| Operations board: cases by queue, assign, note, escalate, resolve, close; summary tiles | Assistance `/api/v1/cases` | Phase 6 |
| Policy scopes, budgets (with reservations), supplier agreements (admin) | Policy `/api/v1/policies/scopes`, `/api/v1/budgets`, `/api/v1/policies/agreements` | Phase 7 |
| Approval chain view (steps, who is next, expiry), delegate my approvals | Travel Core `GET /api/v1/trips/{id}/approvals`, `/api/v1/approvals/delegates`; `ApprovalResponse` gained `step`, `chainLength`, `chainRoles`, `expiresAt`, `escalatedToRole`, `onBehalfOf` | Phase 7 |
| Notification bell and inbox, preferences per category | Assistance `/api/v1/notifications`, `…/preferences` | Phase 8 |
| Safety: advisories (admin), affected travelers, the traveler's check-in | Assistance `/api/v1/safety` | Phase 8 |
| "Add to calendar" and a printable itinerary | Travel Core `GET /api/v1/trips/{id}/itinerary[.ics]` | Phase 8 |
| Reports with CSV download (Finance, admins) | Audit `/api/v1/reports/{spend,outcomes,exceptions,suppliers}?format=csv` | Phase 9 |

Type changes the existing UI must absorb: `TripStatus` gained `QUOTED` (already in
`web/src/api/types.ts`); trip responses gained `purchase`, `alternatives`, `allocation`,
`quoteExpiresAt`; approval responses gained the chain fields above; the `ApprovalResponse`
`requiredRole` may now be `FINANCE`; 403 codes `NOT_THE_APPROVER` carry the step and role.

Edge routes: nginx and the ingress already serve `/api/v1/cases`, `/api/v1/notifications`,
`/api/v1/safety` (assistance), `/api/v1/reports` (audit), `/scim/v2/` (enterprise context).
