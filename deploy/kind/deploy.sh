#!/usr/bin/env bash
# Builds images for the current commit, pushes them to the local registry as :<sha>, feeds the
# platform's config files into the cluster, and deploys infra + services with Helm.
#   deploy/kind/deploy.sh            # build + deploy HEAD
#   TAG=<sha> deploy/kind/deploy.sh  # deploy an already-pushed tag (no build)
set -euo pipefail
cd "$(dirname "$0")/../.."
SHA="$(git rev-parse --short=12 HEAD)"
# Uncommitted changes get their own tag: an image tag must never mean two different builds, and a
# Deployment whose image reference did not change would keep running the old one.
if [ -n "$(git status --porcelain --untracked-files=all)" ]; then
  # The dirty hash covers tracked changes AND the contents of untracked files (a new service edited
  # before its first commit must still produce a new tag, or the nodes keep the cached image).
  SHA="${SHA}-dirty-$( (git diff HEAD --binary; git status --porcelain --untracked-files=all; git ls-files --others --exclude-standard -z | xargs -0 shasum 2>/dev/null) | shasum | cut -c1-8)"
fi
TAG="${TAG:-$SHA}"
REGISTRY=localhost:5001/travel-os

if [ "$TAG" = "$SHA" ] && [ "${SKIP_BUILD:-}" != "1" ]; then
  make -s jars
  REGISTRY="$REGISTRY" scripts/build-images.sh "$TAG" --push
fi

deploy/kind/secrets.sh

cm() { kubectl create configmap "$1" -n travelos-infra "${@:2}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null; }
cm keycloak-realm         --from-file=platform/local/keycloak/travelos-realm.json
cm temporal-dynamicconfig --from-file=platform/local/temporal/dynamicconfig/development-sql.yaml
cm kafka-init-script      --from-file=platform/local/kafka/create-topics.sh
cm otel-collector-config  --from-file=platform/local/observability/otel-collector.yaml
cm tempo-config           --from-file=tempo.yaml=platform/local/observability/tempo.yaml
cm grafana-datasources    --from-file=platform/local/observability/grafana/provisioning/datasources/tempo.yaml

echo "== infra"
helm upgrade --install travelos-infra deploy/helm/travelos-infra -n travelos-infra --wait --timeout 10m
echo "== services @ $TAG"
helm dependency update deploy/helm/travelos >/dev/null
helm upgrade --install travelos deploy/helm/travelos -n travelos \
  -f deploy/helm/travelos/values-kind.yaml --set global.image.tag="$TAG" --wait --timeout 12m
helm -n travelos history travelos | tail -3
kubectl -n travelos get pods -o wide
