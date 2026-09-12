#!/usr/bin/env bash
# Demonstrates the rollback procedure end to end on kind:
#   1. note the current revision and image tag
#   2. deploy a "new release" (same images retagged <sha>-next, as a stand-in for a bad build)
#   3. roll back with `helm rollback` to the previous revision
#   4. verify every Deployment runs the previous tag again and is healthy
set -euo pipefail
cd "$(dirname "$0")/.."
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }
REGISTRY=localhost:5001/travel-os
SERVICES="travel-core policy supplier-gateway order audit trip-planning optimization llm-gateway"
tag_of() { kubectl -n travelos get deploy "$1" -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's/.*://'; }

GOOD=$(tag_of travel-core); REV=$(helm -n travelos history travelos -o json | python3 -c 'import sys,json; print(max(r["revision"] for r in json.load(sys.stdin)))')
echo "== known-good: revision $REV, tag $GOOD"

NEXT="${GOOD}-next"
for s in $SERVICES; do docker tag "$REGISTRY/$s:$GOOD" "$REGISTRY/$s:$NEXT"; docker push -q "$REGISTRY/$s:$NEXT" >/dev/null; done
echo "== deploying $NEXT (revision $((REV+1)))"
helm upgrade travelos deploy/helm/travelos -n travelos -f deploy/helm/travelos/values-kind.yaml --set global.image.tag="$NEXT" --wait --timeout 12m >/dev/null
[ "$(tag_of order)" = "$NEXT" ] && pass "revision $((REV+1)) runs $NEXT" || fail "upgrade did not roll"

echo "== rolling back to revision $REV"
helm -n travelos rollback travelos "$REV" --wait --timeout 12m >/dev/null
for s in $SERVICES; do [ "$(tag_of "$s")" = "$GOOD" ] || fail "$s runs $(tag_of "$s"), expected $GOOD"; done
pass "all 8 deployments run $GOOD again"
kubectl -n travelos get pods --no-headers | grep -vq -E 'Running|Completed' && fail "unhealthy pods after rollback" || pass "every pod Running"
curl -sf http://localhost:18081/actuator/health/readiness >/dev/null && pass "travel-core serves after rollback" || fail "travel-core not ready"
helm -n travelos history travelos | tail -4
echo; echo "Rollback on kind: PASS"
