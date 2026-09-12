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
KEYCLOAK_ADMIN_PASSWORD=$(gen)
PAYMENT_TOKEN=tok_corp_visa_sandbox
ENV
  chmod 600 "$ENV_FILE"
  echo "generated $ENV_FILE"
fi
set -a; . "./$ENV_FILE"; set +a

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
  --from-literal=AUDIT_DB_PASSWORD="$AUDIT_DB_PASSWORD"
apply temporal-db -n travelos-infra --from-literal=POSTGRES_USER=travelos --from-literal=POSTGRES_PWD="$POSTGRES_SUPERUSER_PASSWORD"
apply keycloak-admin -n travelos-infra --from-literal=KC_BOOTSTRAP_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD"
# application namespace: one Secret per service, only what that service needs
apply travel-core-secrets -n travelos --from-literal=TRAVEL_CORE_DB_PASSWORD="$TRAVEL_CORE_DB_PASSWORD"
apply policy-secrets -n travelos --from-literal=POLICY_DB_PASSWORD="$POLICY_DB_PASSWORD"
apply supplier-gateway-secrets -n travelos --from-literal=SUPPLIER_GATEWAY_DB_PASSWORD="$SUPPLIER_GATEWAY_DB_PASSWORD"
apply order-secrets -n travelos --from-literal=ORDER_DB_PASSWORD="$ORDER_DB_PASSWORD"
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
