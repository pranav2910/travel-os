# ADR-0019: Governance is scoped, budgeted, chained and federated; the platform's authorities stay where they are

Status: accepted (platform completion, Phase 7, 2026-09-24)

## Context

Until Phase 6 a tenant had one policy, one approver role per decision, no budgets, no notion of a
negotiated supplier, no way for a manager to hand approvals to a colleague, no answer to an approval
nobody decided, and identity that came only from the platform's own Keycloak realm and the
SIMULATED HRIS. A corporate travel program needs: policies that differ by project or cost center;
approvals that go up a chain and cannot stall forever; budgets that two trips cannot both spend;
agreements that steer the search and the verdict; the customer's own IdP for sign-in and
provisioning; and the customer's real HRIS, calendars, CRM and expense systems.

## Decision

1. **Scoped policies resolve most specific first, in the Policy service.** `policy_scope` assigns a
   policy to an employee, project, cost center, department, legal entity or office; the tenant
   default stays the fallback. Every evaluation carries the trip's allocation scope (the Phase 2
   snapshot) and the decision records which policy applied. Nothing about the engine's rules
   changed for tenants that assign no scopes.
2. **Approval chains come from the policy document.** `approval.chain` lists ordered steps, each
   with an optional total above which it applies; roles a rule requires are appended. The decision
   carries the chain and the step expiry; Travel Core keeps one pending approval per trip at a time
   and opens the next step when one is approved. The workflow is signalled by the last step only,
   so the planner is unchanged. `approvers[0]` still names the first step for older readers.
3. **Delegation is a recorded relationship, not a role.** A manager (or Finance, or a travel
   admin) delegates their authority to a colleague for a period; the delegate decides in the
   delegator's name, sees the trips that authority covers, and the approval says `onBehalfOf`. A
   revoked or expired delegation ends it at once.
4. **Unanswered approvals escalate once, then expire.** A sweep in Travel Core escalates a step
   past its expiry to `TRAVEL_ADMIN` with a fresh period (`travel.approval.escalated`, which the
   assistance service opens a case for), and expires a step still unanswered after that as a
   rejection by the platform (`travel.approval.expired`); the workflow treats it like any rejection.
5. **Budgets are reserved under a lock, in the Policy service.** A budget covers a scope and a
   period in one currency. Policy's verdict knows what is left (`BUDGET_EXCEEDED` denies on a hard
   budget, asks Finance on a soft one); the workflow reserves the total under the budget row's lock
   before BOOKING (idempotent per trip; a hard budget that no longer fits fails the trip); the
   platform's own `travel.trip.booked / cancelled / failed` events commit or release the
   reservation. Two trips racing for the last of a budget: exactly one gets it.
6. **Supplier agreements steer the search and, when the document says so, the verdict.**
   Negotiated rate codes reach the supplier search (`negotiated_rates`); a preferred-supplier list
   plus `suppliers.onNonPreferred` makes choosing outside it a violation. Agreements are data the
   travel admin keeps; the policy stays the authority for what they mean.
7. **Federation brokers the customer's IdP through Keycloak; provisioning is SCIM 2.0.** The
   services keep their JWT contract (`tenant_id`, `employee_id`, `roles`); a brokered IdP's claims
   are mapped onto it (`deploy/keycloak/federate.sh`). Enterprise Context serves `/scim/v2` with a
   per-tenant bearer token from the secrets mechanism; provisioning is authoritative for existence
   and activity, and a SCIM deactivation runs the platform's own offboarding (ADR-0014).
8. **Genuine enterprise adapters exist only when their credentials do.** Workday (RaaS), Google
   Workspace and Microsoft 365 calendars, Salesforce and SAP Concur implement the Slice 4 source
   port with real OAuth flows, incremental checkpoints and honest failure classes (a revoked
   credential is final; an outage is retried). They are contract-tested against scripted provider
   responses; live verification needs a customer tenant and is recorded as blocked.

## Consequences

- Authorization semantics are unchanged where nothing new is configured: one policy, one approver,
  no budget, no agreements, the platform's own realm. Every addition is opt-in data.
- Policy remains the authority for verdicts, Travel Core for approvals, the Order service for
  money and suppliers. Budgets say whether a trip may be bought, never how it is paid.
- Budget accounting follows the trip's events, so a partial cancellation's refund does not return to
  the budget until the trip is cancelled or the budget is corrected by Finance; Phase 9 reports it.
- Federation and provisioning are implemented but unverified against a real IdP; the runbook
  `docs/runbooks/enterprise-federation.md` says exactly what was and was not exercised.
