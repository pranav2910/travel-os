# Production configuration reference (Phase 11)

Every service reads its configuration from environment variables with safe local defaults; every
credential comes from the platform's secrets mechanism (compose: the shell; kind:
`deploy/kind/secrets.sh` → Kubernetes Secrets; EKS: External Secrets from AWS Secrets Manager,
`deploy/helm/travelos/values-eks.yaml`). Nothing secret is defaulted in a chart or a compose file
except the dev-only values the local stack labels as such. "Absent = off" means the feature that
needs the value does not exist until it is set; the service starts either way.

## Shared by every JVM service

| Variable | Purpose | Default |
|---|---|---|
| `PORT`, `GRPC_PORT` | HTTP and gRPC listeners | per service (8081/9081 …) |
| `<SERVICE>_DB_URL`, `_DB_USER`, `_DB_PASSWORD`, `_DB_POOL_SIZE` | the service's own database (one role per database, ADR-0006); the password is a secret | local dev values |
| `KAFKA_BOOTSTRAP_SERVERS` | the broker | `localhost:9092` |
| `OIDC_ISSUER_URI` (+ `OIDC_JWK_SET_URI` in containers) | the realm whose JWTs are accepted; audience `travelos-api` | dev realm |
| `TRACING_EXPORT_ENABLED`, `TRACING_SAMPLING_PROBABILITY`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_METRICS_EXPORT_ENABLED`, `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | OpenTelemetry | off locally, on in the stack |
| `travelos.outbox.poll-interval` | the outbox safety-net poll (relays are nudged on commit since Phase 10) | 500 ms |

## Per service

| Service | Variables | Secrets |
|---|---|---|
| travel-core (8081/9081) | `ENTERPRISE_CONTEXT_ADDRESS` (optional; empty = Slice 1 arranger rules), `TEMPORAL_ADDRESS`, `TEMPORAL_ENABLED`, `APPROVAL_SWEEP` (1m) | `TRAVEL_CORE_DB_PASSWORD` |
| policy (8082/9082) | — | `POLICY_DB_PASSWORD` |
| supplier-gateway (8084/9084) | `DUFFEL_BASE_URL`, `DUFFEL_UA_CORPORATE_CODE`, `HOTELBEDS_BASE_URL` | `SUPPLIER_GATEWAY_DB_PASSWORD`, `SANDBOX_AIR_WEBHOOK_SECRET`, `DUFFEL_ACCESS_TOKEN` (absent = no live air), `HOTELBEDS_API_KEY` + `HOTELBEDS_SECRET` (absent = no live hotels) |
| order (8085/9085) | `SUPPLIER_GATEWAY_ADDRESS`, `STRIPE_BASE_URL`, `travelos.finance.settlement.<provider>` | `ORDER_DB_PASSWORD`, `STRIPE_SECRET_KEY` (absent = sandbox payments only) |
| audit (8088) | — | `AUDIT_DB_PASSWORD` |
| disruption (8089/9089) | `ORDER_ADDRESS`, `TEMPORAL_ADDRESS`, `TEMPORAL_ENABLED` | `DISRUPTION_DB_PASSWORD` |
| enterprise-context (8090/9090) | `TRAVEL_CORE_ADDRESS`, `CONTEXT_SYNC_INTERVAL`, `CONTEXT_SCHEDULER_TICK`, `SANDBOX_CONNECTOR_PAGE_SIZE`, `PROFILE_DOCUMENT_RETENTION_DAYS`, `PROFILE_RETENTION_SWEEP` | `ENTERPRISE_CONTEXT_DB_PASSWORD`, `TRAVELOS_FIELD_KEY` (required: profile field encryption, base64 AES-256), `SANDBOX_CONNECTOR_WEBHOOK_SECRET`, `SCIM_TOKEN_<TENANT>` (absent = no provisioning for that tenant), `WORKDAY_REPORT_URL/USERNAME/PASSWORD`, `GOOGLE_SERVICE_ACCOUNT_EMAIL/KEY`, `MICROSOFT_TENANT_ID/CLIENT_ID/CLIENT_SECRET`, `SALESFORCE_INSTANCE_URL/CLIENT_ID/CLIENT_SECRET`, `CONCUR_CLIENT_ID/CLIENT_SECRET/REFRESH_TOKEN` (each absent = that adapter is not registered) |
| learning (8091/9091) | `LEARNING_DEPLOYMENT_CLASS` (SANDBOX or LIVE), `LEARNING_DEFAULT_MODE`, `LEARNING_*` algorithm parameters | `LEARNING_DB_PASSWORD` |
| assistance (8092) | `ASSISTANCE_SLA_{CRITICAL,HIGH,NORMAL,LOW}`, `ASSISTANCE_ESCALATION_SWEEP`, `NOTIFICATIONS_DISPATCH_INTERVAL`, `SAFETY_CHECKIN_GRACE` | `ASSISTANCE_DB_PASSWORD`, `SENDGRID_API_KEY` + `NOTIFICATIONS_FROM_ADDRESS` (absent = no email), `SLACK_WEBHOOK_URL` (absent = no chat) |
| trip-planning worker (8086) | `*_ADDRESS` of every service, `TEMPORAL_ADDRESS`, `DEFAULT_TIMEZONE`, `APPROVAL_TIMEOUT` | `PAYMENT_TOKEN` (the Slice 1 opaque token; a registered instrument's token in production) |
| optimization (9083), llm-gateway (9087) | Python; `LLM_PROVIDER` | `ANTHROPIC_API_KEY` (absent = the deterministic offline extractor) |
| web (8080) | `OIDC_AUTHORITY`, `OIDC_CLIENT_ID`, `DEPLOYMENT_CLASS`, `ENVIRONMENT_LABEL`, `<SERVICE>_URL` for every proxied route (`/api/v1/*`, `/scim/v2/`) | — |
| keycloak | realm `platform/local/keycloak/travelos-realm.json`; federation via `deploy/keycloak/federate.sh` | `KC_BOOTSTRAP_ADMIN_PASSWORD`, the brokered IdP's client secret (shell only) |

## Where each environment gets its values

- **compose** (`platform/local/docker-compose*.yml`): dev-only defaults labelled as such; live
  credentials pass through from the shell and are never defaulted.
- **kind** (`deploy/kind/secrets.sh`): database passwords, the field key and the SCIM token are
  generated per cluster; live credentials come from the shell; one Secret per service holds only
  what that service needs.
- **EKS** (`deploy/helm/travelos/values-eks.yaml`): External Secrets map every key above to
  `travelos/<ENV>/<service>/<group>` in AWS Secrets Manager; `deploy/helm/travelos-infra` runs the
  Postgres role job from `postgres-app-passwords`.

## Databases and migrations

Flyway runs each service's migrations at startup against its own database (V1…Vn under
`src/main/resources/db/migration`); a new service (assistance, Phase 6) needs its role and
database created once (`platform/local/postgres/init/01-databases.sql` for a fresh volume; the
infra chart's role job on Kubernetes; on an existing local volume run the three statements by hand,
as the Phase 10 run did). Migrations are additive; none drops data.

## Scheduled work (one instance runs each; all are idempotent and lock their rows)

| Service | Sweep | Interval |
|---|---|---|
| every service | outbox relay (safety net; commits nudge it) | 500 ms |
| travel-core | approval expiry: escalate once, then expire | `APPROVAL_SWEEP` 1m |
| order | credit expiry | `travelos.finance.credit-sweep` 1h |
| enterprise-context | connector scheduler; document retention purge | 10 s / 1h |
| assistance | case escalation; notification dispatch; safety check-in grace | 1m / 5 s / 1m |
| disruption | impact-confirmation retry for DETECTED notices | see `ImpactRetryScheduler` |
