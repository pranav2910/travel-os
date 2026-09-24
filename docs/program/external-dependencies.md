# Remaining external dependencies (Phase 11)

What the platform can do only with something outside this repository, what exists for it, how to
switch it on, and what has and has not been verified. "Provider-test-verified" means the adapter
was exercised against scripted responses in the provider's documented shape; "live-verified" means a
real call to the provider happened; "blocked" means nothing here could be run without an account.

| Dependency | Purpose | What exists | Enable with | Verification |
|---|---|---|---|---|
| Duffel | live air search, offers, orders, cancellations, private fares | `supplier/live/duffel` (Phase 4), capabilities, mutation ledger | `DUFFEL_ACCESS_TOKEN` (test tokens start with `duffel_test_`) | provider-test-verified (`DuffelAirSupplierTest`); live smoke test skips without a token; **blocked** |
| Hotelbeds | live hotel availability, booking, cancellation | `supplier/live/hotelbeds` | `HOTELBEDS_API_KEY`, `HOTELBEDS_SECRET` | provider-test-verified; **blocked** |
| Rail / car suppliers | rail and car rental | contract kinds and adapter seams only (`RailSupplier`, `CarRentalSupplier`) | an adapter | **absent** (no adapter, simulated or live) |
| Stripe | authorize / capture / void / refund on real instruments | `order/finance/StripePaymentProvider` | `STRIPE_SECRET_KEY` | provider-test-verified (`StripePaymentProviderTest`); **blocked** |
| Anthropic | free-text trip understanding with a real model | `intelligence/llm-gateway` | `ANTHROPIC_API_KEY`, `LLM_PROVIDER=anthropic` | the deterministic extractor is verified; the model path **blocked** here |
| Customer IdP (Okta, Entra ID, …) | federated sign-in | `deploy/keycloak/federate.sh` (OIDC/SAML broker with tenant/employee/role mappers) | the IdP's client id/secret or SAML metadata | syntax-checked; **blocked** |
| Customer IdP provisioning | SCIM 2.0 user lifecycle | `/scim/v2` in Enterprise Context | `SCIM_TOKEN_<TENANT>` | verified against Okta/Entra request shapes (`ScimIntegrationTest`); a real provisioning cycle **blocked** |
| Workday | HRIS | `source/live/WorkdayHrisSource` (RaaS) | `WORKDAY_REPORT_URL/USERNAME/PASSWORD` | provider-test-verified (`LiveSourcesContractTest`); **blocked** |
| Google Workspace | calendars | `GoogleCalendarSource` (service account, domain-wide delegation, sync tokens) | `GOOGLE_SERVICE_ACCOUNT_EMAIL/KEY` | provider-test-verified; **blocked** |
| Microsoft 365 | calendars | `GraphCalendarSource` (client credentials, delta links) | `MICROSOFT_TENANT_ID/CLIENT_ID/CLIENT_SECRET` | provider-test-verified; **blocked** |
| Salesforce | CRM visits | `SalesforceCrmSource` (client credentials, SOQL) | `SALESFORCE_INSTANCE_URL/CLIENT_ID/CLIENT_SECRET` | provider-test-verified; **blocked** |
| SAP Concur | expense reports | `ConcurExpenseSource` (refresh token) | `CONCUR_CLIENT_ID/CLIENT_SECRET/REFRESH_TOKEN` | provider-test-verified; **blocked** |
| SendGrid | email notifications | `assistance/notify/EmailChannel` | `SENDGRID_API_KEY`, `NOTIFICATIONS_FROM_ADDRESS` | delivery machinery verified through a recording channel; the provider **blocked** |
| Slack | chat notifications | `ChatChannel` (incoming webhook) | `SLACK_WEBHOOK_URL` | as above; **blocked** |
| AWS (EKS, RDS, MSK, Secrets Manager) | production hosting | Terraform modules and environments, EKS Helm values, External Secrets | AWS credentials and accounts | Terraform static checks and kind deployments verified in CI; a real AWS deployment **blocked** |
| Airport catalogue | places, zones, distances | ~100 hand-curated airports (`Locations`) | a full dataset (data swap) | verified for the catalogue; a full dataset is a data task |
| Currency conversion | multi-currency reporting | FX provenance recorded from the payment provider | a rates source | reports stay per currency by design (ADR-0021) |

Nothing in this table was live-verified in this repository; every "blocked" row names the exact
credential that unblocks it, and each adapter refuses to start without it rather than pretending.
