# Enterprise federation and provisioning (Phase 7)

How a customer's identity provider signs its people in (federation) and creates, updates and
deactivates them in the platform (provisioning). Both are implemented; both need a customer's IdP to
be verified live, which this repository cannot do. What was verified is stated at the end.

## Sign-in: Keycloak brokers the customer's IdP

The platform's services validate JWTs from the `travelos` realm and read three claims: `tenant_id`,
`employee_id`, `roles` (`libs/spring-web`, `TenantJwtAuthenticationConverter`). Federation keeps that
contract: Keycloak brokers the customer's IdP (OIDC or SAML) and maps the IdP's claims onto those
three.

`deploy/keycloak/federate.sh` configures the broker with `kcadm` from environment variables and
never writes a credential anywhere:

```bash
FEDERATION_ALIAS=acme-okta FEDERATION_KIND=oidc \
FEDERATION_ISSUER=https://acme.okta.com/oauth2/default \
FEDERATION_CLIENT_ID=<from the IdP> FEDERATION_CLIENT_SECRET=<from the IdP> \
FEDERATION_TENANT=acme FEDERATION_EMPLOYEE_CLAIM=employeeNumber FEDERATION_GROUPS_CLAIM=groups \
KEYCLOAK_ADMIN_PASSWORD=<secrets mechanism> deploy/keycloak/federate.sh
```

Mappers: `tenant_id` is hardcoded per identity provider (one IdP, one tenant); `employee_id` comes
from the IdP claim named by `FEDERATION_EMPLOYEE_CLAIM`; realm roles come from IdP groups named
`travelos-manager`, `travelos-travel_admin`, `travelos-finance` (everyone gets `TRAVELER`).
`syncMode=FORCE` re-applies the mapping at every sign-in, so a group change at the IdP takes effect
on the next login. MFA is the IdP's: Keycloak requires nothing on top for brokered users.

On kind/EKS set `KC_EXEC="kubectl exec -n travelos-infra deploy/keycloak --"` and `KC_URL` to the
in-cluster address.

## Provisioning: SCIM 2.0

Enterprise Context serves `/scim/v2` (`ServiceProviderConfig`, `Users` with GET/POST/PUT/PATCH/DELETE,
filters `userName eq`, `externalId eq`, `id eq`). The IdP authenticates with a per-tenant bearer
token from the secrets mechanism (`SCIM_TOKEN_<TENANT>` -> `travelos.context.integrations.scim.tokens.<tenant>`);
a user's JWT is not a provisioning token. The web edge routes `/scim/v2/` to the service.

Mapping (RFC 7643 core + enterprise extension + `urn:ietf:params:scim:schemas:extension:travelos:2.0:User`):

| SCIM | Employee |
|---|---|
| `enterprise.employeeNumber`, else `externalId` | `employee_id` (the platform's id; stable) |
| `userName` (or the primary email) | `email` |
| `displayName`, else `name.givenName` + `name.familyName` | `display_name` |
| `travelos.workLocation`, else a 3-letter `addresses[work].locality` | `work_location` (IATA; `UNK` when unknown) |
| `travelos.timeZone`, else `timezone` | `time_zone` |
| `enterprise.manager.value` | `manager_employee_id` |
| `enterprise.department` / `costCenter` / `organization`, `travelos.officeId` | department / cost center / legal entity / office |
| `active` | `active`; a deactivation runs the same path as a travel admin's (grants end, documents enter retention, ADR-0014) |

`DELETE` deactivates (nothing is deleted). Provisioning is authoritative for existence and activity;
an HRIS sync with an older revision cannot revive a deactivated user. Groups are not modelled: roles
come from the IdP's claims at sign-in (above).

Okta: add the SCIM app with base URL `https://<edge>/scim/v2`, "HTTP Header" auth, the tenant's
token; enable Create/Update/Deactivate and map `employeeNumber`. Entra ID: "Provisioning" on the
enterprise app with the same base URL and token; map `employeeId` to `employeeNumber`.

## Genuine enterprise adapters

Registered only when their credentials exist (`services/enterprise-context/.../source/live`):

| Provider | Kind | Credential (environment) | Incremental |
|---|---|---|---|
| `workday` | HRIS | `WORKDAY_REPORT_URL`, `WORKDAY_USERNAME`, `WORKDAY_PASSWORD` (RaaS, basic auth) | whole report per run |
| `google-workspace` | CALENDAR | `GOOGLE_SERVICE_ACCOUNT_EMAIL`, `GOOGLE_SERVICE_ACCOUNT_KEY` (PKCS#8 PEM; domain-wide delegation) | `nextSyncToken` per user |
| `microsoft-365` | CALENDAR | `MICROSOFT_TENANT_ID`, `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET` (client credentials) | `deltaLink` per user |
| `salesforce` | CRM | `SALESFORCE_INSTANCE_URL`, `SALESFORCE_CLIENT_ID`, `SALESFORCE_CLIENT_SECRET` (connected app, client credentials) | `LastModifiedDate` watermark |
| `sap-concur` | EXPENSE | `CONCUR_CLIENT_ID`, `CONCUR_CLIENT_SECRET`, `CONCUR_REFRESH_TOKEN` | `LastModifiedDate` watermark |

A connector for a provider is created like a sandbox one (`POST /api/v1/connectors {kind, provider,
config}`); calendar connectors list the covered people in `config.users`. Configuration never holds
a secret (the service refuses one). A revoked credential fails the run as `CREDENTIALS_REVOKED`
(final: reconnect it); rate limits and outages are retried by the sync workflow.

## What was verified, what is blocked

- Verified: the SCIM endpoint end to end against a real database with the shapes Okta and Entra ID
  send (`ScimIntegrationTest`); every adapter's credential flow, mapping, checkpoint and failure
  handling against scripted provider responses (`LiveSourcesContractTest`); the federation script's
  syntax.
- Blocked (needs a customer tenant): a brokered sign-in through a real IdP; a real Okta/Entra
  provisioning cycle; a live sync from Workday, Google Workspace, Microsoft 365, Salesforce or
  Concur. None of these was exercised; nothing here claims otherwise.
