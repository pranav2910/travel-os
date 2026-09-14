#!/usr/bin/env bash
# Slice 1 end-to-end against the LIVE local platform and running services (see docs/runbooks/local-dev.md):
#   travel-core :8081/:9081, policy :8082/:9082, optimization :9083, supplier-gateway :8084/:9084,
#   order :8085/:9085, trip-planning worker :8086, plus Postgres/Kafka/Temporal/Keycloak from `make up`.
#
# Two trips:
#   1. with a policy that requires a manager for everything -> AWAITING_APPROVAL -> bob approves -> BOOKED
#   2. with the seed policy -> in policy, zero approvals -> BOOKED
# Every step asserts what the platform says (status codes, statuses, evidence ids, events on the
# real broker). Exit code != 0 means Slice 1 is not working. Nothing is mocked.
set -euo pipefail

# E2E_BACKEND=compose (default) talks to the docker-compose stack; E2E_BACKEND=kind to the kind
# cluster (ports 1xxxx, kubectl for broker/Temporal introspection). `make kind-e2e` sets it up.
BACKEND=${E2E_BACKEND:-compose}
if [ "$BACKEND" = kind ]; then
  : "${KC:=http://localhost:18180}" "${CORE:=http://localhost:18081}" "${POLICY:=http://localhost:18082}"
  : "${SUPPLIER:=http://localhost:18084}" "${ORDER:=http://localhost:18085}" "${WORKER:=http://localhost:18086}"
  : "${AUDIT:=http://localhost:18088}" "${OPT_PORT:=19083}" "${LLM_PORT:=19087}" "${TEMPO:=http://localhost:13200}"
fi
KC=${KC:-http://localhost:8180}
CORE=${CORE:-http://localhost:8081}
POLICY=${POLICY:-http://localhost:8082}
SUPPLIER=${SUPPLIER:-http://localhost:8084}
ORDER=${ORDER:-http://localhost:8085}
WORKER=${WORKER:-http://localhost:8086}
AUDIT=${AUDIT:-http://localhost:8088}
OPT_PORT=${OPT_PORT:-9083}
LLM_PORT=${LLM_PORT:-9087}
TEMPO=${TEMPO:-http://localhost:3200}
COMPOSE="docker compose -f $(dirname "$0")/../platform/local/docker-compose.yml"

kafka_consume() { # $@ topics -> raw records
  if [ "$BACKEND" = kind ]; then
    kubectl exec -n travelos-infra deploy/kafka -- bash -c "for t in $*; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic \$t --from-beginning --timeout-ms 4000 2>/dev/null; done"
  else
    $COMPOSE exec -T kafka bash -c "for t in $*; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic \$t --from-beginning --timeout-ms 4000 2>/dev/null; done"
  fi
}
temporal_describe() { # $1 workflow id
  if [ "$BACKEND" = kind ]; then
    kubectl exec -n travelos-infra deploy/temporal -- temporal workflow describe --address 127.0.0.1:7233 --namespace travelos -w "$1" 2>/dev/null || true
  else
    $COMPOSE exec -T temporal temporal workflow describe --address temporal:7233 --namespace travelos -w "$1" 2>/dev/null || true
  fi
}
SEED="$(dirname "$0")/../platform/local/seed/policies/acme-us-standard.json"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
# statements, not an expression: `check 'assert ...; print(...)'`
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

echo "== 0. health"
for svc in "$CORE" "$POLICY" "$ORDER" "$AUDIT" "$SUPPLIER" "$WORKER"; do
  # a fresh rollout needs a moment before NodePorts route: wait, do not judge on the first probe
  for i in $(seq 1 90); do curl -sf "$svc/actuator/health/readiness" >/dev/null && break; sleep 2; done
  curl -sf "$svc/actuator/health/readiness" >/dev/null || fail "$svc not ready"
done
nc -z localhost "$OPT_PORT" || fail "optimization gRPC :$OPT_PORT not listening"
nc -z localhost "$LLM_PORT" || fail "llm-gateway gRPC :$LLM_PORT not listening"
pass "all services ready"

# tokens come from Keycloak, which is not one of the app services above: wait for its realm too
for i in $(seq 1 90); do curl -sf "$KC/realms/travelos/.well-known/openid-configuration" >/dev/null && break; sleep 2; done
curl -sf "$KC/realms/travelos/.well-known/openid-configuration" >/dev/null || fail "Keycloak at $KC not ready"
ALICE=$(tok alice); BOB=$(tok bob); CAROL=$(tok carol)

publish_policy() { # $1 = python expression mutating d (the seed document), $2 = note
  python3 -c "import json,sys; d=json.load(open('$SEED')); $1; print(json.dumps({'document': d, 'note': '$2'}))" \
    | curl -s -X POST "$POLICY/api/v1/policies" -H "Authorization: Bearer $CAROL" -H 'Content-Type: application/json' -d @- \
    | json '"v%s %s" % (d.get("version"), d.get("policyId") or d.get("code"))'
}

create_trip() { # prints trip id
  curl -s -X POST "$CORE/api/v1/trips" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: e2e-$(date +%s%N)" \
    -H 'Content-Type: application/json' -d '{"request":"Seattle before 5pm Tuesday Oct 6, back Wednesday evening, customer meeting",
      "intent":{"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z",
                "returnAfter":"2026-10-07T20:00:00Z","latestReturn":"2026-10-08T06:00:00Z","purpose":"customer meeting"},"source":"WEB"}' \
    | json 'd["tripId"]'
}

wait_status() { # $1 trip, $2 expected status (or "|"-separated), $3 timeout seconds
  local t=0
  while [ $t -lt "$3" ]; do
    local s; s=$(curl -s "$CORE/api/v1/trips/$1" -H "Authorization: Bearer $ALICE" | json 'd["status"]')
    case "|$2|" in *"|$s|"*) echo "$s"; return 0;; esac
    case "$s" in FAILED|CANCELLED) echo "$s"; return 0;; esac
    sleep 1; t=$((t+1))
  done
  echo "TIMEOUT"; return 0
}

trip_view() { curl -s "$CORE/api/v1/trips/$1" -H "Authorization: Bearer $ALICE"; }

echo "== 1. approval path: publish a policy where every trip needs a manager"
publish_policy 'd["approval"]["managerRequiredAbove"]=1' 'e2e: approval required for everything' | sed 's/^/  published /'
TRIP1=$(create_trip); echo "  trip $TRIP1"
S=$(wait_status "$TRIP1" "AWAITING_APPROVAL" 60); [ "$S" = "AWAITING_APPROVAL" ] || { trip_view "$TRIP1"; fail "expected AWAITING_APPROVAL, got $S"; }
pass "workflow searched, applied policy, optimized and asked for approval"
V=$(trip_view "$TRIP1")
echo "$V" | json 'd["evidence"]["selectedBundleId"]' | grep -q '^bdl_' || fail "no selected bundle in evidence"
echo "$V" | json 'd["evidence"]["optimizationRunId"]' | grep -q '^opt_' || fail "no optimization run in evidence"
echo "$V" | json 'd["evidence"]["policyDecisionId"]' | grep -q '^pd_' || fail "no policy decision in evidence"
[ "$(echo "$V" | json 'd["approval"]["status"]')" = "PENDING" ] || fail "approval not pending"
pass "evidence chain present: $(echo "$V" | json '"bundle=%s opt=%s pd=%s total=%s" % (d["evidence"]["selectedBundleId"], d["evidence"]["optimizationRunId"], d["evidence"]["policyDecisionId"], d["total"]["display"])')"

echo "  alice tries to approve her own trip:"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORE/api/v1/trips/$TRIP1/approval" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: self-$TRIP1" -H 'Content-Type: application/json' -d '{"decision":"APPROVE"}')
[ "$CODE" = "403" ] && pass "403 for the traveler" || fail "expected 403, got $CODE"
echo "  bob (manager) approves:"
R=$(curl -s -X POST "$CORE/api/v1/trips/$TRIP1/approval" -H "Authorization: Bearer $BOB" -H "Idempotency-Key: approve-$TRIP1" -H 'Content-Type: application/json' -d '{"decision":"APPROVE","comment":"Customer meeting approved."}')
[ "$(echo "$R" | json 'd.get("status")')" = "APPROVED" ] && pass "approval recorded by $(echo "$R" | json 'd["decidedBy"]')" || fail "approval failed: $R"
S=$(wait_status "$TRIP1" "BOOKED" 90); [ "$S" = "BOOKED" ] || { trip_view "$TRIP1"; fail "expected BOOKED, got $S"; }
V=$(trip_view "$TRIP1"); ORDER1=$(echo "$V" | json 'd["evidence"]["orderId"]')
pass "BOOKED with order $ORDER1 (approval $(echo "$V" | json 'd["evidence"]["approvalId"]'))"

echo "== 2. zero-approval path: back to the seed policy"
publish_policy 'pass' 'e2e: seed policy restored' | sed 's/^/  published /'
TRIP2=$(create_trip); echo "  trip $TRIP2"
S=$(wait_status "$TRIP2" "BOOKED" 90); [ "$S" = "BOOKED" ] || { trip_view "$TRIP2"; fail "expected BOOKED, got $S"; }
V=$(trip_view "$TRIP2"); ORDER2=$(echo "$V" | json 'd["evidence"]["orderId"]')
[ "$(echo "$V" | json 'd.get("approval")')" = "None" ] && pass "BOOKED with order $ORDER2 and no approval in the loop" || fail "unexpected approval on the in-policy trip"

echo "== 3. the transactional truth"
O=$(curl -s "$ORDER/api/v1/orders/$ORDER2" -H "Authorization: Bearer $ALICE")
[ "$(echo "$O" | json 'd["status"]')" = "CONFIRMED" ] || fail "order not CONFIRMED: $O"
pass "order CONFIRMED at $(echo "$O" | json 'd["supplier"]') ref $(echo "$O" | json 'd["externalOrderId"]') locator $(echo "$O" | json 'd["items"][0]["recordLocator"]') total $(echo "$O" | json 'd["total"]["display"]')"
[ "$(echo "$O" | json 'd["policyDecisionId"]')" = "$(echo "$V" | json 'd["evidence"]["policyDecisionId"]')" ] && pass "order carries the same policy decision id as the trip" || fail "evidence mismatch between trip and order"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$ORDER/api/v1/orders/$ORDER2" -H "Authorization: Bearer $(tok zoe)")
[ "$CODE" = "404" ] && pass "another tenant gets 404 for the order" || fail "cross-tenant order read returned $CODE"

echo "== 4. explainability"
D=$(curl -s "$POLICY/api/v1/policy-decisions?tripId=$TRIP2" -H "Authorization: Bearer $ALICE")
N=$(echo "$D" | json 'len(d)'); [ "$N" -gt 5 ] || fail "expected one policy decision per candidate, got $N"
pass "$N policy decisions recorded for the trip; outcomes: $(echo "$D" | json 'sorted(set(x["outcome"] for x in d))')"
H=$(curl -s "$CORE/api/v1/trips/$TRIP2/history" -H "Authorization: Bearer $ALICE" | json '" -> ".join(h["to"] for h in d)')
[ "$H" = "SUBMITTED -> PLANNING -> APPROVED -> BOOKING -> BOOKED" ] && pass "history: $H" || fail "unexpected history: $H"

echo "== 5. durable coordination + the event trail"
# Capture first: with pipefail, grep -q closing the pipe early would fail the pipeline via SIGPIPE.
WF=$(temporal_describe "$TRIP2")
echo "$WF" | grep -E 'Status|Type' | head -3 | sed 's/^/  /'
echo "$WF" | grep -qi 'completed' && pass "Temporal workflow $TRIP2 COMPLETED" || fail "workflow not completed"
EVENTS=$(kafka_consume travel.trip travel.policy travel.optimization travel.approval travel.order | grep "$TRIP2" | python3 -c 'import sys,json,collections; c=collections.Counter(json.loads(l)["eventType"] for l in sys.stdin if l.strip()); print(dict(sorted(c.items())))')
echo "  events for $TRIP2: $EVENTS"
echo "$EVENTS" | grep -q "travel.trip.created" && echo "$EVENTS" | grep -q "'travel.optimization.completed': 1" && echo "$EVENTS" | grep -q "travel.order.confirmed" && echo "$EVENTS" | grep -q "travel.trip.booked" && pass "created -> policy -> optimization.completed -> order.confirmed -> booked all on the broker" || fail "event trail incomplete (optimization.completed must appear exactly once)"

echo "== 6. free text: the LLM gateway understands the request, the ledger keeps the evidence"
TRIP3=$(curl -s -X POST "$CORE/api/v1/trips" -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: e2e-$(date +%s%N)" \
    -H 'Content-Type: application/json' -d '{"request":"Fly BOS to SEA on 2026-10-06, back 2026-10-08. Hotel needed, purpose: customer meeting","source":"WEB"}' \
    | json 'd["tripId"]')
echo "  created $TRIP3 from free text only"
S3=$(wait_status "$TRIP3" BOOKED 120); [ "$S3" = BOOKED ] && pass "free-text trip BOOKED" || fail "free-text trip ended $S3: $(trip_view "$TRIP3" | json 'print(d.get("failureStage"), d.get("failureCode"))')"
trip_view "$TRIP3" | check 'i=d["intent"]; assert (i["origin"],i["destination"],i["hotelRequired"])==("BOS","SEA",True), i; print("  intent frozen from text: BOS -> SEA, hotel, purpose=%r" % i.get("purpose"))' && pass "intent extracted from free text" || fail "intent not as expected"
trip_view "$TRIP3" | check 'assert d.get("explanation"), "no explanation stored"; print("  explanation:", d["explanation"][:160].replace("\n"," ") + ("..." if len(d["explanation"])>160 else ""))' && pass "explanation stored with the plan" || fail "no explanation"
curl -s "$CORE/api/v1/trips/$TRIP3/history" -H "Authorization: Bearer $ALICE" | check 'assert any("intent extracted" in (h.get("reason") or "") for h in d), [h.get("reason") for h in d]' && pass "history records the extraction with the model name" || fail "history lacks the extraction entry"
curl -s "$CORE/api/v1/trips/$TRIP3/decisions" -H "Authorization: Bearer $ALICE" | check 'assert d and d[0]["decisionType"]=="INTENT_EXTRACTION" and d[0]["call"]["callId"].startswith("llm_"), d; print("  ledger:", d[0]["result"], "by", d[0]["call"]["provider"], d[0]["call"]["model"], "prompt", d[0]["call"]["promptId"], "v%d" % d[0]["call"]["promptVersion"])' && pass "agent-decision ledger has the model-call evidence" || fail "ledger missing"
INTENT_EVENTS=$(kafka_consume travel.intent | grep "$TRIP3" | python3 -c 'import sys,json; print(sorted({json.loads(l)["eventType"] for l in sys.stdin if l.strip()}))')
echo "  travel.intent events for $TRIP3: $INTENT_EVENTS"
echo "$INTENT_EVENTS" | grep -q "travel.intent.detected" && pass "travel.intent.detected on the broker" || fail "no travel.intent.detected event"

echo "== 7. the audit ledger: one place that answers why, across every service"
for i in $(seq 1 30); do n=$(curl -s "$AUDIT/api/v1/audit/trips/$TRIP3" -H "Authorization: Bearer $ALICE" | json 'len(d.get("events",[]))' 2>/dev/null || echo 0); [ "${n:-0}" -ge 8 ] && break; sleep 1; done
curl -s "$AUDIT/api/v1/audit/trips/$TRIP3" -H "Authorization: Bearer $ALICE" | check 't=[e["eventType"] for e in d["events"]]; print("  trail:", t); assert t[0]=="travel.trip.created" and "travel.intent.detected" in t and t.count("travel.optimization.completed")==1 and t.index("travel.optimization.completed")<t.index("travel.order.created") and "travel.order.confirmed" in t and t[-1]=="travel.trip.booked", t' && pass "audit trail for $TRIP3 is complete and ordered" || fail "audit trail incomplete"
curl -s "$AUDIT/api/v1/audit/trips/$TRIP3/decisions" -H "Authorization: Bearer $ALICE" | check 'assert d["status"]=="BOOKED" and d["intent"]["model"] and d["policy"]["outcome"] and d["order"]["status"]=="CONFIRMED", d; [print("   ", line) for line in d["narrative"]]' && pass "decision ledger assembled from the trail" || fail "ledger incomplete"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$AUDIT/api/v1/audit/trips/$TRIP3" -H "Authorization: Bearer $(tok zoe)")" = 404 ] && pass "another tenant cannot see the trail" || fail "cross-tenant audit read"
curl -s "$AUDIT/api/v1/audit/events?type=travel.order.confirmed&limit=5" -H "Authorization: Bearer $CAROL" | check 'assert len(d)>=3, len(d); print("  finance view: last %d confirmed orders, newest %s" % (len(d), d[0]["data"]["orderId"]))' && pass "auditor query works, tenant-scoped" || fail "auditor query"

echo "== 8. one trace: the whole lifecycle of $TRIP3 under a single trace id"
Q=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote("{ span.trip.id = \"%s\" }" % sys.argv[1]))' "$TRIP3")
NOW=$(date +%s); BEST=""
for i in $(seq 1 45); do
  BEST=$(curl -s "$TEMPO/api/search?q=$Q&limit=20&start=$((NOW-3600))&end=$((NOW+60))" | python3 -c '
import sys,json
d=json.load(sys.stdin); traces=d.get("traces",[])
# pick the trace with the most services; print "traceID services spans"
best=None
for t in traces:
    tid=t["traceID"]
    print(tid, len(t.get("serviceStats",{}) or {}), t.get("rootServiceName",""))
' 2>/dev/null | sort -k2 -n -r | head -1)
  [ -n "$BEST" ] && break; sleep 2
done
[ -n "$BEST" ] || fail "no trace tagged with $TRIP3 reached Tempo"
TID=$(echo "$BEST" | awk '{print $1}')
SERVICES=$(curl -s "$TEMPO/api/traces/$TID" | python3 -c '
import sys,json
d=json.load(sys.stdin); names=set(); spans=0
for b in d.get("batches",[]):
    for a in b.get("resource",{}).get("attributes",[]):
        if a["key"]=="service.name": names.add(a["value"]["stringValue"])
    for ss in b.get("scopeSpans",[]): spans+=len(ss.get("spans",[]))
print(spans, ",".join(sorted(names)))')
echo "  trace $TID: $(echo "$SERVICES" | awk '{print $1}') spans across [$(echo "$SERVICES" | awk '{print $2}')]"
N=$(echo "$SERVICES" | awk '{print $2}' | tr ',' '\n' | grep -c .)
[ "$N" -ge 5 ] && pass "one trace spans $N services (HTTP -> outbox -> Kafka -> Temporal -> gRPC -> Python -> audit)" || fail "trace covers only $N services"

echo
echo "Slice 1 happy path: PASS ($TRIP1 via approval, $TRIP2 in policy, $TRIP3 from free text; audit ledger complete; one trace per trip)"
