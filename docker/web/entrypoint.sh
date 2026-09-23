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
# The identity provider's origin (scheme://host[:port]) is the only foreign origin the app talks to:
# token requests (connect-src) and the silent-renew iframe (frame-src: it opens on the provider and
# lands back on this origin's /silent-renew.html, so both origins are allowed). Derived from the authority.
OIDC_ORIGIN=$(printf '%s' "${OIDC_AUTHORITY}" | sed -E 's#^([a-z]+://[^/]+).*$#\1#')
export OIDC_ORIGIN
# One snippet, included by every location: security headers apply to every response the edge
# serves, the shell, the assets, the proxied API and its error documents alike.
cat > /tmp/travelos-headers.conf <<CONF
add_header X-Content-Type-Options nosniff always;
add_header X-Frame-Options SAMEORIGIN always;
add_header Referrer-Policy strict-origin-when-cross-origin always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self' ${OIDC_ORIGIN}; frame-src 'self' ${OIDC_ORIGIN}; frame-ancestors 'self'; base-uri 'self'; form-action 'self' ${OIDC_ORIGIN}; object-src 'none'" always;
CONF
cat > /tmp/travelos-proxy.conf <<'CONF'
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header Authorization $http_authorization;
proxy_set_header Idempotency-Key $http_idempotency_key;
proxy_connect_timeout 5s;
proxy_read_timeout 60s;
proxy_buffering off;
include /tmp/travelos-headers.conf;
CONF
mkdir -p /tmp/conf.d /tmp/stream-conf.d # where nginx's envsubst step renders the server block
