"""Prompt 12: one outage at a time on the isolated stack, during a relevant operation; then a full
restart preserving volumes; then a trace of a booking through the ledger and the trace store."""

import json
import subprocess
import time
import urllib.request

import qa

T = qa.TAG


def docker(*a):
    return subprocess.run(["docker", *a], capture_output=True, text=True, timeout=180).stdout.strip()


def health(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return r.status
    except Exception as e:  # noqa: BLE001
        return str(e)[:40]


def submit(user, body):
    r = qa.api(user, "POST", "/api/v1/trips", body)
    return (r.json or {}).get("tripId", ""), r


results = []


def outage(name, container, during, expect, ok, down_s=25):
    """Stop `container`, run `during()` (returns a dict of observations), start it, wait for health, then
    observe recovery with `expect`/`ok`."""
    docker("stop", container)
    t0 = time.time()
    try:
        obs = during()
    except Exception as e:  # noqa: BLE001
        obs = {"exception": str(e)[:120]}
    time.sleep(max(0, down_s - (time.time() - t0)))
    docker("start", container)
    for _ in range(90):
        st = docker("inspect", "--format", "{{.State.Health.Status}}", container)
        if st in ("healthy", ""):
            break
        time.sleep(2)
    obs["recovered_health"] = docker("inspect", "--format", "{{.State.Health.Status}}", container) or "no healthcheck"
    obs["after"] = expect()
    results.append({"name": name, "container": container, **obs})
    qa.record(test_id=f"P12-OUT-{len(results):02d}", source_requirement=f"Prompt 12 outage: {name}; checklist 32", priority="P0", test_layer="service fault on the isolated stack", account="alice/bob", input=f"docker stop {container} for ~{down_s} s during {name}",
              steps="stop; operate; start; wait healthy; observe", expected="honest user-visible failure or wait during the outage (no false success), health reflects it, and full recovery without manual data repair",
              actual=json.dumps(obs)[:700], status="PASS" if ok(obs) else "FAIL", evidence=qa.evidence(f"p12-{container}.json", obs))


# 1. frontend (web edge) down: the API is unreachable through the edge; direct service health is fine
outage("frontend edge down", "travelos-web-1",
       lambda: {"edge": health(f"{qa.BASE}/healthz"), "core_direct": health(f"{qa.CORE}/actuator/health/readiness")},
       lambda: {"edge": health(f"{qa.BASE}/healthz"), "list": qa.api("alice", "GET", "/api/v1/trips?limit=1").status},
       lambda o: o["after"]["edge"] == 200 and o["after"]["list"] == 200, down_s=10)

# 2. Travel Core (API) down while the user lists and creates
def during_core():
    lst = qa.api("alice", "GET", "/api/v1/trips?limit=1")
    cr = qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} during core outage", 30, 32))
    return {"list_status": lst.status, "list_body": (lst.text or "")[:80], "create_status": cr.status}
outage("Travel Core down", "travelos-travel-core-1", during_core,
       lambda: {"list": qa.api("alice", "GET", "/api/v1/trips?limit=1").status, "create": qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} after core outage", 30, 32)).status},
       lambda o: o["list_status"] in (502, 503, 504) and o["create_status"] in (502, 503, 504) and o["after"]["list"] == 200 and o["after"]["create"] == 202, down_s=20)

# 3. Keycloak down: an existing token keeps working (stateless JWT); a new login fails
def during_kc():
    existing = qa.api("alice", "GET", "/api/v1/trips?limit=1").status  # cached token
    try:
        qa.token("dan", fresh=True); new_login = "ok"
    except Exception as e:  # noqa: BLE001
        new_login = str(e)[:60]
    return {"existing_token_api": existing, "new_login": new_login}
outage("Keycloak down", "travelos-keycloak-1", during_kc,
       lambda: {"new_login": (qa.token("dan", fresh=True) and "ok"), "api": qa.api("dan", "GET", "/api/v1/trips?limit=1").status},
       lambda o: o["existing_token_api"] == 200 and o["new_login"] != "ok" and o["after"]["new_login"] == "ok", down_s=20)

# 4. Temporal server down while a trip is submitted: accepted, parked, completes after recovery
def during_temporal():
    tid, r = submit("alice", qa.round_trip(f"{T} during temporal outage", 34, 36))
    time.sleep(8)
    return {"create": r.status, "trip": tid, "status_during": (qa.trip("alice", tid) or {}).get("status")}
outage("Temporal server down", "travelos-temporal-1", during_temporal,
       lambda: {"final": qa.wait_trip("alice", results[-1]["trip"] if results and results[-1].get("trip") else "x", {"BOOKED", "FAILED"}, 240) if False else None},
       lambda o: o["create"] == 202 and o["status_during"] in ("SUBMITTED", "PLANNING"), down_s=25)
# recovery check for 4 (the closure above cannot see its own trip; do it explicitly)
last = results[-1]
final = qa.wait_trip("alice", last.get("trip", "x"), {"BOOKED", "FAILED"}, 300)
qa.record(test_id="P12-OUT-04b", source_requirement="Prompt 12 Temporal outage: the parked trip completes after recovery", priority="P0", test_layer="service fault", account="alice", input=last.get("trip"),
          steps="poll after the server is back", expected="BOOKED (the outbox relay and the worker resume; no duplicate)", actual=f"final={final}; orders={len(qa.api('alice','GET','/api/v1/orders?tripId='+last.get('trip','x')).json or [])}", status="PASS" if final == "BOOKED" else "FAIL", evidence=qa.evidence("p12-temporal-recovery.json", {"final": final}), ids=last.get("trip"))

# 5. Postgres down briefly: requests fail honestly; everything recovers with no data repair
def during_pg():
    return {"list": qa.api("alice", "GET", "/api/v1/trips?limit=1").status, "edge": health(f"{qa.BASE}/healthz")}
outage("Postgres down", "travelos-postgres-1", during_pg,
       lambda: {"list": qa.api("alice", "GET", "/api/v1/trips?limit=1").status, "create_and_book": qa.wait_trip("alice", submit("alice", qa.round_trip(f"{T} after postgres outage", 38, 40))[0], {"BOOKED", "FAILED"}, 240)},
       lambda o: o["list"] in (500, 502, 503, 504) and o["after"]["list"] == 200 and o["after"]["create_and_book"] == "BOOKED", down_s=15)
# services that lost their pool may need a moment; give them one before the next fault
time.sleep(20)

# 6. optimizer down mid-planning: the workflow waits (activity retries), then completes
def during_opt():
    tid, r = submit("alice", qa.round_trip(f"{T} during optimizer outage", 42, 44))
    time.sleep(10)
    return {"trip": tid, "status_during": (qa.trip("alice", tid) or {}).get("status")}
outage("optimizer down", "travelos-optimization-1", during_opt, lambda: {}, lambda o: o["status_during"] in ("PLANNING", "SUBMITTED"), down_s=20)
last = results[-1]
final = qa.wait_trip("alice", last.get("trip", "x"), {"BOOKED", "FAILED"}, 300)
qa.record(test_id="P12-OUT-06b", source_requirement="Prompt 12 optimizer outage: the trip completes after recovery", priority="P0", test_layer="service fault", account="alice", input=last.get("trip"), steps="poll", expected="BOOKED", actual=f"final={final}", status="PASS" if final == "BOOKED" else "FAIL", evidence="p12-travelos-optimization-1.json", ids=last.get("trip"))

# 7. full stack restart preserving volumes: the run's trips are still there afterwards
before = qa.api("alice", "GET", "/api/v1/trips?limit=200").json or []
mine_before = [t["tripId"] for t in before if (t.get("intent") or {}).get("purpose", "").startswith(T)]
subprocess.run(["make", "-C", "/Users/pranavsaipalla/travel-os", "stack-down"], capture_output=True, text=True, timeout=300)
up = subprocess.run(["make", "-C", "/Users/pranavsaipalla/travel-os", "stack-up"], capture_output=True, text=True, timeout=600)
for _ in range(60):
    if health(f"{qa.BASE}/healthz") == 200 and health(f"{qa.CORE}/actuator/health/readiness") == 200:
        break
    time.sleep(3)
qa._tokens.clear()
after = qa.api("alice", "GET", "/api/v1/trips?limit=200").json or []
mine_after = [t["tripId"] for t in after if (t.get("intent") or {}).get("purpose", "").startswith(T)]
topics = docker("exec", "travelos-kafka-1", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092", "--list").count("travel.")
qa.record(test_id="P12-RESTART-01", source_requirement="Prompt 12 full restart with named volumes preserved; repeat migrations; data after recovery; checklist 33", priority="P0", test_layer="stack restart", account="alice", input=f"{len(mine_before)} run-tagged trips before",
          steps="make stack-down; make stack-up; wait healthy; list", expected="all services healthy, Flyway migrations re-run idempotently, topics present, every trip still listed", actual=f"stack-up rc={up.returncode}; trips before={len(mine_before)} after={len(mine_after)} missing={len(set(mine_before)-set(mine_after))}; travel.* topics={topics}",
          status="PASS" if up.returncode == 0 and set(mine_before) <= set(mine_after) and topics >= 10 else "FAIL", evidence=qa.evidence("p12-restart.json", {"before": len(mine_before), "after": len(mine_after), "missing": sorted(set(mine_before) - set(mine_after)), "stack_up_tail": up.stdout[-400:]}))
qa.record(test_id="P12-FRESH-01", source_requirement="Prompt 12 clean startup in a disposable stack / migrations on a fresh database", priority="P1", test_layer="cross-reference", account="—", input="—",
          steps="—", expected="fresh-database migrations", actual="not run here: a disposable second stack does not fit the 8 GB VM beside the running one and `make stack-nuke` would erase the user's data (forbidden). Fresh-database migrations are exercised by every service's Testcontainers integration tests in `./gradlew check` (green at cda5ccb, CI run 35810676097).", status="BLOCKED", evidence="CI run 35810676097")

# 8. observability: a booked trip's trace through the ledger and the trace store
tid, r = submit("alice", qa.round_trip(f"{T} observability trace", 46, 48))
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 240)
time.sleep(8)
ledger = qa.api("alice", "GET", f"/api/v1/audit/trips/{tid}").json or {}
events = ledger if isinstance(ledger, list) else (ledger.get("events") or ledger.get("trail") or [])
types = [e.get("eventType") for e in events] if isinstance(events, list) else []
corr = {e.get("correlationId") for e in events} if isinstance(events, list) else set()
try:
    with urllib.request.urlopen(f"http://localhost:3200/api/search?tags=trip.id%3D{tid}&limit=5", timeout=10) as rr:
        traces = json.load(rr).get("traces", [])
except Exception as e:  # noqa: BLE001
    traces = f"tempo query failed: {str(e)[:60]}"
metrics = health("http://localhost:8081/actuator/prometheus")
qa.record(test_id="P12-OBS-01", source_requirement="Prompt 12 trace a successful booking end to end; correlation ids; metrics; checklist 34", priority="P1", test_layer="audit ledger + Tempo + actuator", account="alice", input=tid,
          steps="GET /audit/trips/{id}; Tempo search by trip id; /actuator/prometheus", expected="an ordered trail with one correlation id across services (created -> planned -> policy -> optimization -> order -> booked); a trace per trip in Tempo; metrics exposed",
          actual=f"{st}; trail events={len(types)} types={types[:8]}; correlation ids={len(corr)}; tempo traces={traces if isinstance(traces, str) else len(traces)}; prometheus={metrics}", status="PASS" if st == "BOOKED" and len(types) >= 5 and len(corr) == 1 else "FAIL", evidence=qa.evidence("p12-observability.json", {"types": types, "correlation": list(corr), "tempo": traces if isinstance(traces, str) else traces[:2]}), ids=tid)
print(qa.summary())
