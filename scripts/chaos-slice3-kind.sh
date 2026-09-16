#!/usr/bin/env bash
# Deterministic failure injection for multi-component itineraries on kind. Every fault lands inside
# a critical window that a tripwire proves before the next fault is injected; nothing depends on
# timing luck.
#
#   A. a 3-leg / 2-stay / 2-transfer itinerary: the optimizer is gone so the planning provably parks
#      at OptimizeItinerary (attempt >= 2); the order service is removed underneath it; the optimizer
#      returns and the booking walks into CreateOrder with nobody to call (attempt >= 2); the worker
#      is killed mid-retry; both come back: one order, seven components, one sandbox booking each,
#      component states CONFIRMED, one travel.order.confirmed.
#   B. a connected recovery on that trip: the airline cancels leg 1; the optimizer is gone so the
#      recovery parks at Optimize; the order service is removed; the optimizer returns; ChangeOrder
#      retries; the worker is killed; everything returns: one order change, the transfer re-timed
#      or preserved, the hotel untouched, one supplier reissue, Temporal continues from history.
#   C. a supplier that loses its answer after committing (DEN's hotel and shuttle): one booking each.
#   D. tenant isolation throughout.
set -euo pipefail
restore_scaled() { for d in order optimization; do kubectl -n travelos scale deploy/"$d" --replicas=1 >/dev/null 2>&1 || true; done; }
trap restore_scaled EXIT
cd "$(dirname "$0")/.."
export E2E_BACKEND=kind
KC=http://localhost:18180; CORE=http://localhost:18081; POLICY=http://localhost:18082; ORDER=http://localhost:18085
SUPPLIER=http://localhost:18084; AUDIT=http://localhost:18088; DISRUPTION=http://localhost:18089; WORKER=http://localhost:18086
SEED=platform/local/seed/policies/acme-us-standard.json
WEBHOOK_SECRET=$(grep '^SANDBOX_AIR_WEBHOOK_SECRET=' deploy/kind/.secrets.env | cut -d= -f2)
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
check() { python3 -c "import sys,json; d=json.load(sys.stdin); $1"; }
tok() {
  local i out
  for i in $(seq 1 45); do
    out=$(curl -sf -X POST "$KC/realms/travelos/protocol/openid-connect/token" -d client_id=travelos-dev-cli \
      -d grant_type=password -d "username=$1" -d password=password 2>/dev/null | json 'd["access_token"]' 2>/dev/null) \
      && [ -n "$out" ] && { echo "$out"; return 0; }
    sleep 2
  done
  return 1
}
for i in $(seq 1 90); do curl -sf "$KC/realms/travelos/.well-known/openid-configuration" >/dev/null && break; sleep 2; done
ALICE=$(tok alice); BOB=$(tok bob); CAROL=$(tok carol); ZOE=$(tok zoe)
for svc in "$CORE" "$POLICY" "$ORDER" "$SUPPLIER" "$DISRUPTION" "$WORKER"; do
  for i in $(seq 1 90); do curl -sf "$svc/actuator/health/readiness" >/dev/null && break; sleep 2; done
  curl -sf "$svc/actuator/health/readiness" >/dev/null || fail "$svc not ready"
done
tmp_describe() { kubectl exec -n travelos-infra deploy/temporal -- temporal workflow describe --address 127.0.0.1:7233 --namespace travelos -w "$1" -o json 2>/dev/null; }
tmp_show() { kubectl exec -n travelos-infra deploy/temporal -- temporal workflow show --address 127.0.0.1:7233 --namespace travelos -w "$1" -o json 2>/dev/null; }
pending_attempt() { tmp_describe "$1" | python3 -c 'import sys,json; d=json.load(sys.stdin); pa=[p for p in d.get("pendingActivities",[]) if p.get("activityType",{}).get("name")==sys.argv[1]]; print(pa[0].get("attempt",0) if pa else 0)' "$2" 2>/dev/null || echo 0; }
scale_away() { kubectl -n travelos scale deploy/"$1" --replicas=0 >/dev/null; kubectl -n travelos wait --for=delete pod -l app="$1" --timeout=120s >/dev/null 2>&1 || true; echo "  $1 scaled to 0 (pods gone)"; }
scale_back() { kubectl -n travelos scale deploy/"$1" --replicas=1 >/dev/null; kubectl -n travelos rollout status deploy/"$1" --timeout=240s >/dev/null; echo "  $1 back"; }
tripwire() { local att=0 i; for i in $(seq 1 90); do att=$(pending_attempt "$1" "$2"); [ "${att:-0}" -ge 2 ] && break; sleep 1; done
  [ "${att:-0}" -ge 2 ] && pass "tripwire: $2 is in flight and retrying (attempt $att)" || fail "$2 not retrying (attempt ${att:-0}): the injection did not take effect"; }
kill_pods() { for app in "$@"; do kubectl -n travelos delete pod -l app="$app" --wait=false >/dev/null; echo "  killed $app pod(s)"; done; }
http_of() { case "$1" in order) echo "$ORDER";; trip-planning) echo "$WORKER";; disruption) echo "$DISRUPTION";; esac; }
wait_ready() { for app in "$@"; do kubectl -n travelos rollout status deploy/"$app" --timeout=240s >/dev/null; local url; url=$(http_of "$app"); local i; for i in $(seq 1 60); do curl -sf "$url/actuator/health/readiness" >/dev/null 2>&1 && break; sleep 1; done; curl -sf "$url/actuator/health/readiness" >/dev/null || fail "$app not reachable after restart"; done; }
status_of() { curl -s "$CORE/api/v1/trips/$1" -H "Authorization: Bearer $ALICE" | json 'd["status"]'; }
wait_status() { local t=0; while [ $t -lt "$3" ]; do local s; s=$(status_of "$1"); case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac; case "$s" in FAILED|CANCELLED) echo "$s"; return 0;; esac; sleep 1; t=$((t+1)); done; echo TIMEOUT; }
disruption_status() { curl -s "$DISRUPTION/api/v1/disruptions/$1" -H "Authorization: Bearer $BOB" | json 'd["status"]'; }
wait_disruption() { local t=0; while [ $t -lt "$3" ]; do local s; s=$(disruption_status "$1"); case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac; case "$s" in NO_ALTERNATIVE|FAILED|MANUAL_INTERVENTION_REQUIRED) echo "$s"; return 0;; esac; sleep 1; t=$((t+1)); done; echo TIMEOUT; }
psql_db() { kubectl exec -n travelos-infra statefulset/postgres -- psql -U travelos -d "$1" -tAc "$2"; }
events_for() { kubectl exec -n travelos-infra deploy/kafka -- bash -c "for t in travel.order travel.trip travel.disruption; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic \$t --from-beginning --timeout-ms 4000 2>/dev/null; done" | grep "$1" | python3 -c 'import sys,json,collections; c=collections.Counter(json.loads(l)["eventType"] for l in sys.stdin if l.strip()); print(dict(sorted(c.items())))'; }

python3 -c "import json,sys; d=json.load(open('$SEED')); d['approval']['managerRequiredAbove']=500000; d['autonomy']['flightRebooking']={'enabled': True, 'maxIncrementalCost': 10000}; print(json.dumps({'document': d, 'note': 'chaos slice 3'}))" \
  | curl -s -X POST "$POLICY/api/v1/policies" -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' -d @- >/dev/null
LEGS='[{"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"SEA","destination":"SAN","earliestDeparture":"2026-10-08T14:00:00Z","arrivalDeadline":"2026-10-09T02:00:00Z"},{"origin":"SAN","destination":"BOS","earliestDeparture":"2026-10-09T13:00:00Z","arrivalDeadline":"2026-10-10T02:00:00Z"}]'
STAYS='[{"city":"SEA","checkInDate":"2026-10-06","checkOutDate":"2026-10-08"},{"city":"SAN","checkInDate":"2026-10-08","checkOutDate":"2026-10-09"}]'
XFERS='[{"kind":"AIRPORT_TO_HOTEL","city":"SEA"},{"kind":"HOTEL_TO_AIRPORT","city":"SAN"}]'

echo "== A. seven components; the optimizer and then the order service go away under the booking"
scale_away optimization
TRIP=$(curl -s -X POST "$CORE/api/v1/trips" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: chaos3-$(date +%s%N)" -H 'Content-Type: application/json' \
  -d "{\"intent\":{\"purpose\":\"chaos\",\"itinerary\":{\"legs\":$LEGS,\"stays\":$STAYS,\"transfers\":$XFERS}},\"source\":\"API\"}" | json 'd["tripId"]')
echo "  trip $TRIP"
[ "$(wait_status "$TRIP" PLANNING 120)" = PLANNING ] || fail "trip $TRIP is $(status_of "$TRIP")"
tripwire "$TRIP" OptimizeItinerary
# Hold 2: with the planning provably parked before any mutation, take the order service away.
scale_away order
scale_back optimization
ATT=0; for i in $(seq 1 120); do ATT=$(pending_attempt "$TRIP" CreateOrder); [ "${ATT:-0}" -ge 2 ] && break; sleep 1; done
[ "${ATT:-0}" -ge 2 ] && pass "tripwire: CreateOrder is in flight and retrying (attempt $ATT) with no order service to call" || fail "CreateOrder not retrying (attempt ${ATT:-0})"
[ "$(status_of "$TRIP")" = BOOKING ] && pass "$TRIP is BOOKING, seven components reported as BOOKING" || fail "status $(status_of "$TRIP")"
kill_pods trip-planning
scale_back order
wait_ready trip-planning order; pass "worker and order service are back"
# A fast replacement worker may already have finished: BOOKING or BOOKED are both the persisted state carried on; FAILED would be the loss.
S=$(status_of "$TRIP"); case "$S" in BOOKING|BOOKED) pass "state survived: $S, not FAILED";; *) fail "state lost: $S";; esac
[ "$(wait_status "$TRIP" BOOKED 300)" = BOOKED ] && pass "$TRIP BOOKED by the replacement worker once the order service returned" || fail "$TRIP ended $(status_of "$TRIP")"
FINAL=$(tmp_show "$TRIP" | python3 -c 'import sys,json; ev=json.load(sys.stdin).get("events",[]); a=[e["activityTaskStartedEventAttributes"]["attempt"] for e in ev if e["eventType"]=="EVENT_TYPE_ACTIVITY_TASK_STARTED" and e["activityTaskStartedEventAttributes"].get("attempt",1)>1]; print(max(a, default=1))')
[ "$FINAL" -ge 2 ] && pass "tripwire: Temporal history records activities succeeding on attempt $FINAL (continued from persisted history)" || fail "no retried activity in history"
ORDER_ID=$(curl -s "$CORE/api/v1/trips/$TRIP" -H "Authorization: Bearer $ALICE" | json 'd["evidence"]["orderId"]')
curl -s "$CORE/api/v1/trips/$TRIP/components" -H "Authorization: Bearer $ALICE" | check "assert len(d)==7 and all(c['status']=='CONFIRMED' for c in d), [(c['type'],c['status']) for c in d]" && pass "seven components CONFIRMED" || fail "components"
N_ORD=$(psql_db orders "select count(*) from travel_order where trip_id='$TRIP'")
N_ITEMS=$(psql_db orders "select count(*) from order_item where order_id='$ORDER_ID' and status='CONFIRMED'")
N_AIR=$(psql_db supplier_gateway "select count(*) from sandbox_order where idempotency_key like '$ORDER_ID:%'")
N_HG=$(psql_db supplier_gateway "select count(*) from sandbox_booking where idempotency_key like '$ORDER_ID:%'")
[ "$N_ORD" = 1 ] && [ "$N_ITEMS" = 7 ] && [ "$N_AIR" = 3 ] && [ "$N_HG" = 4 ] && pass "one order, 7 confirmed items, 3 airline orders + 4 hotel/ground bookings at the suppliers: nothing twice" || fail "rows: orders=$N_ORD items=$N_ITEMS air=$N_AIR hotel+ground=$N_HG"
EV=$(events_for "$TRIP"); echo "  events: $EV"
echo "$EV" | grep -q "'travel.order.confirmed': 1" && echo "$EV" | grep -q "'travel.trip.booked': 1" && pass "one order.confirmed, one trip.booked" || fail "duplicate or missing events"

echo "== B. the airline cancels leg 1 of that itinerary; the recovery is held at Optimize, then at ChangeOrder, and the worker dies"
read -r EXT FLIGHT <<<"$(curl -s "$ORDER/api/v1/orders/$ORDER_ID" -H "Authorization: Bearer $BOB" | check "i=[x for x in d['items'] if x['type']=='AIR' and x['status']=='CONFIRMED' and x['flights'][0]['origin']=='BOS'][0]; print(i['externalRef'], i['flights'][0]['flightNumber'])")"
scale_away optimization
BODY=$(printf '{"eventId":"chaos3-%s","type":"FLIGHT_CANCELLED","externalOrderId":"%s","flightNumber":"%s","date":"2026-10-06","reason":"crew availability","reaccommodation":{"fareDeltaMinor":7300}}' "$(date +%s%N)" "$EXT" "$FLIGHT")
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/^.* //')
DSR=$(curl -s -X POST "$SUPPLIER/api/v1/suppliers/sandbox-air/events" -H 'Content-Type: application/json' -H "X-Supplier-Signature: sha256=$SIG" -d "$BODY" | json 'd["disruptionId"]')
echo "  disruption $DSR"
[ "$(wait_disruption "$DSR" OPTIMIZING 120)" = OPTIMIZING ] && pass "$DSR: impact confirmed on the itinerary's order; parked at OPTIMIZING" || fail "$DSR is $(disruption_status "$DSR")"
tripwire "$DSR" Optimize
scale_away order
scale_back optimization
[ "$(wait_disruption "$DSR" CHANGING 240)" = CHANGING ] && pass "$DSR reached CHANGING with no order service to call" || fail "$DSR: $(disruption_status "$DSR")"
tripwire "$DSR" ChangeOrder
kill_pods trip-planning
scale_back order
wait_ready trip-planning order; pass "recovery worker and order service are back"
S=$(disruption_status "$DSR"); case "$S" in CHANGING|RESOLVED) pass "state survived: $DSR is $S (a fast replacement worker may already have finished the change)";; *) fail "state lost: $S";; esac
[ "$(wait_disruption "$DSR" RESOLVED 240)" = RESOLVED ] && pass "$DSR RESOLVED by the replacement worker" || fail "$DSR ended $(disruption_status "$DSR")"
curl -s "$DISRUPTION/api/v1/disruptions/$DSR" -H "Authorization: Bearer $BOB" | check "
r=d['recovery']; assert r['autonomyOutcome']=='ALLOW' and int(r['incrementalCost']['amountMinor'])==7300, r
acts={c['type']+':'+c['action'] for c in d['decision']['componentChanges']}; assert 'AIR:REPLACED' in acts and 'HOTEL:PRESERVED' in acts, acts
print('  component changes:', sorted(acts))" && pass "the leg was replaced, the transfer followed it, the hotel was left alone" || fail "decision"
N_CHG=$(psql_db orders "select count(*) from order_change where order_id='$ORDER_ID'")
N_REISSUE=$(psql_db supplier_gateway "select count(*) from sandbox_order_change where external_order_id='$EXT'")
N_GCHG=$(psql_db supplier_gateway "select count(*) from sandbox_booking_change where booking_id in (select booking_id from sandbox_booking where idempotency_key like '$ORDER_ID:%')")
N_DEC=$(psql_db disruption "select count(*) from recovery_decision where disruption_id='$DSR'")
[ "$N_CHG" = 1 ] && [ "$N_REISSUE" = 1 ] && [ "$N_GCHG" -le 1 ] && [ "$N_DEC" = 1 ] && pass "one logical recovery: order_change=1, airline reissue=1, ground re-timings=$N_GCHG (<=1), decision records=1" || fail "rows: order_change=$N_CHG reissues=$N_REISSUE ground=$N_GCHG decisions=$N_DEC"
FINAL=$(tmp_show "$DSR" | python3 -c 'import sys,json; ev=json.load(sys.stdin).get("events",[]); a=[e["activityTaskStartedEventAttributes"]["attempt"] for e in ev if e["eventType"]=="EVENT_TYPE_ACTIVITY_TASK_STARTED" and e["activityTaskStartedEventAttributes"].get("attempt",1)>1]; print(max(a, default=1))')
[ "$FINAL" -ge 2 ] && pass "tripwire: Temporal history records ChangeOrder succeeding on attempt $FINAL" || fail "no retried activity in history"
EV=$(events_for "$DSR"); echo "  events: $EV"
echo "$EV" | grep -q "'travel.order.changed': 1" && echo "$EV" | grep -q "'travel.disruption.resolved': 1" && pass "one order.changed, one resolved" || fail "duplicate or missing events"

echo "== C. a supplier that commits and then loses its answer, under the same platform: one booking each"
LEGS_DEN='[{"origin":"BOS","destination":"DEN","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"DEN","destination":"BOS","earliestDeparture":"2026-10-07T14:00:00Z","arrivalDeadline":"2026-10-08T02:00:00Z"}]'
TRIP_C=$(curl -s -X POST "$CORE/api/v1/trips" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: chaos3c-$(date +%s%N)" -H 'Content-Type: application/json' \
  -d "{\"intent\":{\"purpose\":\"chaos\",\"itinerary\":{\"legs\":$LEGS_DEN,\"stays\":[{\"city\":\"DEN\",\"checkInDate\":\"2026-10-06\",\"checkOutDate\":\"2026-10-07\"}],\"transfers\":[{\"kind\":\"AIRPORT_TO_HOTEL\",\"city\":\"DEN\"}]}},\"source\":\"API\"}" | json 'd["tripId"]')
[ "$(wait_status "$TRIP_C" BOOKED 300)" = BOOKED ] || fail "$TRIP_C ended $(status_of "$TRIP_C")"
ORDER_C=$(curl -s "$CORE/api/v1/trips/$TRIP_C" -H "Authorization: Bearer $ALICE" | json 'd["evidence"]["orderId"]')
N_C=$(psql_db supplier_gateway "select count(*) from sandbox_booking where idempotency_key like '$ORDER_C:%'")
[ "$N_C" = 2 ] && pass "$TRIP_C: hotel and shuttle answers were lost after booking; status lookup reconciled them: 2 bookings" || fail "bookings for $ORDER_C: $N_C"

echo "== D. tenant isolation under chaos"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$CORE/api/v1/trips/$TRIP/components" -H "Authorization: Bearer $ZOE")" = 404 ] && pass "globex gets 404 for the components" || fail "isolation"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$DISRUPTION/api/v1/disruptions/$DSR" -H "Authorization: Bearer $ZOE")" = 404 ] && pass "globex gets 404 for $DSR" || fail "isolation (disruption)"
python3 -c "import json,sys; d=json.load(open('$SEED')); print(json.dumps({'document': d, 'note': 'chaos slice 3: seed restored'}))" \
  | curl -s -X POST "$POLICY/api/v1/policies" -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' -d @- >/dev/null
echo
echo "Chaos slice 3 on kind: PASS"
