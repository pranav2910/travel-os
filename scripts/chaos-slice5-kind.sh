#!/usr/bin/env bash
# Slice 5 deterministic chaos on kind: learning under the faults the platform must survive without a
# duplicate outcome, a partially built profile or a plan whose learned inputs change under it.
# Every hold is proven by a Temporal tripwire (an activity retrying, attempt >= 2, live or in the
# persisted history) before the next fault is injected.
#
#   A. the Learning service is gone while a trip is planned: Resolve is retried a bounded number of
#      times, then the trip books on the baseline with fallback LEARNING_UNAVAILABLE in its ledger
#   B. a profile build with the Learning service gone, then the worker killed: the replacement worker
#      resumes at the step it was on; one profile, one built event, no partial artifact
#   C. the learning consumer is killed while the same records are redelivered: exactly one outcome
#      per logical outcome revision afterwards
#   D. the active profile changes while a plan's optimizer call is held: the plan keeps the inputs
#      pinned to its attempt
set -euo pipefail
cd "$(dirname "$0")/.."
export E2E_BACKEND=kind
KC=${KC:-http://localhost:18180}; CORE=${CORE:-http://localhost:18081}; POLICY=${POLICY:-http://localhost:18082}
SUPPLIER=${SUPPLIER:-http://localhost:18084}; ORDER=${ORDER:-http://localhost:18085}; AUDIT=${AUDIT:-http://localhost:18088}
DISRUPTION=${DISRUPTION:-http://localhost:18089}; WORKER=${WORKER:-http://localhost:18086}; LEARNING=${LEARNING:-http://localhost:18091}
SEED="platform/local/seed/policies/acme-us-standard.json"

kafka_consume() { kubectl exec -n travelos-infra deploy/kafka -- bash -c "for t in $*; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic \$t --from-beginning --timeout-ms 4000 2>/dev/null; done"; }
kafka_produce() { kubectl exec -i -n travelos-infra deploy/kafka -- /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "$1" >/dev/null 2>&1; }
psql_db() { kubectl exec -n travelos-infra statefulset/postgres -- psql -U travelos -d "$1" -tAc "$2"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
check() { python3 -c "import sys,json; d=json.load(sys.stdin); $1"; }
tok() { local i out; for i in $(seq 1 45); do out=$(curl -sf -X POST "$KC/realms/travelos/protocol/openid-connect/token" -d client_id=travelos-dev-cli -d grant_type=password -d "username=$1" -d password=password 2>/dev/null | json 'd["access_token"]' 2>/dev/null) && [ -n "$out" ] && { echo "$out"; return 0; }; sleep 2; done; return 1; }

echo "== 0. health"
for svc in "$CORE" "$POLICY" "$ORDER" "$AUDIT" "$SUPPLIER" "$DISRUPTION" "$WORKER" "$LEARNING"; do
  for i in $(seq 1 90); do curl -sf "$svc/actuator/health/readiness" >/dev/null && break; sleep 2; done
  curl -sf "$svc/actuator/health/readiness" >/dev/null || fail "$svc not ready"
done
for i in $(seq 1 90); do curl -sf "$KC/realms/travelos/.well-known/openid-configuration" >/dev/null && break; sleep 2; done
pass "all services ready"
ALICE=$(tok alice); BOB=$(tok bob); CAROL=$(tok carol)
NONCE=$(date +%s); START_EPOCH=$(date +%s)

publish_policy() { python3 -c "import json,sys; d=json.load(open('$SEED')); $1; print(json.dumps({'document': d, 'note': '$2'}))" | curl -s -X POST "$POLICY/api/v1/policies" -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' -d @- | json '"v%s" % d.get("version")'; }
lrn_get() { curl -s "$LEARNING$1" -H "Authorization: Bearer $2"; }
lrn_post_status() { curl -s -w '\n%{http_code}' -X POST "$LEARNING$1" -H "Authorization: Bearer $2" -H "Idempotency-Key: c5-$(date +%s%N)" -H 'Content-Type: application/json' -d "$3"; }
lrn_post() { lrn_post_status "$@" | sed '$d'; }
lrn_put_status() { curl -s -w '\n%{http_code}' -X PUT "$LEARNING$1" -H "Authorization: Bearer $2" -H "Idempotency-Key: c5-$(date +%s%N)" -H 'Content-Type: application/json' -d "$3"; }
set_mode() { local out; out=$(lrn_put_status "/api/v1/learning/config" "$CAROL" "{\"mode\":\"$1\"}"); [ "$(echo "$out" | tail -1)" = 200 ] || fail "set mode $1: $out"; }
config() { lrn_get "/api/v1/learning/config" "$CAROL"; }
profile() { lrn_get "/api/v1/learning/profiles/$1" "$CAROL"; }
profile_status() { profile "$1" | json 'd.get("status","")' 2>/dev/null || echo ""; }
wait_profile() { local t=0 s; while [ $t -lt "$2" ]; do s=$(profile_status "$1"); case "$s" in ELIGIBLE|REJECTED|FAILED) echo "$s"; return 0;; esac; sleep 2; t=$((t+2)); done; echo TIMEOUT; }
request_build() { local out; out=$(lrn_post_status "/api/v1/learning/profiles" "$CAROL" "$1"); [ "$(echo "$out" | tail -1)" = 202 ] || fail "build request: $out"; echo "$out" | sed '$d' | json 'd["profileId"]'; }
outcome_count() { lrn_get "/api/v1/learning/outcomes?tripId=$1" "$CAROL" | json 'len(d)'; }
kinds() { lrn_get "/api/v1/learning/outcomes?tripId=$1" "$CAROL" | json '",".join(sorted(set(o["kind"] for o in d)))'; }
wait_kind() { local t=0; while [ $t -lt "$3" ]; do case ",$(kinds "$1")," in *",$2,"*) return 0;; esac; sleep 1; t=$((t+1)); done; return 1; }
ledger() { curl -s "$AUDIT/api/v1/audit/trips/$1/decisions" -H "Authorization: Bearer $ALICE"; }
disruption_status() { curl -s "$DISRUPTION/api/v1/disruptions/$1" -H "Authorization: Bearer $BOB" | json 'd["status"]'; }
wait_disruption() { local t=0; while [ $t -lt "$3" ]; do local s; s=$(disruption_status "$1"); case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac; case "$s" in NO_ALTERNATIVE|FAILED|MANUAL_INTERVENTION_REQUIRED) echo "$s"; return 0;; esac; sleep 1; t=$((t+1)); done; echo TIMEOUT; }
wait_ledger_learning() { local t=0; while [ $t -lt "$2" ]; do ledger "$1" | python3 -c 'import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get("learning") else 1)' 2>/dev/null && return 0; sleep 1; t=$((t+1)); done; return 1; }
# Every run books on its own dates: the sandbox airline keeps a cancelled flight cancelled, so a rerun on the
# same cluster would otherwise find the previous run's carrier gone and pick another (no seasons: any date works).
DAY_BASE=$(( (NONCE % 300) + 7 ))
day() { python3 -c "import datetime; print((datetime.date(2026,11,2)+datetime.timedelta(days=$DAY_BASE+$1)).isoformat())"; }
create_trip() { local d1 d2; d1=$(day "$1"); d2=$(day "$(( $1 + 1 ))")
  curl -s -X POST "$CORE/api/v1/trips" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: c5-$NONCE-$1-$(date +%s%N)" -H 'Content-Type: application/json' \
    -d "{\"intent\":{\"origin\":\"BOS\",\"destination\":\"SEA\",\"earliestDeparture\":\"${d1}T05:00:00Z\",\"arrivalDeadline\":\"${d1}T23:59:00Z\",\"returnAfter\":\"${d2}T10:00:00Z\",\"latestReturn\":\"$(day "$(( $1 + 2 ))")T06:00:00Z\",\"purpose\":\"$2\"},\"source\":\"API\"}" | json 'd.get("tripId") or d'; }
status_of() { curl -s "$CORE/api/v1/trips/$1" -H "Authorization: Bearer $ALICE" | json 'd["status"]'; }
wait_status() { local t=0; while [ $t -lt "$3" ]; do local s; s=$(status_of "$1"); case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac; case "$s" in FAILED|CANCELLED) echo "$s"; return 0;; esac; sleep 1; t=$((t+1)); done; echo TIMEOUT; }
order_of() { curl -s "$ORDER/api/v1/orders?tripId=$1" -H "Authorization: Bearer $BOB" | check "o=[x for x in d if x['status'] in ('CONFIRMED','CHANGED')][0]; item=[i for i in o['items'] if i['status']=='CONFIRMED'][0]; f=item['flights'][0]; print(o['orderId'], o['externalOrderId'], f['flightNumber'], f.get('carrier') or f['flightNumber'][:2], item['itemId'], o['total']['amountMinor'])"; }
count_events() { kafka_consume "$1" | grep "$2" | python3 -c "import sys,json; print(sum(1 for l in sys.stdin if l.strip() and json.loads(l)['eventType']=='$3'))"; }

# ---- chaos helpers (the Slice 1-4 discipline: every hold is proven by a Temporal tripwire before the next fault)
tmp_describe() { kubectl exec -n travelos-infra deploy/temporal -- temporal workflow describe --address 127.0.0.1:7233 --namespace travelos -w "$1" -o json 2>/dev/null; }
tmp_show() { kubectl exec -n travelos-infra deploy/temporal -- temporal workflow show --address 127.0.0.1:7233 --namespace travelos -w "$1" -o json 2>/dev/null; }
pending_attempt() { tmp_describe "$1" | python3 -c 'import sys,json; d=json.load(sys.stdin); pa=[p for p in d.get("pendingActivities",[]) if p.get("activityType",{}).get("name")==sys.argv[1]]; print(pa[0].get("attempt",0) if pa else 0)' "$2" 2>/dev/null || echo 0; }
max_attempt_in_history() { tmp_show "$1" | python3 -c 'import sys,json; ev=json.load(sys.stdin).get("events",[]); a=[e["activityTaskStartedEventAttributes"]["attempt"] for e in ev if e["eventType"]=="EVENT_TYPE_ACTIVITY_TASK_STARTED" and e["activityTaskStartedEventAttributes"].get("attempt",1)>1 and (len(sys.argv)<2 or sys.argv[1] in json.dumps(e))]; print(max(a, default=1))' "${2:-}"; }
activity_attempts() { # $1 workflow id, $2 activity type -> highest attempt of that activity in the history (scheduled+started)
  tmp_show "$1" | python3 -c '
import sys,json; ev=json.load(sys.stdin).get("events",[]); sched={}; best=0
for e in ev:
    if e["eventType"]=="EVENT_TYPE_ACTIVITY_TASK_SCHEDULED": sched[e["eventId"]]=e["activityTaskScheduledEventAttributes"]["activityType"]["name"]
    if e["eventType"]=="EVENT_TYPE_ACTIVITY_TASK_STARTED":
        a=e["activityTaskStartedEventAttributes"]
        if sched.get(a.get("scheduledEventId"))==sys.argv[1]: best=max(best, a.get("attempt",1))
print(best)' "$2"; }
scale_away() { kubectl -n travelos scale deploy/"$1" --replicas=0 >/dev/null; kubectl -n travelos wait --for=delete pod -l app="$1" --timeout=120s >/dev/null 2>&1 || true; echo "  $1 scaled to 0 (pods gone)"; }
scale_back() { kubectl -n travelos scale deploy/"$1" --replicas=1 >/dev/null; kubectl -n travelos rollout status deploy/"$1" --timeout=240s >/dev/null; echo "  $1 back"; }
tripwire() { local att=0 i; for i in $(seq 1 120); do att=$(pending_attempt "$1" "$2"); [ "${att:-0}" -ge 2 ] && break; sleep 1; done
  [ "${att:-0}" -ge 2 ] && pass "tripwire: $2 is in flight and retrying (attempt $att)" || fail "$2 not retrying (attempt ${att:-0}): the injection did not take effect"; }
kill_pods() { for app in "$@"; do kubectl -n travelos delete pod -l app="$app" --wait=false >/dev/null; echo "  killed $app pod(s)"; done; }
wait_ready() { for app in "$@"; do kubectl -n travelos rollout status deploy/"$app" --timeout=240s >/dev/null; done; for i in $(seq 1 90); do curl -sf "$LEARNING/actuator/health/readiness" >/dev/null 2>&1 && curl -sf "$WORKER/actuator/health/readiness" >/dev/null 2>&1 && return 0; sleep 2; done; fail "services not reachable after restart"; }
restore() { kubectl -n travelos scale deploy/learning --replicas=1 >/dev/null 2>&1 || true; kubectl -n travelos scale deploy/optimization --replicas=1 >/dev/null 2>&1 || true; }
trap restore EXIT
publish_policy 'pass' 'chaos 5: seed policy' | sed 's/^/  published /'
set_mode SHADOW
lrn_post /api/v1/learning/rollback "$CAROL" '{"toBaseline":true}' >/dev/null

echo "== A. the Learning service is gone while a trip is planned: bounded retries, then the baseline"
scale_away learning
T_A=$(create_trip 0 "chaos 5 A"); echo "  trip $T_A (workflow id = trip id)"
S=$(wait_status "$T_A" BOOKED 240); [ "$S" = BOOKED ] && pass "$T_A BOOKED without the Learning service" || fail "$T_A ended $S"
ATT=$(activity_attempts "$T_A" Resolve); [ "${ATT:-0}" -ge 2 ] && pass "tripwire: Temporal history shows Resolve attempted $ATT times (bounded: 3) before the planner fell back" || fail "Resolve was not retried (attempts ${ATT:-0}): the injection did not take effect"
scale_back learning
for i in $(seq 1 60); do curl -sf "$LEARNING/actuator/health/readiness" >/dev/null 2>&1 && break; sleep 2; done
wait_ledger_learning "$T_A" 90 || fail "no learning section for $T_A: $(ledger "$T_A")"
ledger "$T_A" | check "l=d['learning']; assert l['mode']=='OFF' and l['applied'] is False and l.get('fallbackReason')=='LEARNING_UNAVAILABLE', l; print('  ledger:', l['mode'], l['fallbackReason']); print('  narrative:', [n for n in d['narrative'] if 'Learning' in n][0])" && pass "the ledger names the fallback: LEARNING_UNAVAILABLE, baseline ranking, no profile" || fail "ledger"
wait_kind "$T_A" BOOKING_CONFIRMED 90 && pass "the Learning service, back, caught up on the booking's outcome from the topic" || fail "outcome not ingested after the outage: $(kinds "$T_A")"

echo "== B. a profile build with the Learning service gone, then the worker killed: one profile"
PB=$(python3 -c "import uuid,time; print('lp_'+''.join('0123456789ABCDEFGHJKMNPQRSTVWXYZ'[int(c,16)%32] for c in uuid.uuid4().hex[:26]))")
EVT=$(python3 - "$PB" <<'PY'
import sys,json,uuid,time
pid=sys.argv[1]; now=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
eid="evt_"+"".join("0123456789ABCDEFGHJKMNPQRSTVWXYZ"[int(c,16)%32] for c in uuid.uuid4().hex[:26])
print(json.dumps({"eventId":eid,"eventType":"travel.learning.build-requested","eventVersion":1,"occurredAt":now,"tenantId":"acme","correlationId":pid,"producer":"learning","data":{"profileId":pid,"requestedBy":"human/carol","inputCutoff":now}}))
PY
)
scale_away learning
printf '%s\n' "$EVT" | kafka_produce travel.learning
echo "  build-requested for $PB published with nobody to build against"
tripwire "$PB" BeginBuild
printf '%s\n' "$EVT" | kafka_produce travel.learning
sleep 3
[ "$(tmp_describe "$PB" | json 'd["workflowExecutionInfo"]["status"]')" = WORKFLOW_EXECUTION_STATUS_RUNNING ] && pass "the same request redelivered: still one workflow ($PB), still running" || fail "workflow state"
kill_pods trip-planning
scale_back learning
wait_ready trip-planning learning
pass "worker and service are back"
[ "$(wait_profile "$PB" 240)" = ELIGIBLE ] || [ "$(profile_status "$PB")" = REJECTED ] && pass "$PB finished by the replacement worker: $(profile_status "$PB") ($(profile "$PB" | json 'd.get("verdict")'))" || fail "$PB: $(profile_status "$PB")"
[ "$(psql_db learning "select count(*) from profile where profile_id='$PB'")" = 1 ] && pass "one profile row" || fail "profile rows"
[ "$(count_events travel.learning "$PB" travel.learning.profile-built)" = 1 ] && pass "one profile-built event" || fail "built events: $(count_events travel.learning "$PB" travel.learning.profile-built)"
FINAL=$(activity_attempts "$PB" BeginBuild); [ "$FINAL" -ge 2 ] && pass "tripwire: history shows BeginBuild succeeding on attempt $FINAL (continued from persisted history)" || fail "no retried activity in history"
profile "$PB" | check "assert d['datasetFingerprint'] and d['builtAt'] and d['evaluatedAt'], 'partial artifact'" && pass "built and evaluated, no partial artifact" || fail "partial profile"

echo "== C. the learning consumer is killed while the same records are redelivered"
BEFORE=$(outcome_count "$T_A")
kafka_consume travel.order | grep "$T_A" | python3 -c "import sys,json; [print(l.strip()) for l in sys.stdin if l.strip() and json.loads(l)['eventType']=='travel.order.confirmed']" | head -1 > /tmp/c5-dup.json
[ -s /tmp/c5-dup.json ] || fail "no order.confirmed for $T_A on the broker"
for i in 1 2 3; do kafka_produce travel.order < /tmp/c5-dup.json; done
kill_pods learning
for i in 1 2 3; do kafka_produce travel.order < /tmp/c5-dup.json; done
wait_ready learning
sleep 5
[ "$(outcome_count "$T_A")" = "$BEFORE" ] && pass "six redeliveries across a consumer restart: outcome count unchanged ($BEFORE)" || fail "count changed: $BEFORE -> $(outcome_count "$T_A")"
[ "$(psql_db learning "select count(*) from outcome where trip_id='$T_A' and kind='BOOKING_CONFIRMED'")" = 1 ] && pass "one BOOKING_CONFIRMED row for $T_A in the ledger" || fail "duplicate rows"

echo "== D. the active profile changes while a recovery's optimizer call is held: the attempt keeps its pinned inputs"
[ "$(profile_status "$PB")" = ELIGIBLE ] || { PB=$(request_build "{\"window\":\"PT$(( $(date +%s) - START_EPOCH + 600 ))S\"}"); [ "$(wait_profile "$PB" 180)" = ELIGIBLE ] || fail "no eligible profile to pin ($PB $(profile_status "$PB"))"; }
lrn_post "/api/v1/learning/profiles/$PB/activation" "$CAROL" '{}' | check "assert d['activeProfileId']=='$PB', d"
T_D=$(create_trip 3 "chaos 5 D"); S=$(wait_status "$T_D" BOOKED 240); [ "$S" = BOOKED ] || fail "$T_D ended $S"
read -r O_D E_D F_D C_D IT_D TOT_D <<<"$(order_of "$T_D")"; echo "  trip $T_D booked: $C_D $F_D order $O_D"
scale_away optimization
BODY=$(printf '{"eventId":"%s","type":"FLIGHT_CANCELLED","externalOrderId":"%s","flightNumber":"%s","date":"%s","reason":"crew availability","reaccommodation":{"fareDeltaMinor":7300}}' "sbx-evt-c5-$NONCE" "$E_D" "$F_D" "$(day 3)")
WEBHOOK_SECRET=${WEBHOOK_SECRET:-$(grep '^SANDBOX_AIR_WEBHOOK_SECRET=' deploy/kind/.secrets.env 2>/dev/null | cut -d= -f2)}; WEBHOOK_SECRET=${WEBHOOK_SECRET:-sandbox-air-dev-webhook-secret}
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/^.* //')
RESP=$(curl -s -w '\n%{http_code}' -X POST "$SUPPLIER/api/v1/suppliers/sandbox-air/events" -H 'Content-Type: application/json' -H "X-Supplier-Signature: sha256=$SIG" -d "$BODY"); [ "$(echo "$RESP" | tail -1)" = 202 ] || fail "webhook: $RESP"
DSR=$(echo "$RESP" | head -1 | json 'd["disruptionId"]'); echo "  disruption $DSR (recovery workflow id = disruption id)"
tripwire "$DSR" Optimize
RES=$(activity_attempts "$DSR" Resolve); [ "$RES" -ge 1 ] && pass "Resolve completed once before the optimizer call (pinned in the history)" || fail "Resolve not in history"
lrn_post /api/v1/learning/rollback "$CAROL" '{}' | check "assert d['activeProfileId']!='$PB', d" && pass "rolled the active profile back while $DSR's optimizer call is held (attempt $(pending_attempt "$DSR" Optimize))" || fail "rollback"
scale_back optimization
S=$(wait_disruption "$DSR" RESOLVED 240); [ "$S" = RESOLVED ] && pass "$DSR RESOLVED after the optimizer returned (+USD 73 within the autonomy limit)" || fail "$DSR ended $S"
curl -s "$DISRUPTION/api/v1/trips/$T_D/disruptions" -H "Authorization: Bearer $ALICE" | check "x=[y for y in d if y['disruptionId']=='$DSR'][0]; dec=x['decision']; assert dec.get('learningProfileId')=='$PB', 'the recovery used ' + str(dec.get('learningProfileId')) + ' instead of the pinned $PB'; assert dec.get('learningMode')=='SHADOW', dec; print('  decision record profile:', dec['learningProfileId'], '(pinned before the rollback)')" && pass "the recovery kept the inputs pinned to its attempt; the rollback did not reach into a running attempt" || fail "pinning"
[ "$(activity_attempts "$DSR" Resolve)" = 1 ] && pass "Resolve ran exactly once for $DSR (never re-queried on retry or replay)" || fail "Resolve attempts: $(activity_attempts "$DSR" Resolve)"
FINAL=$(activity_attempts "$DSR" Optimize); [ "$FINAL" -ge 2 ] && pass "tripwire: history shows Optimize succeeding on attempt $FINAL" || fail "no retried Optimize in history"
lrn_post /api/v1/learning/rollback "$CAROL" '{"toBaseline":true}' >/dev/null
echo
echo "Chaos slice 5 on kind: PASS"
