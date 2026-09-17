#!/bin/sh
# Sourced by nginx's entrypoint (an .envsh file) before the templates are rendered: writes the public
# runtime configuration (nothing secret), the proxy snippet, and exports the container's DNS resolver.
# Everything is written under /tmp: the root filesystem is read-only (Helm and compose both run it so).
set -eu
RESOLVER=$(awk '/^nameserver/ { print $2; exit }' /etc/resolv.conf 2>/dev/null || true)
export RESOLVER="${RESOLVER:-127.0.0.11}"
cat > /tmp/config.json <<JSON
{
  "oidcAuthority": "${OIDC_AUTHORITY}",
  "oidcClientId": "${OIDC_CLIENT_ID}",
  "deploymentClass": "${DEPLOYMENT_CLASS}",
  "environmentLabel": "${ENVIRONMENT_LABEL}"
}
JSON
cat > /tmp/travelos-proxy.conf <<'CONF'
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header Authorization $http_authorization;
proxy_set_header Idempotency-Key $http_idempotency_key;
proxy_read_timeout 60s;
proxy_buffering off;
CONF
mkdir -p /tmp/conf.d /tmp/stream-conf.d # where nginx's envsubst step renders the server block
