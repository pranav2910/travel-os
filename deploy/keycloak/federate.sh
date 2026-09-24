#!/usr/bin/env bash
# Phase 7: enterprise federation. Brokers a customer's identity provider (OIDC or SAML) into the
# travelos realm so their people sign in with their own IdP, and maps the claims the platform
# needs (tenant_id, employee_id, roles) onto the realm's token. Runs kcadm inside the Keycloak
# container/pod; every credential comes from the shell and is never written to disk or git.
#
#   FEDERATION_ALIAS=acme-okta FEDERATION_KIND=oidc \
#   FEDERATION_ISSUER=https://acme.okta.com/oauth2/default \
#   FEDERATION_CLIENT_ID=... FEDERATION_CLIENT_SECRET=... \
#   FEDERATION_TENANT=acme FEDERATION_EMPLOYEE_CLAIM=employeeNumber FEDERATION_GROUPS_CLAIM=groups \
#   KEYCLOAK_ADMIN_PASSWORD=... deploy/keycloak/federate.sh
#
#   FEDERATION_KIND=saml FEDERATION_METADATA_URL=https://.../metadata.xml ... deploy/keycloak/federate.sh
#
# KC_EXEC decides where kcadm runs: "docker exec travelos-keycloak" (compose, default) or
# "kubectl exec -n travelos-infra deploy/keycloak --" (kind/EKS).
set -euo pipefail
: "${FEDERATION_ALIAS:?alias of the identity provider, e.g. acme-okta}"
: "${FEDERATION_KIND:?oidc or saml}"
: "${FEDERATION_TENANT:?the tenant every user of this IdP belongs to}"
: "${KEYCLOAK_ADMIN_PASSWORD:?the realm admin password (from the secrets mechanism)}"
KC_EXEC=${KC_EXEC:-docker exec travelos-keycloak}
KC_URL=${KC_URL:-http://localhost:8080}
REALM=${REALM:-travelos}
EMPLOYEE_CLAIM=${FEDERATION_EMPLOYEE_CLAIM:-employeeNumber}
GROUPS_CLAIM=${FEDERATION_GROUPS_CLAIM:-groups}

kc() { $KC_EXEC /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server "$KC_URL" --realm master --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

if [ "$FEDERATION_KIND" = "oidc" ]; then
  : "${FEDERATION_ISSUER:?issuer URL}" "${FEDERATION_CLIENT_ID:?client id}" "${FEDERATION_CLIENT_SECRET:?client secret}"
  kc create identity-provider/instances -r "$REALM" \
    -s alias="$FEDERATION_ALIAS" -s providerId=oidc -s enabled=true -s trustEmail=true -s storeToken=false \
    -s firstBrokerLoginFlowAlias="first broker login" -s syncMode=FORCE \
    -s config.issuer="$FEDERATION_ISSUER" \
    -s config.authorizationUrl="$FEDERATION_ISSUER/v1/authorize" -s config.tokenUrl="$FEDERATION_ISSUER/v1/token" \
    -s config.jwksUrl="$FEDERATION_ISSUER/v1/keys" -s config.useJwksUrl=true -s config.validateSignature=true \
    -s config.clientId="$FEDERATION_CLIENT_ID" -s config.clientSecret="$FEDERATION_CLIENT_SECRET" \
    -s config.defaultScope="openid profile email $GROUPS_CLAIM" -s config.clientAuthMethod=client_secret_post \
    -s config.pkceEnabled=true -s config.pkceMethod=S256 2>/dev/null || echo "identity provider $FEDERATION_ALIAS exists; updating mappers only"
else
  : "${FEDERATION_METADATA_URL:?SAML metadata URL}"
  kc create identity-provider/instances -r "$REALM" \
    -s alias="$FEDERATION_ALIAS" -s providerId=saml -s enabled=true -s trustEmail=true \
    -s firstBrokerLoginFlowAlias="first broker login" -s syncMode=FORCE \
    -s config.entityId="$KC_URL/realms/$REALM" -s config.metadataDescriptorUrl="$FEDERATION_METADATA_URL" \
    -s config.useMetadataDescriptorUrl=true -s config.principalType=SUBJECT -s config.nameIDPolicyFormat=urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress \
    -s config.wantAssertionsSigned=true -s config.validateSignature=true 2>/dev/null || echo "identity provider $FEDERATION_ALIAS exists; updating mappers only"
fi

mapper() { # name, mapper type, extra -s args
  local name=$1 type=$2; shift 2
  kc create "identity-provider/instances/$FEDERATION_ALIAS/mappers" -r "$REALM" \
    -s name="$name" -s identityProviderAlias="$FEDERATION_ALIAS" -s identityProviderMapper="$type" -s config.syncMode=FORCE "$@" 2>/dev/null \
    || echo "mapper $name exists"
}
# Every user of this IdP belongs to one tenant: the claim the platform's services require.
mapper tenant-id hardcoded-attribute-idp-mapper -s config.attribute=tenant_id -s "config.attribute.value=$FEDERATION_TENANT"
if [ "$FEDERATION_KIND" = "oidc" ]; then
  mapper employee-id oidc-user-attribute-idp-mapper -s "config.claim=$EMPLOYEE_CLAIM" -s config.user.attribute=employee_id
  # IdP groups -> realm roles the platform understands (MANAGER, TRAVEL_ADMIN, FINANCE; TRAVELER for everyone)
  for role in MANAGER TRAVEL_ADMIN FINANCE; do
    mapper "group-$role" oidc-advanced-role-idp-mapper -s "config.claims=[{\"key\":\"$GROUPS_CLAIM\",\"value\":\"travelos-$(echo $role | tr A-Z a-z)\"}]" -s "config.role=$role"
  done
  mapper traveler hardcoded-role-idp-mapper -s config.role=TRAVELER
else
  mapper employee-id saml-user-attribute-idp-mapper -s "config.attribute.name=$EMPLOYEE_CLAIM" -s config.user.attribute=employee_id
  for role in MANAGER TRAVEL_ADMIN FINANCE; do
    mapper "group-$role" saml-advanced-role-idp-mapper -s "config.attributes=[{\"key\":\"$GROUPS_CLAIM\",\"value\":\"travelos-$(echo $role | tr A-Z a-z)\"}]" -s "config.role=$role"
  done
  mapper traveler hardcoded-role-idp-mapper -s config.role=TRAVELER
fi
echo "federation $FEDERATION_ALIAS ($FEDERATION_KIND) configured for tenant $FEDERATION_TENANT; the realm's tokens carry tenant_id, employee_id and roles from the IdP"
