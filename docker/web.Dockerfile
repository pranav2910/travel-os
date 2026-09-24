# syntax=docker/dockerfile:1.7
# The web edge: the React app built once, served by an unprivileged nginx that also proxies the
# public API routes to the owning services (same table as the Helm ingress). One image for the
# Docker stack, kind and EKS; the runtime config (/config.json) comes from environment variables.
FROM node:22-alpine AS build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run typecheck && npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine
LABEL org.opencontainers.image.source="https://github.com/pranav2910/travel-os" \
      org.opencontainers.image.licenses="Proprietary"
USER root
# Everything nginx writes lives under /tmp so the root filesystem can be read-only (Helm sets it so).
# Keep nginx's own entrypoint scripts (they render /etc/nginx/templates/*.template with envsubst).
RUN rm -f /etc/nginx/conf.d/default.conf \
 && sed -i 's#pid .*#pid /tmp/nginx.pid;#; s#include /etc/nginx/conf.d/\*.conf;#include /tmp/conf.d/*.conf;#' /etc/nginx/nginx.conf \
 && grep -q 'include /tmp/conf.d/\*.conf;' /etc/nginx/nginx.conf \
 && chown -R 10001:10001 /etc/nginx /usr/share/nginx/html
COPY --chown=10001:10001 docker/web/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --chown=10001:10001 docker/web/entrypoint.sh /docker-entrypoint.d/10-travelos-config.envsh
COPY --from=build --chown=10001:10001 /web/dist /usr/share/nginx/html
USER 10001
# The rendered server block goes under /tmp as well (nginx's envsubst step honours these).
ENV NGINX_ENVSUBST_OUTPUT_DIR=/tmp/conf.d \
    NGINX_ENVSUBST_STREAM_OUTPUT_DIR=/tmp/stream-conf.d
ENV PORT=8080 \
    OIDC_AUTHORITY=http://localhost:8180/realms/travelos \
    OIDC_CLIENT_ID=travelos-web \
    DEPLOYMENT_CLASS=SANDBOX \
    ENVIRONMENT_LABEL=local \
    TRAVEL_CORE_URL=http://travel-core:8081 \
    POLICY_URL=http://policy:8082 \
    ORDER_URL=http://order:8085 \
    AUDIT_URL=http://audit:8088 \
    DISRUPTION_URL=http://disruption:8089 \
    ENTERPRISE_CONTEXT_URL=http://enterprise-context:8090 \
    LEARNING_URL=http://learning:8091 \
    ASSISTANCE_URL=http://assistance:8092
EXPOSE 8080
