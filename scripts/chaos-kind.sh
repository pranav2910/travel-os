#!/usr/bin/env bash
# Failure injection on the kind stack. Pods die while trips are in flight; the platform must end
# with exactly one order per trip, no lost state, and NetworkPolicies must actually be enforced.
set -euo pipefail
# Whatever happens, never leave the order service scaled to zero (an HPA will not scale up from 0).
restore_order() { kubectl -n travelos scale deploy/order --replicas=1 >/dev/null 2>&1 || true; }
trap restore_order EXIT
cd "$(dirname "$0")/.."
export E2E_BACKEND=kind
KC=http://localhost:18180; CORE=http://localhost:18081; POLICY=http://localhost:18082; ORDER=http://localhost:18085; AUDIT=http://localhost:18088
SEED=platform/local/seed/policies/acme-us-standard.json
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
tok() { # retries: Keycloak may still be starting, or restarting, when the run begins
  local i out
  for i in $(seq 1 45); do
    out=$(curl -sf -X POST "$KC/realms/travelos/protocol/openid-connect/token" -d client_id=travelos-dev-cli \
      -d grant_type=password -d "username=$1" -d password=password 2>/dev/null | json 'd["access_token"]' 2>/dev/null) \
      && [ -n "$out" ] && { echo "$out"; return 0; }
    sleep 2
  done
  return 1
}
# tokens come from Keycloak, which is not one of the app services above: wait for its realm too
for i in $(seq 1 90); do curl -sf "$KC/realms/travelos/.well-known/openid-configuration" >/dev/null && break; sleep 2; done
curl -sf "$KC/realms/travelos/.well-known/openid-configuration" >/dev/null || fail "Keycloak at $KC not ready"
ALICE=$(tok alice); BOB=$(tok bob); CAROL=$(tok carol)
publish_policy() { python3 -c "import json,sys; d=json.load(open('$SEED')); $1; print(json.dumps({'document': d, 'note': 'chaos'}))" | curl -s -X POST "$POLICY/api/v1/policies" -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' -d @- | json '"v%s" % d["version"]'; }
create_trip() { curl -s -X POST "$CORE/api/v1/trips" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: chaos-$(date +%s%N)" -H 'Content-Type: application/json' -d '{"intent":{"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z","returnAfter":"2026-10-07T20:00:00Z","latestReturn":"2026-10-08T06:00:00Z","purpose":"chaos"},"source":"API"}' | json 'd["tripId"]'; }
status() { local i; for i in 1 2 3 4 5 6 7 8 9 10; do local out; out=$(curl -s "$CORE/api/v1/trips/$1" -H "Authorization: Bearer $ALICE" | json 'd["status"]' 2>/dev/null) && [ -n "$out" ] && { echo "$out"; return 0; }; sleep 1; done; echo UNREACHABLE; }
wait_status() { local t=0; while [ $t -lt "$3" ]; do local s; s=$(status "$1"); case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac; case "$s" in FAILED|CANCELLED) echo "$s"; return 0;; esac; sleep 1; t=$((t+1)); done; echo TIMEOUT; }
kill_pods() { for app in "$@"; do kubectl -n travelos delete pod -l app="$app" --wait=false >/dev/null; echo "  killed $app pod(s)"; done; }
http_of() { case "$1" in travel-core) echo "$CORE";; policy) echo "$POLICY";; order) echo "$ORDER";; audit) echo "$AUDIT";; trip-planning) echo http://localhost:18086;; supplier-gateway) echo http://localhost:18084;; esac; }
wait_ready() { # Ready pods, then the NodePort actually routes to them (kube-proxy lag after a kill)
  for app in "$@"; do kubectl -n travelos rollout status deploy/"$app" --timeout=240s >/dev/null
    local url; url=$(http_of "$app"); [ -z "$url" ] && continue
    local i; for i in $(seq 1 60); do curl -sf "$url/actuator/health/readiness" >/dev/null 2>&1 && break; sleep 1; done
    curl -sf "$url/actuator/health/readiness" >/dev/null || fail "$app not reachable after restart"
  done; }
orders_for() { curl -s "$ORDER/api/v1/orders?tripId=$1" -H "Authorization: Bearer $BOB" | json 'len([o for o in d if o["status"]=="CONFIRMED"]), len(d)'; }
order_rows() { kubectl exec -n travelos-infra statefulset/postgres -- psql -U travelos -d orders -tAc "select count(*) from travel_order where trip_id='$1'" 2>/dev/null || echo "?"; }

echo "== A. kill the worker AND travel-core while a trip waits for approval; approve; expect one order"
publish_policy 'd["approval"]["managerRequiredAbove"]=1' >/dev/null
A=$(create_trip); echo "  trip $A"
[ "$(wait_status "$A" AWAITING_APPROVAL 90)" = AWAITING_APPROVAL ] && pass "$A is waiting for a manager" || fail "$A did not reach AWAITING_APPROVAL"
kill_pods trip-planning travel-core
wait_ready trip-planning travel-core; pass "new pods are Ready"
[ "$(status "$A")" = AWAITING_APPROVAL ] && pass "state survived: still AWAITING_APPROVAL after both pods died" || fail "state lost"
curl -s -X POST "$CORE/api/v1/trips/$A/approval" -H "Authorization: Bearer $BOB" -H "Idempotency-Key: chaos-approve-$A" -H 'Content-Type: application/json' -d '{"decision":"APPROVE","comment":"after the outage"}' >/dev/null
[ "$(wait_status "$A" BOOKED 120)" = BOOKED ] && pass "$A BOOKED by the replacement worker" || fail "$A ended $(status "$A")"
read -r confirmed total <<<"$(orders_for "$A" | tr -d '(),')"
[ "$confirmed" = 1 ] && [ "$total" = 1 ] && pass "exactly one order ($confirmed confirmed of $total)" || fail "orders for $A: $confirmed confirmed of $total"

echo "== B. order service is down while a trip is booking; the worker dies mid-retry; order comes back; expect one order"
# Deterministic mid-flight injection (a kill after create_trip races a workflow that finishes in <1s):
# take the order service away first so CreateOrder is retrying when the worker is killed.
tmp_describe() { kubectl exec -n travelos-infra deploy/temporal -- temporal workflow describe --address 127.0.0.1:7233 --namespace travelos -w "$1" -o json 2>/dev/null; }
tmp_show() { kubectl exec -n travelos-infra deploy/temporal -- temporal workflow show --address 127.0.0.1:7233 --namespace travelos -w "$1" -o json 2>/dev/null; }
pending_attempt() { tmp_describe "$1" | python3 -c 'import sys,json; d=json.load(sys.stdin); pa=[p for p in d.get("pendingActivities",[]) if p.get("activityType",{}).get("name")=="CreateOrder"]; print(pa[0].get("attempt",0) if pa else 0)' 2>/dev/null || echo 0; }
publish_policy 'pass' >/dev/null   # seed policy: no approval
kubectl -n travelos scale deploy/order --replicas=0 >/dev/null; kubectl -n travelos wait --for=delete pod -l app=order --timeout=120s >/dev/null 2>&1 || true
echo "  order service scaled to 0"
B=$(create_trip); echo "  trip $B"
[ "$(wait_status "$B" BOOKING 90)" = BOOKING ] && pass "$B reached BOOKING with no order service to call" || fail "$B did not reach BOOKING: $(status "$B")"
ATT=0; for i in $(seq 1 30); do ATT=$(pending_attempt "$B"); [ "${ATT:-0}" -ge 2 ] && break; sleep 1; done
[ "${ATT:-0}" -ge 2 ] && pass "tripwire: CreateOrder is in flight and retrying (attempt $ATT)" || fail "CreateOrder is not retrying (attempt ${ATT:-0}); the injection did not take effect"
kill_pods trip-planning
kubectl -n travelos scale deploy/order --replicas=1 >/dev/null; echo "  order service scaled back to 1"
wait_ready trip-planning order; pass "worker and order service are back"
[ "$(status "$B")" != FAILED ] && pass "state survived: $B is $(status "$B"), not FAILED" || fail "workflow failed"
[ "$(wait_status "$B" BOOKED 240)" = BOOKED ] && pass "$B BOOKED by the replacement worker once the order service returned" || fail "$B ended $(status "$B")"
FINAL=$(tmp_show "$B" | python3 -c 'import sys,json; ev=json.load(sys.stdin).get("events",[]); a=[e for e in ev if e["eventType"]=="EVENT_TYPE_ACTIVITY_TASK_STARTED" and e["activityTaskStartedEventAttributes"].get("attempt",1)>1]; print(max([e["activityTaskStartedEventAttributes"]["attempt"] for e in a], default=1))')
[ "$FINAL" -ge 2 ] && pass "tripwire: Temporal history records CreateOrder succeeding on attempt $FINAL" || fail "history shows no retried activity; the kill was not mid-flight"
read -r confirmed total <<<"$(orders_for "$B" | tr -d '(),')"
[ "$confirmed" = 1 ] && [ "$total" = 1 ] && pass "exactly one order ($confirmed confirmed of $total); rows in orders db: $(order_rows "$B")" || fail "orders for $B: $confirmed confirmed of $total"
EV=$(kubectl exec -n travelos-infra deploy/kafka -- bash -c "for t in travel.order; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic \$t --from-beginning --timeout-ms 4000 2>/dev/null; done" | grep "$B" | python3 -c 'import sys,json,collections; c=collections.Counter(json.loads(l)["eventType"] for l in sys.stdin if l.strip()); print(dict(c))')
echo "  order events for $B: $EV"
echo "$EV" | grep -q "'travel.order.confirmed': 1" && pass "one confirmation event, no duplicates" || fail "duplicate or missing order events"

echo "== C. NetworkPolicy is enforced, not decorative"
kubectl -n travelos delete pod netprobe --ignore-not-found --wait=true >/dev/null 2>&1 || true
if kubectl -n travelos run netprobe --image=busybox:1.37 --restart=Never --rm -i --quiet --command -- sh -c 'nc -z -w 3 postgres.travelos-infra 5432' >/dev/null 2>&1; then
  fail "an unlabeled pod could reach Postgres: default-deny is not working"
else
  pass "an arbitrary pod in the namespace cannot reach Postgres (default deny)"
fi
kubectl -n travelos exec deploy/travel-core -- timeout 5 bash -c 'exec 3<>/dev/tcp/postgres.travelos-infra/5432 && echo ok' 2>/dev/null | grep -q ok && pass "travel-core can (its policy allows 5432 to the infra namespace)" || fail "travel-core cannot reach Postgres"
if kubectl -n travelos exec deploy/policy -- timeout 5 bash -c 'exec 3<>/dev/tcp/order/9085 && echo ok' 2>/dev/null | grep -q ok; then   # Calico drops, so the connect hangs: bound it
  fail "policy could open a gRPC connection to order: egress allow-list is not enforced"
else
  pass "policy cannot reach order (no rule allows it)"
fi
echo
echo "Chaos on kind: PASS"
