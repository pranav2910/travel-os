# Go-live checklist and operations runbook (Phase 11)

For a controlled pilot on Kubernetes (`docs/runbooks/kubernetes.md` covers kind and EKS mechanics;
this page is the order of operations and what to watch). Every step names the evidence that it was
done; none of them is optional.

## Before the first tenant

1. **Secrets in place** for every row of `docs/program/production-config.md` that the pilot
   needs: database passwords, `TRAVELOS_FIELD_KEY`, the SCIM token per tenant, and only the live
   credentials the pilot actually uses (a missing credential switches a feature off; it never
   falls back to a sandbox silently).
2. **Deployment class**: `LEARNING_DEPLOYMENT_CLASS=LIVE` and the web's `DEPLOYMENT_CLASS=LIVE`
   only when live suppliers and payments are configured; SANDBOX otherwise, and the UI says so.
3. **Identity**: the realm imported; either the dev realm's users disabled or the customer's IdP
   brokered (`deploy/keycloak/federate.sh`) and provisioning pointed at `/scim/v2` with the
   tenant's token; a first sign-in carries `tenant_id`, `employee_id` and roles (check the token).
4. **Policy**: publish the tenant's policy (`POST /api/v1/policies`), assign scopes if any
   (`PUT /api/v1/policies/scopes`), budgets (`POST /api/v1/budgets`), agreements
   (`POST /api/v1/policies/agreements`). A tenant without a default policy books nothing (denied,
   never waved through).
5. **Payments**: register the corporate instruments (`POST /api/v1/finance/instruments`) and set
   `travelos.finance.settlement.<provider>`; the worker's `PAYMENT_TOKEN` names a registered
   instrument.
6. **Notifications**: a `NOTIFICATIONS_FROM_ADDRESS` the tenant recognises; people's addresses are
   learned at sign-in; test with one traveler before the first real trip.
7. **Smoke**: run `scripts/e2e-slice1.sh` (or `make kind-e2e`) against the environment with the
   sandbox suppliers still enabled, then one real trip end to end with the live suppliers on a
   refundable fare, cancelled afterwards, and reconcile it (`GET /api/v1/finance/reconciliation`).

## Every day

- **Cases** (`/api/v1/cases`, summary at `/summary`): nothing CRITICAL open past its due time;
  every escalated case owned. Exposures (`GET /api/v1/orders/exposures`) resolved within the day.
- **Deliveries**: `GET /api/v1/notifications/deliveries/failed` empty, or each failure explained.
- **Approvals**: escalations and expiries in `GET /api/v1/reports/exceptions` near zero; a spike
  means a manager is away without a delegate (`/api/v1/approvals/delegates`).
- **Outbox**: `travelos_outbox_backlog` near zero in every service; a growing backlog means Kafka
  or the broker credentials.
- **Audit**: `audit_quarantine` empty (a row is a producer emitting something off-contract).
- **Suppliers**: `GET /api/v1/reports/suppliers` refusals and disruptions in line with the
  previous week; `supplier_mutation_attempt` rows in STARTED/UNKNOWN older than an hour are
  OUTCOME_UNKNOWN cases and must be reconciled with the supplier.

## Metrics worth an alert

| Metric | Condition |
|---|---|
| `travelos_outbox_backlog` | > 100 for 5 minutes |
| `travelos_audit_quarantined` | any increase |
| `travelos_assistance_cases_total{result="OPENED",kind="OUTCOME_UNKNOWN"}` | any increase |
| `travelos_assistance_escalations_total` | > 3 per hour |
| `travelos_assistance_notifications_total{result="FAILED"}` | any increase |
| `travelos_learning_resolutions_total{result!="APPLIED",result!="SHADOW"}` | fallback reasons rising |
| workflow task queue backlog (Temporal UI) | > 50 for 5 minutes |
| HTTP 5xx rate on the edge | > 1% |

## Operations

- **Replaying events**: the audit trail is the truth; consumers are idempotent by event id
  (`processed_event` tables in learning, assistance, policy). To rebuild a consumer's state, reset
  its consumer group offset and let it re-read the topic.
- **A stuck workflow**: Temporal UI shows the stage (`query stage`); a workflow waiting on a person
  (approval, purchase confirmation) has a case or a notification; a workflow that lost an answer
  retries with the same idempotency key, never books twice.
- **A supplier outage**: bookings fail with the supplier's code and no charge (the payment was only
  authorized and is voided); recoveries fall to a person (`RECOVERY_FAILED` cases).
- **Rotating a credential**: replace the secret, roll the deployment; adapters read it at startup.
  The profile field key rotates by re-encryption (ADR-0014).
- **Rollback**: `docs/runbooks/kubernetes.md#rollback`; migrations are additive, so an older image
  runs against a newer schema.
- **Offboarding a person**: the IdP's deactivation (SCIM) or `POST /api/v1/employees/{id}/deactivation`;
  grants end and documents enter retention at once.

## What this pilot does not do (still true)

See `docs/program/external-dependencies.md` for every live integration that is implemented but
unverified, and the capability matrix for what is absent (rail/car adapters, multi-passenger
trips, invoice documents).
