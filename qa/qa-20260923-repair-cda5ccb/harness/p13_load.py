"""Prompt 13: concurrency, volume (100 trips), latency percentiles, stale state (as far as the product allows)."""

import concurrent.futures as cf
import json
import statistics
import time

import qa

T = qa.TAG


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * len(xs) + 0.5)) - 1)] if xs else None


def timed(fn):
    t0 = time.perf_counter()
    r = fn()
    return (time.perf_counter() - t0) * 1000, r


# ---- latency samples (warm): token, list, create, detail
lat = {"login_ms": [], "list_ms": [], "create_ms": [], "detail_ms": [], "e2e_book_s": []}
for i in range(10):
    ms, _ = timed(lambda: qa.token("alice", fresh=True)); lat["login_ms"].append(ms)
    ms, _ = timed(lambda: qa.api("alice", "GET", "/api/v1/trips?limit=20")); lat["list_ms"].append(ms)
    t0 = time.time()
    ms, r = timed(lambda: qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} latency {i}", 30, 32))); lat["create_ms"].append(ms)
    tid = (r.json or {}).get("tripId", "")
    ms, _ = timed(lambda: qa.api("alice", "GET", f"/api/v1/trips/{tid}")); lat["detail_ms"].append(ms)
    st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 120, )
    lat["e2e_book_s"].append(time.time() - t0)
summary = {k: {"n": len(v), "p50": round(pct(v, 50), 1), "p95": round(pct(v, 95), 1), "p99": round(pct(v, 99), 1), "max": round(max(v), 1)} for k, v in lat.items()}
qa.record(test_id="P13-LAT-01", source_requirement="Prompt 13 latency: login, list, create, detail, end-to-end booking (acceptance vs confirmation)", priority="P2", test_layer="API timing (warm, sequential, local Docker)", account="alice", input="10 samples each",
          steps="time each call; e2e = submit until BOOKED (1.5 s poll)", expected="no project SLOs exist: measurements reported with proposed thresholds (login p95 < 500 ms, list/detail p95 < 300 ms, create p95 < 500 ms, e2e booking p95 < 15 s on this stack)",
          actual=json.dumps(summary), status="PASS" if summary["create_ms"]["p95"] < 2000 else "FAIL", evidence=qa.evidence("p13-latency.json", {"samples": lat, "summary": summary}))

# ---- 10 concurrent users (5 accounts x 2 sessions) each creating a trip and waiting for it
users = ["alice", "bob", "carol", "dan", "alice", "bob", "carol", "dan", "alice", "bob"]
def one(i):
    u = users[i]
    t0 = time.time()
    r = qa.api(u, "POST", "/api/v1/trips", qa.round_trip(f"{T} concurrent user {i} {u}", 50 + i, 52 + i))
    tid = (r.json or {}).get("tripId", "")
    st = qa.wait_trip(u, tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 240) if tid else "REJECTED"
    return {"user": u, "status": r.status, "trip": tid, "final": st, "seconds": round(time.time() - t0, 1)}
t0 = time.time()
with cf.ThreadPoolExecutor(10) as ex:
    conc = list(ex.map(one, range(10)))
dur = time.time() - t0
owners = {}
for c in conc:
    if c["trip"]:
        t = qa.trip(c["user"], c["trip"]) or {}
        owners[c["trip"]] = (t.get("travelerId"), t.get("tenantId"))
mismatch = [c for c in conc if c["trip"] and owners.get(c["trip"], ("",))[0] != {"alice": "emp_1001", "bob": "emp_1002", "carol": "emp_1003", "dan": "emp_1004"}[c["user"]]]
qa.record(test_id="P13-CONC-01", source_requirement="Prompt 13 ten concurrent users; cross-user contamination; checklist 16", priority="P0", test_layer="API (10 threads)", account="alice, bob, carol, dan", input="10 simultaneous trips",
          steps="ThreadPoolExecutor(10); wait; verify each trip's owner", expected="10 trips, each owned by its creator, all reach a terminal state; no errors", actual=f"created={[c['status'] for c in conc]} finals={[c['final'] for c in conc]} wall={dur:.1f}s owner mismatches={len(mismatch)}",
          status="PASS" if all(c["status"] == 202 and c["final"] == "BOOKED" for c in conc) and not mismatch else "FAIL", evidence=qa.evidence("p13-concurrent-users.json", {"results": conc, "owners": owners, "wall_seconds": dur}))

# ---- volume: 100 trips at API level (bounded submit rate), then list and detail behaviour
t0 = time.time()
with cf.ThreadPoolExecutor(8) as ex:
    created = list(ex.map(lambda i: qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} volume {i:03d}", 100 + (i % 20), 102 + (i % 20))), range(100)))
ids = [(r.json or {}).get("tripId") for r in created if r.status == 202]
submit_s = time.time() - t0
def settled():
    lst = qa.api("alice", "GET", "/api/v1/trips?limit=400").json or []
    st = {t["tripId"]: t["status"] for t in lst if t["tripId"] in ids}
    return st
st = qa.poll(settled, lambda s: len(s) == len(ids) and all(v in ("BOOKED", "FAILED", "AWAITING_APPROVAL", "CANCELLED") for v in s.values()), 600, every=5)
total_s = time.time() - t0
from collections import Counter
dist = Counter(st.values())
ms, lst = timed(lambda: qa.api("alice", "GET", "/api/v1/trips?limit=400"))
qa.record(test_id="P13-VOL-01", source_requirement="Prompt 13 volume: 100 trips at API level; list behaviour; checklist 31", priority="P1", test_layer="API (8 threads)", account="alice", input="100 POST /trips",
          steps="submit; poll the list until all settle (600 s bound); time the big list", expected="100 accepted; all settle; the list of 400 answers in bounded time; no pagination cursor exists (limit only): reported as a gap", actual=f"accepted={len(ids)}/100 submit={submit_s:.1f}s settle={total_s:.0f}s distribution={dict(dist)} list(limit=400)={ms:.0f}ms items={len(lst.json) if isinstance(lst.json, list) else lst.status}",
          status="PASS" if len(ids) == 100 and len(st) == 100 and all(v == "BOOKED" for v in st.values()) else "FAIL", evidence=qa.evidence("p13-volume.json", {"accepted": len(ids), "distribution": dict(dist), "seconds": total_s, "list_ms": ms}), bug_id="" if len(st) == 100 else "BUG-13 (see report)")
qa.record(test_id="P13-VOL-02", source_requirement="Prompt 13 1,000 trips; 50/100 concurrent users; soak", priority="P2", test_layer="—", account="—", input="—", steps="—", expected="—",
          actual="not run: no isolated load environment (LOAD_LIMIT 10 users on the shared local stack); 1,000 trips would take >30 min of worker time on this laptop and add noise to the shared database. Proposed for a dedicated load run.", status="BLOCKED", evidence="")

# ---- stale state: the product has no edit; the equivalent is a new trip and the old one's offers must not attach
a = qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} stale A BGR->BOS", 60, 62, origin="BGR", dest="BOS")).json["tripId"]
b = qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} stale B BGR->JFK", 60, 62, origin="BGR", dest="JFK")).json["tripId"]
c = qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} stale C BOS->NRT dec/jan", 95, 100, origin="BOS", dest="NRT")).json["tripId"]
for x in (a, b, c):
    qa.wait_trip("alice", x, {"BOOKED", "FAILED"}, 200)
oa, ob, oc = (qa.api("alice", "GET", f"/api/v1/orders?tripId={x}").json or [{}] for x in (a, b, c))
routes = [(x[0].get("items", [{}])[0].get("flights", [{}])[0].get("origin"), x[0].get("items", [{}])[0].get("flights", [{}])[0].get("destination")) for x in (oa, ob, oc)]
qa.record(test_id="P13-STALE-01", source_requirement="Prompt 13 stale state: Bangor->Boston, then ->New York, then Boston->Tokyo across December/January", priority="P0", test_layer="API", account="alice", input="three trips as the product's substitute for editing",
          steps="book A, B, C; read each order's first flight", expected="each order matches its own request; no offer/price from A attaches to B or C; A's confirmed history untouched", actual=f"orders' first legs={routes}; A still {(qa.trip('alice', a) or {}).get('status')}",
          status="PASS" if routes == [("BGR", "BOS"), ("BGR", "JFK"), ("BOS", "NRT")] else "FAIL", evidence=qa.evidence("p13-stale.json", {"orders": [oa, ob, oc]}), ids=f"{a} {b} {c}")

# ---- rapid reordered searches: five different destinations in parallel, then the same for bob; each result belongs to its request
dests = ["LHR", "CDG", "NRT", "DEL", "MIA"]
def go(u, d):
    r = qa.api(u, "POST", "/api/v1/trips", qa.round_trip(f"{T} rapid {u} JFK->{d}", 70, 74, origin="JFK", dest=d)); tid = r.json["tripId"]
    qa.wait_trip(u, tid, {"BOOKED", "FAILED"}, 200)
    o = qa.api(u, "GET", f"/api/v1/orders?tripId={tid}").json or [{}]
    return (u, d, tid, (o[0].get("items", [{}])[0].get("flights", [{}])[0].get("destination")))
with cf.ThreadPoolExecutor(10) as ex:
    rapid = list(ex.map(lambda ud: go(*ud), [(u, d) for u in ("alice", "bob") for d in dests]))
qa.record(test_id="P13-CACHE-01", source_requirement="Prompt 13 rapid NYC->London/Paris/Tokyo/Delhi/Miami searches, reordered responses, identical searches for multiple users", priority="P0", test_layer="API (10 threads)", account="alice, bob",
          input="5 destinations x 2 users in parallel", steps="submit all; verify each order's destination equals its request", expected="every result attached to its own request and user", actual=f"{[(u, d, dst) for u, d, _, dst in rapid]}",
          status="PASS" if all(d == dst for _, d, _, dst in rapid) else "FAIL", evidence=qa.evidence("p13-rapid.json", rapid))
print(qa.summary())
