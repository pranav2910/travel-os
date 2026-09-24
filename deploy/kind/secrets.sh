#!/usr/bin/env bash
# Generates every secret the stack needs and creates them as Kubernetes Secrets. Values live only
# in deploy/kind/.secrets.env (gitignored) so re-runs are stable; nothing is in Helm values or git.
set -euo pipefail
cd "$(dirname "$0")"
ENV_FILE=.secrets.env
if [ ! -f "$ENV_FILE" ]; then
  gen() { openssl rand -hex 16; }
  cat > "$ENV_FILE" <<ENV
POSTGRES_SUPERUSER_PASSWORD=$(gen)
ENTERPRISE_CONTEXT_DB_PASSWORD=$(gen)
TRAVEL_CORE_DB_PASSWORD=$(gen)
POLICY_DB_PASSWORD=$(gen)
SUPPLIER_GATEWAY_DB_PASSWORD=$(gen)
APPROVAL_DB_PASSWORD=$(gen)
ORDER_DB_PASSWORD=$(gen)
AUDIT_DB_PASSWORD=$(gen)
DISRUPTION_DB_PASSWORD=$(gen)
LEARNING_DB_PASSWORD=$(gen)
ASSISTANCE_DB_PASSWORD=$(gen)
SCIM_TOKEN_ACME=${SCIM_TOKEN_ACME:-$(gen)}
SANDBOX_AIR_WEBHOOK_SECRET=$(gen)
SANDBOX_CONNECTOR_WEBHOOK_SECRET=$(gen)
KEYCLOAK_ADMIN_PASSWORD=$(gen)
PAYMENT_TOKEN=tok_corp_visa_sandbox
ENV
  chmod 600 "$ENV_FILE"
  echo "generated $ENV_FILE"
fi
set -a; . "./$ENV_FILE"; set +a
if [ -z "${LEARNING_DB_PASSWORD:-}" ]; then
  LEARNING_DB_PASSWORD=$(openssl rand -hex 16); echo "LEARNING_DB_PASSWORD=$LEARNING_DB_PASSWORD" >> "$ENV_FILE"; export LEARNING_DB_PASSWORD
fi
if [ -z "${TRAVELOS_FIELD_KEY:-}" ]; then
  TRAVELOS_FIELD_KEY=$(openssl rand -base64 32); echo "TRAVELOS_FIELD_KEY=$TRAVELOS_FIELD_KEY" >> "$ENV_FILE"; export TRAVELOS_FIELD_KEY
fi
if [ -z "${SANDBOX_CONNECTOR_WEBHOOK_SECRET:-}" ]; then
  SANDBOX_CONNECTOR_WEBHOOK_SECRET=$(openssl rand -hex 16); echo "SANDBOX_CONNECTOR_WEBHOOK_SECRET=$SANDBOX_CONNECTOR_WEBHOOK_SECRET" >> "$ENV_FILE"; export SANDBOX_CONNECTOR_WEBHOOK_SECRET
fi

apply() { kubectl create secret generic "$@" --dry-run=client -o yaml | kubectl apply -f - >/dev/null; }
# infra namespace
apply postgres-superuser -n travelos-infra --from-literal=password="$POSTGRES_SUPERUSER_PASSWORD"
apply postgres-app-passwords -n travelos-infra \
  --from-literal=ENTERPRISE_CONTEXT_DB_PASSWORD="$ENTERPRISE_CONTEXT_DB_PASSWORD" \
  --from-literal=TRAVEL_CORE_DB_PASSWORD="$TRAVEL_CORE_DB_PASSWORD" \
  --from-literal=POLICY_DB_PASSWORD="$POLICY_DB_PASSWORD" \
  --from-literal=SUPPLIER_GATEWAY_DB_PASSWORD="$SUPPLIER_GATEWAY_DB_PASSWORD" \
  --from-literal=APPROVAL_DB_PASSWORD="$APPROVAL_DB_PASSWORD" \
  --from-literal=ORDER_DB_PASSWORD="$ORDER_DB_PASSWORD" \
  --from-literal=AUDIT_DB_PASSWORD="$AUDIT_DB_PASSWORD" \
  --from-literal=DISRUPTION_DB_PASSWORD="$DISRUPTION_DB_PASSWORD" \
  --from-literal=LEARNING_DB_PASSWORD="$LEARNING_DB_PASSWORD" \
  --from-literal=ASSISTANCE_DB_PASSWORD="$ASSISTANCE_DB_PASSWORD"
apply temporal-db -n travelos-infra --from-literal=POSTGRES_USER=travelos --from-literal=POSTGRES_PWD="$POSTGRES_SUPERUSER_PASSWORD"
apply keycloak-admin -n travelos-infra --from-literal=KC_BOOTSTRAP_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD"
# application namespace: one Secret per service, only what that service needs
apply travel-core-secrets -n travelos --from-literal=TRAVEL_CORE_DB_PASSWORD="$TRAVEL_CORE_DB_PASSWORD"
apply policy-secrets -n travelos --from-literal=POLICY_DB_PASSWORD="$POLICY_DB_PASSWORD"
# Phase 4: live supplier credentials come from the shell (never from .secrets.env or git); absent = sandbox only.
apply supplier-gateway-secrets -n travelos --from-literal=SUPPLIER_GATEWAY_DB_PASSWORD="$SUPPLIER_GATEWAY_DB_PASSWORD" --from-literal=SANDBOX_AIR_WEBHOOK_SECRET="$SANDBOX_AIR_WEBHOOK_SECRET" \
  --from-literal=DUFFEL_ACCESS_TOKEN="${DUFFEL_ACCESS_TOKEN:-}" --from-literal=HOTELBEDS_API_KEY="${HOTELBEDS_API_KEY:-}" --from-literal=HOTELBEDS_SECRET="${HOTELBEDS_SECRET:-}"
if [ -n "${DUFFEL_ACCESS_TOKEN:-}" ] || [ -n "${HOTELBEDS_API_KEY:-}" ]; then echo "supplier-gateway: live supplier credentials taken from your shell (not stored)"; fi
apply disruption-secrets -n travelos --from-literal=DISRUPTION_DB_PASSWORD="$DISRUPTION_DB_PASSWORD"
# Phase 7: the SCIM provisioning token is generated here (print it with `kubectl get secret` for the IdP);
# enterprise credentials (Workday, Google, Microsoft, Salesforce, Concur) come from the shell, never from git.
apply enterprise-context-secrets -n travelos --from-literal=ENTERPRISE_CONTEXT_DB_PASSWORD="$ENTERPRISE_CONTEXT_DB_PASSWORD" --from-literal=SANDBOX_CONNECTOR_WEBHOOK_SECRET="${SANDBOX_CONNECTOR_WEBHOOK_SECRET:-$(openssl rand -hex 16)}" --from-literal=TRAVELOS_FIELD_KEY="$TRAVELOS_FIELD_KEY" \
  --from-literal=SCIM_TOKEN_ACME="$SCIM_TOKEN_ACME" \
  --from-literal=WORKDAY_REPORT_URL="${WORKDAY_REPORT_URL:-}" --from-literal=WORKDAY_USERNAME="${WORKDAY_USERNAME:-}" --from-literal=WORKDAY_PASSWORD="${WORKDAY_PASSWORD:-}" \
  --from-literal=GOOGLE_SERVICE_ACCOUNT_EMAIL="${GOOGLE_SERVICE_ACCOUNT_EMAIL:-}" --from-literal=GOOGLE_SERVICE_ACCOUNT_KEY="${GOOGLE_SERVICE_ACCOUNT_KEY:-}" \
  --from-literal=MICROSOFT_TENANT_ID="${MICROSOFT_TENANT_ID:-}" --from-literal=MICROSOFT_CLIENT_ID="${MICROSOFT_CLIENT_ID:-}" --from-literal=MICROSOFT_CLIENT_SECRET="${MICROSOFT_CLIENT_SECRET:-}" \
  --from-literal=SALESFORCE_INSTANCE_URL="${SALESFORCE_INSTANCE_URL:-}" --from-literal=SALESFORCE_CLIENT_ID="${SALESFORCE_CLIENT_ID:-}" --from-literal=SALESFORCE_CLIENT_SECRET="${SALESFORCE_CLIENT_SECRET:-}" \
  --from-literal=CONCUR_CLIENT_ID="${CONCUR_CLIENT_ID:-}" --from-literal=CONCUR_CLIENT_SECRET="${CONCUR_CLIENT_SECRET:-}" --from-literal=CONCUR_REFRESH_TOKEN="${CONCUR_REFRESH_TOKEN:-}"
apply learning-secrets -n travelos --from-literal=LEARNING_DB_PASSWORD="$LEARNING_DB_PASSWORD"
apply assistance-secrets -n travelos --from-literal=ASSISTANCE_DB_PASSWORD="$ASSISTANCE_DB_PASSWORD"
apply order-secrets -n travelos --from-literal=ORDER_DB_PASSWORD="$ORDER_DB_PASSWORD" --from-literal=STRIPE_SECRET_KEY="${STRIPE_SECRET_KEY:-}"
apply audit-secrets -n travelos --from-literal=AUDIT_DB_PASSWORD="$AUDIT_DB_PASSWORD"
apply trip-planning-secrets -n travelos --from-literal=PAYMENT_TOKEN="$PAYMENT_TOKEN"
if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
  apply llm-gateway-secrets -n travelos --from-literal=LLM_PROVIDER=anthropic --from-literal=ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY"
  echo "llm-gateway: anthropic provider (key from your shell, not stored)"
else
  apply llm-gateway-secrets -n travelos --from-literal=LLM_PROVIDER=fake
  echo "llm-gateway: fake provider (export ANTHROPIC_API_KEY to use Claude)"
fi
echo "secrets applied to travelos-infra and travelos"
