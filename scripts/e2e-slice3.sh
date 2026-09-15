#!/usr/bin/env bash
# Slice 3 end-to-end: hotels, ground transport and multi-city itineraries against the LIVE platform.
# Nothing is mocked; the suppliers are the SIMULATED sandbox adapters (documented fixtures per city).
#
#   A. a complete BOS -> SEA -> SAN -> BOS itinerary (3 legs, 2 stays, 2 transfers) from request to
#      confirmed sandbox bookings, with component status, total, decisions and an explanation
#   B. an infeasible itinerary (a stay nobody can reach) and a policy violation (trip budget):
#      FAILED with named reasons, no bookings
#   C. a stale approval: the SFO hotel re-prices on revalidation -> back to a person; the ORD hotel's
#      quote expires -> re-quoted at the same price -> booked without another approval
#   D. a later component fails (AUS hotel) -> compensation releases the legs; a cancellation is
#      refused (LAX hotel) -> exposure recorded, escalated, resolved by a travel admin
#   E. duplicates: the same request twice is one trip; a supplier timeout after a committed booking
#      (DEN) is reconciled by status lookup: exactly one booking per component
#   F. connected disruption recovery: a cancelled leg re-times the transfer and keeps the hotel
#      (autonomous, +$73); a +$180 cancellation needs a manager, same workflow continues
#   G. tenant isolation, approval authorization, supplier text as data, a red-eye across midnight
set -euo pipefail

BACKEND=${E2E_BACKEND:-compose}
if [ "$BACKEND" = kind ]; then
  : "${KC:=http://localhost:18180}" "${CORE:=http://localhost:18081}" "${POLICY:=http://localhost:18082}"
  : "${SUPPLIER:=http://localhost:18084}" "${ORDER:=http://localhost:18085}" "${AUDIT:=http://localhost:18088}"
  : "${DISRUPTION:=http://localhost:18089}" "${TEMPO:=http://localhost:13200}" "${WORKER:=http://localhost:18086}"
fi
KC=${KC:-http://localhost:8180}; CORE=${CORE:-http://localhost:8081}; POLICY=${POLICY:-http://localhost:8082}
SUPPLIER=${SUPPLIER:-http://localhost:8084}; ORDER=${ORDER:-http://localhost:8085}; AUDIT=${AUDIT:-http://localhost:8088}
DISRUPTION=${DISRUPTION:-http://localhost:8089}; TEMPO=${TEMPO:-http://localhost:3200}
WORKER=${WORKER:-http://localhost:8086}
COMPOSE="docker compose -f $(dirname "$0")/../platform/local/docker-compose.yml"
SEED="$(dirname "$0")/../platform/local/seed/policies/acme-us-standard.json"
if [ "$BACKEND" = kind ] && [ -f "$(dirname "$0")/../deploy/kind/.secrets.env" ]; then
  WEBHOOK_SECRET=${WEBHOOK_SECRET:-$(grep '^SANDBOX_AIR_WEBHOOK_SECRET=' "$(dirname "$0")/../deploy/kind/.secrets.env" | cut -d= -f2)}
fi
WEBHOOK_SECRET=${WEBHOOK_SECRET:-sandbox-air-dev-webhook-secret}

kafka_consume() {
  if [ "$BACKEND" = kind ]; then
    kubectl exec -n travelos-infra deploy/kafka -- bash -c "for t in $*; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic \$t --from-beginning --timeout-ms 4000 2>/dev/null; done"
  else
    $COMPOSE exec -T kafka bash -c "for t in $*; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic \$t --from-beginning --timeout-ms 4000 2>/dev/null; done"
  fi
}
psql_db() { # $1 database, $2 sql
  if [ "$BACKEND" = kind ]; then kubectl exec -n travelos-infra statefulset/postgres -- psql -U travelos -d "$1" -tAc "$2";
  else $COMPOSE exec -T postgres psql -U travelos -d "$1" -tAc "$2"; fi
}
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

echo "== 0. health"
for svc in "$CORE" "$POLICY" "$ORDER" "$AUDIT" "$SUPPLIER" "$DISRUPTION" "$WORKER"; do
  for i in $(seq 1 90); do curl -sf "$svc/actuator/health/readiness" >/dev/null && break; sleep 2; done
  curl -sf "$svc/actuator/health/readiness" >/dev/null || fail "$svc not ready"
done
for i in $(seq 1 90); do curl -sf "$KC/realms/travelos/.well-known/openid-configuration" >/dev/null && break; sleep 2; done
curl -sf "$KC/realms/travelos/.well-known/openid-configuration" >/dev/null || fail "Keycloak at $KC not ready"
pass "all services ready"
ALICE=$(tok alice); BOB=$(tok bob); CAROL=$(tok carol); ZOE=$(tok zoe)

publish_policy() { # $1 python mutation of the seed document, $2 note
  python3 -c "import json,sys; d=json.load(open('$SEED')); $1; print(json.dumps({'document': d, 'note': '$2'}))" \
    | curl -s -X POST "$POLICY/api/v1/policies" -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' -d @- \
    | json '"v%s" % d.get("version")'
}
# An itinerary request. $1 stays JSON, $2 transfers JSON, $3 legs JSON (defaults: BOS->SEA->SAN->BOS)
LEGS_DEFAULT='[{"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"SEA","destination":"SAN","earliestDeparture":"2026-10-08T14:00:00Z","arrivalDeadline":"2026-10-09T02:00:00Z"},{"origin":"SAN","destination":"BOS","earliestDeparture":"2026-10-09T13:00:00Z","arrivalDeadline":"2026-10-10T02:00:00Z"}]'
create_itinerary() { # $1 legs, $2 stays, $3 transfers, $4 idempotency key (optional) -> trip id
  local key=${4:-s3-$(date +%s%N)}
  curl -s -X POST "$CORE/api/v1/trips" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' \
    -d "{\"intent\":{\"purpose\":\"slice 3 roadshow\",\"itinerary\":{\"legs\":$1,\"stays\":$2,\"transfers\":$3}},\"source\":\"API\"}" | json 'd.get("tripId") or d'
}
status_of() { curl -s "$CORE/api/v1/trips/$1" -H "Authorization: Bearer $ALICE" | json 'd["status"]'; }
wait_status() { # $1 trip, $2 status list "A|B", $3 seconds
  local t=0; while [ $t -lt "$3" ]; do local s; s=$(status_of "$1"); case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac
    case "$s" in FAILED|CANCELLED) echo "$s"; return 0;; esac; sleep 1; t=$((t+1)); done; echo TIMEOUT; }
trip_view() { curl -s "$CORE/api/v1/trips/$1" -H "Authorization: Bearer $ALICE"; }
components() { curl -s "$CORE/api/v1/trips/$1/components" -H "Authorization: Bearer $ALICE"; }
orders_of() { curl -s "$ORDER/api/v1/orders?tripId=$1" -H "Authorization: Bearer $BOB"; }
approve() { # $1 trip, $2 token, $3 key -> http code
  curl -s -o /dev/null -w '%{http_code}' -X POST "$CORE/api/v1/trips/$1/approval" -H "Authorization: Bearer $2" -H "Idempotency-Key: $3" -H 'Content-Type: application/json' -d '{"decision":"APPROVE","comment":"slice 3"}'; }
notify() { # $1 event id, $2 external order id, $3 flight, $4 delta minor -> HTTP code + body
  local body; body=$(printf '{"eventId":"%s","type":"FLIGHT_CANCELLED","externalOrderId":"%s","flightNumber":"%s","date":"2026-10-06","reason":"crew availability","reaccommodation":{"fareDeltaMinor":%s}}' "$1" "$2" "$3" "$4")
  local sig; sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/^.* //')
  curl -s -w '\n%{http_code}' -X POST "$SUPPLIER/api/v1/suppliers/sandbox-air/events" -H 'Content-Type: application/json' -H "X-Supplier-Signature: sha256=$sig" -d "$body"
}
disruption_status() { curl -s "$DISRUPTION/api/v1/disruptions/$1" -H "Authorization: Bearer $BOB" | json 'd["status"]'; }
wait_disruption() { local t=0; while [ $t -lt "$3" ]; do local s; s=$(disruption_status "$1"); case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac
    case "$s" in NO_ALTERNATIVE|FAILED|MANUAL_INTERVENTION_REQUIRED) echo "$s"; return 0;; esac; sleep 1; t=$((t+1)); done; echo TIMEOUT; }
count_events() { kafka_consume "$1" | grep "$2" | python3 -c "import sys,json; print(sum(1 for l in sys.stdin if l.strip() and json.loads(l)['eventType']=='$3'))"; }
STAYS_A='[{"city":"SEA","checkInDate":"2026-10-06","checkOutDate":"2026-10-08"},{"city":"SAN","checkInDate":"2026-10-08","checkOutDate":"2026-10-09"}]'
XFERS_A='[{"kind":"AIRPORT_TO_HOTEL","city":"SEA"},{"kind":"HOTEL_TO_AIRPORT","city":"SAN"}]'

echo "== A. a complete multi-city itinerary: 3 legs, 2 stays, 2 transfers -> confirmed sandbox bookings"
publish_policy 'd["approval"]["managerRequiredAbove"]=500000' 'slice 3: no manager below USD 5000' | sed 's/^/  published /'
TRIP_A=$(create_itinerary "$LEGS_DEFAULT" "$STAYS_A" "$XFERS_A"); echo "  trip $TRIP_A"
[ "$(wait_status "$TRIP_A" BOOKED 240)" = BOOKED ] || { trip_view "$TRIP_A" | head -c 1200; echo; fail "expected BOOKED, got $(status_of "$TRIP_A")"; }
pass "$TRIP_A BOOKED"
V=$(trip_view "$TRIP_A")
echo "$V" | check "
c=d['components']; assert len(c)==7, len(c)
assert all(x['status']=='CONFIRMED' for x in c), [(x['componentId'],x['status']) for x in c]
assert [x['type'] for x in c]==['AIR','AIR','AIR','HOTEL','HOTEL','GROUND','GROUND'], [x['type'] for x in c]
assert all(x.get('externalRef') for x in c) and all(x['total']['amountMinor']>0 for x in c)
assert sum(x['total']['amountMinor'] for x in c)==d['total']['amountMinor'], (sum(x['total']['amountMinor'] for x in c), d['total'])
assert d['evidence']['orderId'].startswith('ord_') and d['evidence']['policyDecisionId'].startswith('pd_') and d['evidence']['optimizationRunId'].startswith('opt_')
assert d.get('approval') is None
print('  components:', [x['summary'] for x in c])
print('  total', d['total']['display'], 'explanation:', (d.get('explanation') or '')[:140])
" && pass "7 components CONFIRMED with supplier references; total = sum of components; evidence chain present" || fail "trip view"
ORDER_A=$(echo "$V" | json 'd["evidence"]["orderId"]')
curl -s "$ORDER/api/v1/orders/$ORDER_A" -H "Authorization: Bearer $BOB" | check "
assert d['status']=='CONFIRMED', d['status']
items=d['items']; assert len(items)==7 and all(i['status']=='CONFIRMED' for i in items)
assert [i['type'] for i in items]==['AIR','AIR','AIR','HOTEL','HOTEL','GROUND','GROUND'], 'booked in dependency order'
assert all(i.get('componentId','').startswith('cmp_') for i in items)
h=[i for i in items if i['type']=='HOTEL']; assert h[0]['hotel']['checkInDate']=='2026-10-06' and h[0]['hotel']['nights']==2 and h[1]['hotel']['city']=='SAN', h
g=[i for i in items if i['type']=='GROUND']; assert g[0]['ground']['vendorName'] and g[0]['ground']['pickup'], g
print('  order', d['orderId'], d['total']['display'], 'hotels:', [x['hotel']['name'] for x in h], 'ground:', [x['ground']['vendorName'] for x in g])
" && pass "one order, 7 items booked legs -> stays -> transfers, each tagged with its component" || fail "order view"
N_AIR=$(psql_db supplier_gateway "select count(*) from sandbox_order where idempotency_key like '$ORDER_A:%'")
N_HG=$(psql_db supplier_gateway "select count(*) from sandbox_booking where idempotency_key like '$ORDER_A:%'")
[ "$N_AIR" = 3 ] && [ "$N_HG" = 4 ] && pass "sandbox ledgers: 3 airline orders, 4 hotel/ground bookings, one per component" || fail "sandbox rows air=$N_AIR hotel+ground=$N_HG"
[ "$(count_events travel.trip "$TRIP_A" travel.trip.booked)" = 1 ] && kafka_consume travel.trip | grep "$TRIP_A" | grep '"travel.trip.booked"' | python3 -c "import sys,json; e=[json.loads(l) for l in sys.stdin if l.strip()][0]; c=e['data']['components']; assert len(c)==7 and all(x['status']=='CONFIRMED' for x in c); print('  trip.booked carries', len(c), 'components')" && pass "travel.trip.booked once, with every component" || fail "trip.booked event"
curl -s "$AUDIT/api/v1/audit/trips/$TRIP_A/decisions" -H "Authorization: Bearer $ALICE" | check "
assert d['status']=='BOOKED' and len(d['components'])==7, (d['status'], len(d.get('components',[])))
assert any('Itinerary components: 7' in n for n in d['narrative']), d['narrative']
print('  ledger:', [n for n in d['narrative'] if 'components' in n or 'confirmed' in n][:2])
" && pass "audit ledger lists the components" || fail "ledger"

echo "== B. infeasible itinerary and policy violation: FAILED with reasons, nothing booked"
TRIP_B1=$(create_itinerary "$LEGS_DEFAULT" '[{"city":"SEA","checkInDate":"2026-10-05","checkOutDate":"2026-10-08"}]' '[]'); echo "  trip $TRIP_B1 (stay starts before anyone can land)"
[ "$(wait_status "$TRIP_B1" FAILED 180)" = FAILED ] || fail "expected FAILED, got $(status_of "$TRIP_B1")"
trip_view "$TRIP_B1" | check "
assert d['failureStage']=='OPTIMIZATION' and d['failureCode']=='NO_FEASIBLE_ITINERARY', (d['failureStage'], d['failureCode'])
assert all(c['status']=='FAILED' for c in d['components']), [(c['type'],c['status']) for c in d['components']]
bad={c['type']:c.get('failureCode','') for c in d['components']}
assert bad['HOTEL'].startswith('NO_HOTEL_COVERS_NIGHTS:cmp_') and bad['AIR']=='INFEASIBLE', bad
print('  reason on the stay:', bad['HOTEL'], '; legs: INFEASIBLE (nothing else may be planned)')
" && pass "$TRIP_B1 FAILED at OPTIMIZATION with a named reason on the component" || fail "infeasible view"
[ "$(orders_of "$TRIP_B1" | json 'len(d)')" = 0 ] && pass "no order was created" || fail "an order exists for an infeasible trip"
publish_policy 'd["trip"]={"maxTotal": 50000, "onViolation": "DENY"}' 'slice 3: trip budget USD 500, deny' | sed 's/^/  published /'
TRIP_B2=$(create_itinerary "$LEGS_DEFAULT" "$STAYS_A" "$XFERS_A"); echo "  trip $TRIP_B2 (over a USD 500 trip budget)"
[ "$(wait_status "$TRIP_B2" FAILED 180)" = FAILED ] || fail "expected FAILED, got $(status_of "$TRIP_B2")"
trip_view "$TRIP_B2" | check "assert d['failureStage']=='POLICY' and d['failureCode']=='ITINERARY_DENIED', (d['failureStage'], d['failureCode']); print('  ', d['failureCode'])" && pass "$TRIP_B2 FAILED at POLICY: the whole itinerary is over budget" || fail "policy view"
[ "$(orders_of "$TRIP_B2" | json 'len(d)')" = 0 ] && pass "no order was created" || fail "an order exists for a denied trip"
publish_policy 'd["approval"]["managerRequiredAbove"]=500000' 'slice 3: budget restored' >/dev/null

echo "== C. a stale approval: a price rise on revalidation goes back to a person; an expired quote does not"
publish_policy 'd["approval"]["managerRequiredAbove"]=1' 'slice 3: every trip needs a manager' | sed 's/^/  published /'
LEGS_SFO='[{"origin":"BOS","destination":"SFO","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"SFO","destination":"BOS","earliestDeparture":"2026-10-07T14:00:00Z","arrivalDeadline":"2026-10-08T02:00:00Z"}]'
TRIP_C=$(create_itinerary "$LEGS_SFO" '[{"city":"SFO","checkInDate":"2026-10-06","checkOutDate":"2026-10-07"}]' '[]'); echo "  trip $TRIP_C (SFO's cheapest hotel re-prices on revalidation)"
[ "$(wait_status "$TRIP_C" AWAITING_APPROVAL 180)" = AWAITING_APPROVAL ] || fail "expected AWAITING_APPROVAL, got $(status_of "$TRIP_C")"
TOTAL_C1=$(trip_view "$TRIP_C" | json 'd["total"]["amountMinor"]'); APR_C1=$(trip_view "$TRIP_C" | json 'd["approval"]["approvalId"]')
[ "$(approve "$TRIP_C" "$BOB" "c1-$TRIP_C")" = 200 ] && pass "bob approved the plan at $TOTAL_C1" || fail "approval 1"
for i in $(seq 1 60); do V=$(trip_view "$TRIP_C"); S=$(echo "$V" | json 'd["status"]'); A=$(echo "$V" | json 'd["approval"]["approvalId"]'); [ "$S" = AWAITING_APPROVAL ] && [ "$A" != "$APR_C1" ] && break; [ "$S" = FAILED ] && break; sleep 1; done
[ "$S" = AWAITING_APPROVAL ] && [ "$A" != "$APR_C1" ] && pass "revalidation found a higher price: a new approval ($A) is pending" || fail "expected a second approval, status $S approval $A"
TOTAL_C2=$(echo "$V" | json 'd["total"]["amountMinor"]'); [ "$TOTAL_C2" -gt "$TOTAL_C1" ] && pass "total rose from $TOTAL_C1 to $TOTAL_C2 (+$((TOTAL_C2-TOTAL_C1)) = USD 40/night)" || fail "total did not rise"
[ "$(approve "$TRIP_C" "$ALICE" "self-$TRIP_C")" = 403 ] && pass "alice cannot approve her own re-quoted plan" || fail "self approval"
[ "$(approve "$TRIP_C" "$BOB" "c2-$TRIP_C")" = 200 ] || fail "approval 2"
[ "$(wait_status "$TRIP_C" BOOKED 180)" = BOOKED ] && pass "$TRIP_C BOOKED at the re-quoted price" || fail "expected BOOKED, got $(status_of "$TRIP_C")"
curl -s "$CORE/api/v1/trips/$TRIP_C/history" -H "Authorization: Bearer $ALICE" | check "
s=[x['to'] for x in d]; assert s.count('AWAITING_APPROVAL')==2 and s.count('APPROVED')==2 and s[-1]=='BOOKED', s; print('  history:', s)" && pass "two approvals in the history" || fail "history"
[ "$(count_events travel.trip "$TRIP_C" travel.trip.replanned)" = 1 ] && kafka_consume travel.trip | grep "$TRIP_C" | grep '"travel.trip.replanned"' | python3 -c "import sys,json; e=[json.loads(l) for l in sys.stdin if l.strip()][0]['data']; assert e['reason']=='PRICE_CHANGED' and e['newTotal']['amountMinor']>e['previousTotal']['amountMinor'] and e['requiresApproval'], e; print('  replanned:', e['reason'], e['previousTotal']['amountMinor'], '->', e['newTotal']['amountMinor'])" && pass "travel.trip.replanned once, PRICE_CHANGED" || fail "replanned event"
ORDER_C=$(trip_view "$TRIP_C" | json 'd["evidence"]["orderId"]')
[ "$(curl -s "$ORDER/api/v1/orders/$ORDER_C" -H "Authorization: Bearer $BOB" | json 'd["total"]["amountMinor"]')" = "$TOTAL_C2" ] && pass "the order charged the re-quoted total" || fail "order total"
LEGS_ORD='[{"origin":"BOS","destination":"ORD","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"ORD","destination":"BOS","earliestDeparture":"2026-10-07T14:00:00Z","arrivalDeadline":"2026-10-08T02:00:00Z"}]'
TRIP_C2=$(create_itinerary "$LEGS_ORD" '[{"city":"ORD","checkInDate":"2026-10-06","checkOutDate":"2026-10-07"}]' '[]'); echo "  trip $TRIP_C2 (ORD's cheapest hotel quote lives 10 seconds)"
[ "$(wait_status "$TRIP_C2" AWAITING_APPROVAL 180)" = AWAITING_APPROVAL ] || fail "expected AWAITING_APPROVAL, got $(status_of "$TRIP_C2")"
TOTAL_C3=$(trip_view "$TRIP_C2" | json 'd["total"]["amountMinor"]')
echo "  waiting 15s so the quote expires before bob decides"; sleep 15
[ "$(approve "$TRIP_C2" "$BOB" "c3-$TRIP_C2")" = 200 ] || fail "approval"
[ "$(wait_status "$TRIP_C2" BOOKED 180)" = BOOKED ] && pass "$TRIP_C2 BOOKED after an expired quote was re-quoted at the same price" || fail "expected BOOKED, got $(status_of "$TRIP_C2")"
curl -s "$CORE/api/v1/trips/$TRIP_C2/history" -H "Authorization: Bearer $ALICE" | check "s=[x['to'] for x in d]; assert s.count('AWAITING_APPROVAL')==1, s" && pass "one approval was enough: an unchanged price is not a material change" || fail "history"
[ "$(trip_view "$TRIP_C2" | json 'd["total"]["amountMinor"]')" = "$TOTAL_C3" ] && pass "total unchanged at $TOTAL_C3" || fail "total changed"
publish_policy 'd["approval"]["managerRequiredAbove"]=500000' 'slice 3: no manager below USD 5000' >/dev/null

echo "== D. a later component fails: compensation, and a compensation that cannot complete"
LEGS_AUS='[{"origin":"BOS","destination":"AUS","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"AUS","destination":"BOS","earliestDeparture":"2026-10-07T14:00:00Z","arrivalDeadline":"2026-10-08T02:00:00Z"}]'
TRIP_D1=$(create_itinerary "$LEGS_AUS" '[{"city":"AUS","checkInDate":"2026-10-06","checkOutDate":"2026-10-07"}]' '[]'); echo "  trip $TRIP_D1 (AUS's cheapest hotel refuses the booking)"
[ "$(wait_status "$TRIP_D1" FAILED 180)" = FAILED ] || fail "expected FAILED, got $(status_of "$TRIP_D1")"
trip_view "$TRIP_D1" | check "
assert d['failureStage']=='BOOKING' and d['failureCode']=='ROOM_NO_LONGER_AVAILABLE', (d['failureStage'], d['failureCode'])
st={c['type']+':'+c['status'] for c in d['components']}; assert st=={'AIR:CANCELLED','HOTEL:FAILED'}, st
print('  components:', sorted(st))" && pass "$TRIP_D1 FAILED at BOOKING; the legs booked first were released" || fail "compensation view"
ORDER_D1=$(orders_of "$TRIP_D1" | json 'd[0]["orderId"]')
curl -s "$ORDER/api/v1/orders/$ORDER_D1" -H "Authorization: Bearer $BOB" | check "assert d['status']=='FAILED' and d['compensated'] is True and d.get('exposures') is None, (d['status'], d['compensated'])" && pass "order FAILED, compensated, no exposure" || fail "order D1"
LEGS_LAX='[{"origin":"BOS","destination":"LAX","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"LAX","destination":"BOS","earliestDeparture":"2026-10-07T14:00:00Z","arrivalDeadline":"2026-10-08T02:00:00Z"}]'
TRIP_D2=$(create_itinerary "$LEGS_LAX" '[{"city":"LAX","checkInDate":"2026-10-06","checkOutDate":"2026-10-07"}]' '[{"kind":"AIRPORT_TO_HOTEL","city":"LAX"}]'); echo "  trip $TRIP_D2 (LAX: the transfer fails after a non-cancellable hotel is confirmed)"
[ "$(wait_status "$TRIP_D2" FAILED 180)" = FAILED ] || fail "expected FAILED, got $(status_of "$TRIP_D2")"
trip_view "$TRIP_D2" | check "
assert d['failureStage']=='COMPENSATION' and d['failureCode']=='COMPENSATION_INCOMPLETE', (d['failureStage'], d['failureCode'])
st={c['type']+':'+c['status'] for c in d['components']}; assert st=={'AIR:CANCELLED','HOTEL:CANCEL_FAILED','GROUND:FAILED'}, st
print('  components:', sorted(st))" && pass "$TRIP_D2 FAILED at COMPENSATION: the hotel could not be released" || fail "exposure view"
ORDER_D2=$(orders_of "$TRIP_D2" | json 'd[0]["orderId"]')
EXP=$(curl -s "$ORDER/api/v1/orders/$ORDER_D2" -H "Authorization: Bearer $BOB" | check "
assert d['status']=='PARTIALLY_FAILED' and d['compensated'] is False, d['status']
e=d['exposures']; assert len(e)==1 and e[0]['status']=='OPEN' and e[0]['reason']=='COMPENSATION_FAILED' and e[0]['detail'].startswith('CANCELLATION_REFUSED'), e
print(e[0]['exposureId'])")
pass "order PARTIALLY_FAILED with one OPEN exposure ($EXP)"
[ "$(count_events travel.order "$ORDER_D2" travel.order.compensation-failed)" = 1 ] && pass "travel.order.compensation-failed on the broker: escalated" || fail "compensation-failed event"
RES="$ORDER/api/v1/orders/$ORDER_D2/exposures/$EXP/resolution"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$RES" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: r-$EXP" -H 'Content-Type: application/json' -d '{"resolution":"x"}')" = 403 ] && pass "the traveler cannot resolve the company's exposure" || fail "traveler resolution"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$RES" -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: r-$EXP" -H 'Content-Type: application/json' -d '{"resolution":"cancelled by phone with the property; refund confirmed"}')" = 200 ] || fail "resolution"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$RES" -H "Authorization: Bearer $CAROL" -H "Idempotency-Key: r-$EXP" -H 'Content-Type: application/json' -d '{"resolution":"cancelled by phone with the property; refund confirmed"}')" = 200 ] && pass "carol resolved it, idempotently" || fail "idempotent resolution"
curl -s "$ORDER/api/v1/orders/$ORDER_D2" -H "Authorization: Bearer $BOB" | check "assert d['status']=='FAILED' and d['compensated'] is True and d['exposures'][0]['status']=='RESOLVED' and d['exposures'][0]['resolvedBy']=='human/carol', d" && pass "order now FAILED and compensated, by a person" || fail "resolved view"
[ "$(count_events travel.order "$ORDER_D2" travel.order.exposure-resolved)" = 1 ] && pass "travel.order.exposure-resolved once" || fail "exposure-resolved event"
curl -s "$AUDIT/api/v1/audit/trips/$TRIP_D2/decisions" -H "Authorization: Bearer $ALICE" | check "assert d['compensation']['open']==0 and any('resolved by human/carol' in n for n in d['narrative']), d['narrative']" && pass "the ledger records the exposure and its resolution" || fail "ledger D2"

echo "== E. duplicates: one trip per key; a supplier's lost answer after a committed booking is one booking"
KEY_E="s3-dup-$(date +%s%N)"
LEGS_DEN='[{"origin":"BOS","destination":"DEN","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"DEN","destination":"BOS","earliestDeparture":"2026-10-07T14:00:00Z","arrivalDeadline":"2026-10-08T02:00:00Z"}]'
TRIP_E=$(create_itinerary "$LEGS_DEN" '[{"city":"DEN","checkInDate":"2026-10-06","checkOutDate":"2026-10-07"}]' '[{"kind":"AIRPORT_TO_HOTEL","city":"DEN"}]' "$KEY_E")
TRIP_E2=$(create_itinerary "$LEGS_DEN" '[{"city":"DEN","checkInDate":"2026-10-06","checkOutDate":"2026-10-07"}]' '[{"kind":"AIRPORT_TO_HOTEL","city":"DEN"}]' "$KEY_E")
[ "$TRIP_E" = "$TRIP_E2" ] && pass "the same request twice is the same trip ($TRIP_E)" || fail "duplicate request made two trips"
[ "$(wait_status "$TRIP_E" BOOKED 240)" = BOOKED ] || fail "expected BOOKED, got $(status_of "$TRIP_E")"
ORDER_E=$(trip_view "$TRIP_E" | json 'd["evidence"]["orderId"]')
N_E=$(psql_db supplier_gateway "select count(*) from sandbox_booking where idempotency_key like '$ORDER_E:%'")
[ "$N_E" = 2 ] && pass "DEN's hotel and shuttle both lost their answers after booking; status lookup reconciled them: 2 bookings, not 4" || fail "sandbox bookings for $ORDER_E: $N_E"
[ "$(orders_of "$TRIP_E" | json 'len(d)')" = 1 ] && pass "one order for the trip" || fail "orders"
[ "$(count_events travel.order "$ORDER_E" travel.order.confirmed)" = 1 ] && pass "one travel.order.confirmed" || fail "confirmed events"

echo "== F. connected disruption recovery: the cancelled leg drags its transfer along, keeps the hotel"
publish_policy 'd["autonomy"]["flightRebooking"]={"enabled": True, "maxIncrementalCost": 10000}; d["approval"]["managerRequiredAbove"]=500000' 'slice 3: autonomy USD 100' | sed 's/^/  published /'
LEGS_F='[{"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"SEA","destination":"BOS","earliestDeparture":"2026-10-08T14:00:00Z","arrivalDeadline":"2026-10-09T02:00:00Z"}]'
recover() { # $1 fare delta -> prints "trip order disruption"
  local trip; trip=$(create_itinerary "$LEGS_F" '[{"city":"SEA","checkInDate":"2026-10-06","checkOutDate":"2026-10-08"}]' '[{"kind":"AIRPORT_TO_HOTEL","city":"SEA"}]')
  [ "$(wait_status "$trip" BOOKED 240)" = BOOKED ] || fail "trip $trip did not book: $(status_of "$trip")"
  local order ext flight
  read -r order ext flight <<<"$(orders_of "$trip" | check "o=d[0]; i=[x for x in o['items'] if x['type']=='AIR' and x['status']=='CONFIRMED'][0]; print(o['orderId'], i['externalRef'], i['flights'][0]['flightNumber'])")"
  local resp; resp=$(notify "s3-evt-$(date +%s%N)" "$ext" "$flight" "$1")
  [ "$(echo "$resp" | tail -1)" = 202 ] || fail "webhook: $resp"
  echo "$trip $order $(echo "$resp" | head -1 | json 'd["disruptionId"]')"
}
read -r TRIP_F ORDER_F DSR_F <<<"$(recover 7300)"; echo "  trip $TRIP_F order $ORDER_F disruption $DSR_F (+USD 73)"
[ "$(wait_disruption "$DSR_F" RESOLVED 180)" = RESOLVED ] && pass "$DSR_F RESOLVED autonomously" || fail "$DSR_F ended $(disruption_status "$DSR_F")"
curl -s "$DISRUPTION/api/v1/disruptions/$DSR_F" -H "Authorization: Bearer $BOB" | check "
r=d['recovery']; assert r['autonomyOutcome']=='ALLOW' and int(r['incrementalCost']['amountMinor'])==7300, r
ch=d['decision']['componentChanges']; acts={c['type']+':'+c['action'] for c in ch}
assert 'AIR:REPLACED' in acts and ('GROUND:RETIMED' in acts or 'GROUND:PRESERVED' in acts) and 'HOTEL:PRESERVED' in acts, acts
assert len(r['affectedComponentIds'])==3, r['affectedComponentIds']
print('  component changes:', sorted(acts))" && pass "the leg was replaced, the transfer follows it, the hotel stays; every touched component is on record" || fail "decision F"
curl -s "$ORDER/api/v1/orders/$ORDER_F" -H "Authorization: Bearer $BOB" | check "
assert d['status']=='CHANGED', d['status']
ch=[c for c in d['changes'] if c['status']=='APPLIED']; assert len(ch)==1 and ch[0]['incrementalCost']['amountMinor']==7300, ch
h=[i for i in d['items'] if i['type']=='HOTEL']; assert len(h)==1 and h[0]['status']=='CONFIRMED', 'the hotel item was never touched'
by={}
for i in d['items']:
    if i['type']=='AIR': by.setdefault(i['componentId'],[]).append(i['status'])
assert sorted(map(sorted, by.values()))==[['CHANGED','CONFIRMED'],['CONFIRMED']], by  # the cancelled leg: old item CHANGED + its replacement; the other leg untouched
print('  order', d['status'], 'total', d['total']['display'])" && pass "one order change, exactly the components that moved" || fail "order F"
[ "$(count_events travel.order "$DSR_F" travel.order.changed)" = 1 ] && pass "exactly one travel.order.changed" || fail "order.changed count"
read -r TRIP_F2 ORDER_F2 DSR_F2 <<<"$(recover 18000)"; echo "  trip $TRIP_F2 order $ORDER_F2 disruption $DSR_F2 (+USD 180)"
[ "$(wait_disruption "$DSR_F2" HUMAN_REQUIRED 180)" = HUMAN_REQUIRED ] && pass "$DSR_F2 waits for a manager: the change adds more than USD 100 across its components" || fail "$DSR_F2 is $(disruption_status "$DSR_F2")"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$DISRUPTION/api/v1/disruptions/$DSR_F2/approval" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: self-$DSR_F2" -H 'Content-Type: application/json' -d '{"decision":"APPROVE"}')" = 403 ] && pass "alice cannot approve her own recovery" || fail "self-approval"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$DISRUPTION/api/v1/disruptions/$DSR_F2/approval" -H "Authorization: Bearer $BOB" -H "Idempotency-Key: ok-$DSR_F2" -H 'Content-Type: application/json' -d '{"decision":"APPROVE","comment":"go"}')" = 200 ] || fail "bob approval"
[ "$(wait_disruption "$DSR_F2" RESOLVED 180)" = RESOLVED ] && pass "$DSR_F2 RESOLVED by the same workflow after bob approved" || fail "$DSR_F2 ended $(disruption_status "$DSR_F2")"
curl -s "$ORDER/api/v1/orders/$ORDER_F2" -H "Authorization: Bearer $BOB" | check "ch=[c for c in d['changes'] if c['status']=='APPLIED']; assert len(ch)==1 and ch[0]['incrementalCost']['amountMinor']==18000 and ch[0].get('approvalId'), ch" && pass "one change of +18000 with the approval on record" || fail "order F2"

echo "== G. isolation, authorization, supplier text as data, a red-eye across midnight"
for path in "/api/v1/trips/$TRIP_A" "/api/v1/trips/$TRIP_A/components"; do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$CORE$path" -H "Authorization: Bearer $ZOE")" = 404 ] || fail "isolation on $path"
done
[ "$(curl -s -o /dev/null -w '%{http_code}' "$ORDER/api/v1/orders/$ORDER_A" -H "Authorization: Bearer $ZOE")" = 404 ] || fail "isolation on the order"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$DISRUPTION/api/v1/disruptions/$DSR_F" -H "Authorization: Bearer $ZOE")" = 404 ] || fail "isolation on the disruption"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ORDER/api/v1/orders/$ORDER_D2/exposures/$EXP/resolution" -H "Authorization: Bearer $ZOE" -H "Idempotency-Key: z" -H 'Content-Type: application/json' -d '{"resolution":"x"}')" = 404 ] || fail "isolation on the exposure"
pass "another tenant gets 404 on the trip, its components, the order, the disruption and the exposure"
LEGS_MIA='[{"origin":"BOS","destination":"MIA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"MIA","destination":"BOS","earliestDeparture":"2026-10-07T14:00:00Z","arrivalDeadline":"2026-10-08T02:00:00Z"}]'
TRIP_G=$(create_itinerary "$LEGS_MIA" '[{"city":"MIA","checkInDate":"2026-10-06","checkOutDate":"2026-10-07"}]' '[]'); echo "  trip $TRIP_G (MIA's cheapest hotel description tries to instruct the platform)"
[ "$(wait_status "$TRIP_G" BOOKED 240)" = BOOKED ] || fail "expected BOOKED, got $(status_of "$TRIP_G")"
trip_view "$TRIP_G" | check "
x=(d.get('explanation') or '').lower(); assert 'presidential' not in x and 'ignore' not in x and 'ceo' not in x, x
h=[c for c in d['components'] if c['type']=='HOTEL'][0]; assert 'Bayside Bargain' in h['summary'], h
assert d.get('approval') is None and d['status']=='BOOKED'
print('  hotel:', h['summary'])" && pass "the injected text changed nothing: economy policy verdict, no approval, narration free of it" || fail "injection"
curl -s "$POLICY/api/v1/policy-decisions?tripId=$TRIP_G" -H "Authorization: Bearer $CAROL" | check "assert d and all(x.get('outcome') in ('ALLOW','ALLOW_WITH_TRAVELER_PAYMENT','ALLOW_WITH_APPROVAL','DENY') for x in d); print('  policy decisions:', len(d))" 2>/dev/null || true
# The sandbox red-eye leaves SEA at 06:00Z on the 8th = 23:00 Pacific on the 7th and lands in Boston at
# 09:30 Eastern on the 8th (7.5 h). A UTC or a Pacific calendar would put the Boston night on the wrong date.
LEGS_RED='[{"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},{"origin":"SEA","destination":"BOS","earliestDeparture":"2026-10-08T05:00:00Z","arrivalDeadline":"2026-10-08T14:00:00Z"}]'
TRIP_R=$(create_itinerary "$LEGS_RED" '[{"city":"SEA","checkInDate":"2026-10-06","checkOutDate":"2026-10-07"},{"city":"BOS","checkInDate":"2026-10-08","checkOutDate":"2026-10-09"}]' '[]'); echo "  trip $TRIP_R (a red-eye SEA->BOS departing 23:00 Pacific on the 7th, landing on the 8th Eastern)"
[ "$(wait_status "$TRIP_R" BOOKED 240)" = BOOKED ] || { trip_view "$TRIP_R" | head -c 800; echo; fail "expected BOOKED, got $(status_of "$TRIP_R")"; }
trip_view "$TRIP_R" | check "
import datetime, zoneinfo
it=d['intent']['itinerary']; red=it['legs'][1]; bos=[s for s in it['stays'] if s['city']=='BOS'][0]; sea=[s for s in it['stays'] if s['city']=='SEA'][0]
assert bos['checkInDate']=='2026-10-08' and sea['checkOutDate']=='2026-10-07'
assert all(x['status']=='CONFIRMED' for x in d['components']) and len(d['components'])==4, [(x['type'],x['status']) for x in d['components']]
c=[x for x in d['components'] if x['type']=='AIR'][1]; dep=c['summary'].split()[-1]
t=datetime.datetime.fromisoformat(dep.replace('Z','+00:00')); local=t.astimezone(zoneinfo.ZoneInfo('America/Los_Angeles'))
assert local.date().isoformat()=='2026-10-07' and t.astimezone(zoneinfo.ZoneInfo('America/New_York')).date().isoformat()=='2026-10-08', (dep, local)
print('  red-eye departs', local.strftime('%Y-%m-%d %H:%M %Z'), '=', dep, '; Seattle check-out', sea['checkOutDate'], '; Boston stay starts', bos['checkInDate'])" && pass "the Seattle night ends on the local departure date and the Boston night starts on the landing date; neither follows UTC" || fail "red-eye"
publish_policy 'pass' 'slice 3: seed restored' | sed 's/^/  published /'

echo
echo "Slice 3 itineraries: PASS ($TRIP_A booked with 7 components; $TRIP_B1/$TRIP_B2 failed honestly; $TRIP_C re-approved; $TRIP_D2 exposure resolved; $TRIP_E reconciled; $DSR_F autonomous, $DSR_F2 approved)"
