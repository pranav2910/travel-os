#!/usr/bin/env bash
# Deterministic failure injection for disruption recovery on kind. The fault must land inside the
# critical window and prove it did (tripwires), or the test is worthless.
#
#   A. the order service is gone when the airline cancels (impact confirmation is deferred, not
#      dropped); the optimizer is gone so the recovery provably parks at Optimize (attempt >= 2)
#      while the order service is taken away underneath it; the optimizer returns and the recovery
#      reaches CHANGING with nobody to call (ChangeOrder attempt >= 2); the recovery worker is killed
#      mid-retry; both come back: exactly one changed order, one logical recovery, one supplier
#      reissue, Temporal continues from history, one final audit decision. Every hold is proven by
#      a tripwire before the next fault is injected, so nothing depends on timing luck.
#   B. tenant isolation holds throughout.
set -euo pipefail
# Whatever happens, never leave a service scaled to zero (an HPA will not scale up from 0).
restore_scaled() { for d in order optimization; do kubectl -n travelos scale deploy/"$d" --replicas=1 >/dev/null 2>&1 || true; done; }
trap restore_scaled EXIT
cd "$(dirname "$0")/.."
export E2E_BACKEND=kind
KC=http://localhost:18180; CORE=http://localhost:18081; POLICY=http://localhost:18082; ORDER=http://localhost:18085
SUPPLIER=http://localhost:18084; AUDIT=http://localhost:18088; DISRUPTION=http://localhost:18089
SEED=platform/local/seed/policies/acme-us-standard.json
WEBHOOK_SECRET=$(grep '^SANDBOX_AIR_WEBHOOK_SECRET=' deploy/kind/.secrets.env | cut -d= -f2)
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
check() { python3 -c "import sys,json; d=json.load(sys.stdin); $1"; }
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
ALICE=$(tok alice); BOB=$(tok bob); CAROL=$(tok carol); ZOE=$(tok zoe)
for svc in "$CORE" "$POLICY" "$ORDER" "$SUPPLIER" "$DISRUPTION" http://localhost:18086; do
  for i in $(seq 1 90); do curl -sf "$svc/actuator/health/readiness" >/dev/null && break; sleep 2; done
  curl -sf "$svc/actuator/health/readiness" >/dev/null || fail "$svc not ready"
done
tmp_describe() { kubectl exec -n travelos-infra deploy/temporal -- temporal workflow describe --address 127.0.0.1:7233 --namespace travelos -w "$1" -o json 2>/dev/null; }
tmp_show() { kubectl exec -n travelos-infra deploy/temporal -- temporal workflow show --address 127.0.0.1:7233 --namespace travelos -w "$1" -o json 2>/dev/null; }
pending_attempt() { tmp_describe "$1" | python3 -c 'import sys,json; d=json.load(sys.stdin); pa=[p for p in d.get("pendingActivities",[]) if p.get("activityType",{}).get("name")==sys.argv[1]]; print(pa[0].get("attempt",0) if pa else 0)' "$2" 2>/dev/null || echo 0; }
scale_away() { kubectl -n travelos scale deploy/"$1" --replicas=0 >/dev/null; kubectl -n travelos wait --for=delete pod -l app="$1" --timeout=120s >/dev/null 2>&1 || true; echo "  $1 scaled to 0 (pods gone)"; }
tripwire() { # $1 disruption, $2 activity: the named activity must be visibly retrying before we go on
  local att=0 i; for i in $(seq 1 60); do att=$(pending_attempt "$1" "$2"); [ "${att:-0}" -ge 2 ] && break; sleep 1; done
  [ "${att:-0}" -ge 2 ] && pass "tripwire: $2 is in flight and retrying (attempt $att)" || fail "$2 not retrying (attempt ${att:-0}): the injection did not take effect"
}
kill_pods() { for app in "$@"; do kubectl -n travelos delete pod -l app="$app" --wait=false >/dev/null; echo "  killed $app pod(s)"; done; }
http_of() { case "$1" in order) echo "$ORDER";; trip-planning) echo http://localhost:18086;; disruption) echo "$DISRUPTION";; esac; }
wait_ready() { for app in "$@"; do kubectl -n travelos rollout status deploy/"$app" --timeout=240s >/dev/null; local url; url=$(http_of "$app"); local i; for i in $(seq 1 60); do curl -sf "$url/actuator/health/readiness" >/dev/null 2>&1 && break; sleep 1; done; curl -sf "$url/actuator/health/readiness" >/dev/null || fail "$app not reachable after restart"; done; }
# read as bob (manager): a traveler is only named on the disruption once impact confirmation reads the order
disruption_status() { curl -s "$DISRUPTION/api/v1/disruptions/$1" -H "Authorization: Bearer $BOB" | json 'd["status"]'; }
wait_disruption() { local t=0; while [ $t -lt "$3" ]; do local s; s=$(disruption_status "$1"); case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac; case "$s" in NO_ALTERNATIVE|FAILED|MANUAL_INTERVENTION_REQUIRED) echo "$s"; return 0;; esac; sleep 1; t=$((t+1)); done; echo TIMEOUT; }

python3 -c "import json,sys; d=json.load(open('$SEED')); d['autonomy']['flightRebooking']={'enabled': True, 'maxIncrementalCost': 10000}; print(json.dumps({'document': d, 'note': 'chaos slice 2'}))" \
  | curl -s -X POST "$POLICY/api/v1/policies" -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' -d @- >/dev/null
TRIP=$(curl -s -X POST "$CORE/api/v1/trips" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: chaos2-$(date +%s%N)" -H 'Content-Type: application/json' \
  -d '{"intent":{"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z","returnAfter":"2026-10-07T20:00:00Z","latestReturn":"2026-10-08T06:00:00Z","purpose":"chaos"},"source":"API"}' | json 'd["tripId"]')
for i in $(seq 1 180); do [ "$(curl -s "$CORE/api/v1/trips/$TRIP" -H "Authorization: Bearer $ALICE" | json 'd["status"]')" = BOOKED ] && break; sleep 1; done
[ "$(curl -s "$CORE/api/v1/trips/$TRIP" -H "Authorization: Bearer $ALICE" | json 'd["status"]')" = BOOKED ] || fail "trip $TRIP did not book in 180s"
read -r ORDER_ID EXT FLIGHT TOTAL <<<"$(curl -s "$ORDER/api/v1/orders?tripId=$TRIP" -H "Authorization: Bearer $BOB" | check "o=[x for x in d if x['status']=='CONFIRMED'][0]; item=[i for i in o['items'] if i['status']=='CONFIRMED'][0]; print(o['orderId'], o['externalOrderId'], item['flights'][0]['flightNumber'], o['total']['amountMinor'])")"
echo "== A. trip $TRIP booked on $FLIGHT ($EXT); the optimizer and the order service go away; the airline cancels the flight"
# Hold 1: with no optimizer the recovery will provably stop at Optimize, never at ChangeOrder,
# while the order service is taken away underneath it.
scale_away optimization
scale_away order
BODY=$(printf '{"eventId":"chaos2-%s","type":"FLIGHT_CANCELLED","externalOrderId":"%s","flightNumber":"%s","date":"2026-10-06","reason":"crew availability","reaccommodation":{"fareDeltaMinor":7300}}' "$(date +%s%N)" "$EXT" "$FLIGHT")
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/^.* //')
DSR=$(curl -s -X POST "$SUPPLIER/api/v1/suppliers/sandbox-air/events" -H 'Content-Type: application/json' -H "X-Supplier-Signature: sha256=$SIG" -d "$BODY" | json 'd["disruptionId"]')
echo "  disruption $DSR"
# Impact confirmation needs the order service: the Disruption service records first and defers.
for i in $(seq 1 30); do [ -n "$(disruption_status "$DSR" 2>/dev/null)" ] && break; sleep 1; done   # recorded from the broker
sleep 6                                                                                             # long enough for a confirmation attempt (and its retry) to have happened
[ "$(disruption_status "$DSR")" = DETECTED ] && pass "tripwire: $DSR stays DETECTED (impact deferred, not dropped) while the order service is gone" || fail "$DSR is $(disruption_status "$DSR") with no order service to confirm against"
kubectl -n travelos scale deploy/order --replicas=1 >/dev/null; wait_ready order
[ "$(wait_disruption "$DSR" OPTIMIZING 120)" = OPTIMIZING ] && pass "$DSR: impact confirmed once the order service returned; alternatives searched and filtered; now OPTIMIZING" || fail "$DSR stuck at $(disruption_status "$DSR")"
tripwire "$DSR" Optimize
# Hold 2: the recovery is parked before ChangeOrder, so now the order service can be taken away
# for the critical window with certainty; then the optimizer returns and the recovery walks into it.
scale_away order
kubectl -n travelos scale deploy/optimization --replicas=1 >/dev/null; kubectl -n travelos rollout status deploy/optimization --timeout=240s >/dev/null; echo "  optimization back"
[ "$(wait_disruption "$DSR" CHANGING 240)" = CHANGING ] && pass "$DSR reached CHANGING with no order service to call" || fail "$DSR: $(disruption_status "$DSR")"
tripwire "$DSR" ChangeOrder
kill_pods trip-planning
kubectl -n travelos scale deploy/order --replicas=1 >/dev/null; echo "  order service scaled back to 1"
wait_ready trip-planning order; pass "recovery worker and order service are back"
[ "$(disruption_status "$DSR")" = CHANGING ] && pass "state survived: $DSR still CHANGING, not FAILED" || fail "state lost: $(disruption_status "$DSR")"
[ "$(wait_disruption "$DSR" RESOLVED 240)" = RESOLVED ] && pass "$DSR RESOLVED by the replacement worker once the order service returned" || fail "$DSR ended $(disruption_status "$DSR")"
FINAL=$(tmp_show "$DSR" | python3 -c 'import sys,json; ev=json.load(sys.stdin).get("events",[]); a=[e["activityTaskStartedEventAttributes"]["attempt"] for e in ev if e["eventType"]=="EVENT_TYPE_ACTIVITY_TASK_STARTED" and e["activityTaskStartedEventAttributes"].get("attempt",1)>1]; print(max(a, default=1))')
[ "$FINAL" -ge 2 ] && pass "tripwire: Temporal history records ChangeOrder succeeding on attempt $FINAL (continued from persisted history)" || fail "no retried activity in history"
curl -s "$ORDER/api/v1/orders/$ORDER_ID" -H "Authorization: Bearer $BOB" | check "assert d['status']=='CHANGED', d['status']; ch=[c for c in d['changes'] if c['status']=='APPLIED']; assert len(ch)==1, d['changes']; assert d['total']['amountMinor']==$TOTAL+7300, d['total']; print('  change', ch[0]['changeId'], ch[0]['idempotencyKey'])" && pass "exactly one changed order (+7300)" || fail "order state"
N_CHG=$(kubectl exec -n travelos-infra statefulset/postgres -- psql -U travelos -d orders -tAc "select count(*) from order_change where order_id='$ORDER_ID'")
N_SBX=$(kubectl exec -n travelos-infra statefulset/postgres -- psql -U travelos -d supplier_gateway -tAc "select count(*) from sandbox_order_change where external_order_id='$EXT'")
N_DEC=$(kubectl exec -n travelos-infra statefulset/postgres -- psql -U travelos -d disruption -tAc "select count(*) from recovery_decision where disruption_id='$DSR'")
N_OUT=$(kubectl exec -n travelos-infra statefulset/postgres -- psql -U travelos -d disruption -tAc "select count(*) from recovery_outcome where disruption_id='$DSR'")
[ "$N_CHG" = 1 ] && [ "$N_SBX" = 1 ] && [ "$N_DEC" = 1 ] && [ "$N_OUT" = 1 ] && pass "one logical recovery: order_change=1, supplier reissues=1, decision records=1, outcome records=1" || fail "rows: order_change=$N_CHG sandbox_order_change=$N_SBX recovery_decision=$N_DEC recovery_outcome=$N_OUT"
EV=$(kubectl exec -n travelos-infra deploy/kafka -- bash -c "for t in travel.order travel.disruption; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic \$t --from-beginning --timeout-ms 4000 2>/dev/null; done" | grep "$DSR" | python3 -c 'import sys,json,collections; c=collections.Counter(json.loads(l)["eventType"] for l in sys.stdin if l.strip()); print(dict(sorted(c.items())))')
echo "  events for $DSR: $EV"
echo "$EV" | grep -q "'travel.order.changed': 1" && echo "$EV" | grep -q "'travel.disruption.resolved': 1" && echo "$EV" | grep -q "'travel.disruption.decision-ready': 1" && pass "one order.changed, one decision-ready, one resolved on the broker" || fail "duplicate or missing events"
curl -s "$AUDIT/api/v1/audit/trips/$TRIP/decisions" -H "Authorization: Bearer $ALICE" | check "assert len(d['disruptions'])==1 and d['disruptions'][0]['status']=='RESOLVED', d['disruptions']" && pass "one final audit decision for the trip" || fail "audit ledger"

echo "== B. tenant isolation under chaos"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$DISRUPTION/api/v1/disruptions/$DSR" -H "Authorization: Bearer $ZOE")" = 404 ] && pass "globex gets 404 for $DSR" || fail "isolation"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$DISRUPTION/api/v1/disruptions/$DSR/approval" -H "Authorization: Bearer $ZOE" -H "Idempotency-Key: x" -H 'Content-Type: application/json' -d '{"decision":"APPROVE"}')" = 404 ] && pass "globex cannot approve it either" || fail "isolation (approval)"
echo
echo "Chaos slice 2 on kind: PASS"
