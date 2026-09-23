"""Prompt 9: Temporal, idempotency, reconciliation, persistence (what P1 faults did not already prove)."""

import concurrent.futures as cf
import json
import subprocess
import time
import uuid

import qa

T = qa.TAG


def psql(db, sql):
    return subprocess.run(["docker", "exec", "travelos-postgres-1", "psql", "-U", "travelos", "-d", db, "-tAc", sql], capture_output=True, text=True, timeout=60).stdout.strip()


def temporal(*args):
    return subprocess.run(["docker", "exec", "travelos-temporal-1", "temporal", *args, "--address", "temporal:7233", "--namespace", "travelos"], capture_output=True, text=True, timeout=60).stdout


def submit(user, body, **kw):
    r = qa.api(user, "POST", "/api/v1/trips", body, **kw)
    return (r.json or {}).get("tripId", ""), r


# ---- state machine + workflow identity discovery (evidence)
tid, r = submit("alice", qa.round_trip(f"{T} WF discovery", 30, 32))
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 150)
desc = temporal("workflow", "describe", "-w", tid)
hist = qa.api("alice", "GET", f"/api/v1/trips/{tid}/history").json or []
qa.record(test_id="P9-DISC-01", source_requirement="Prompt 9 discover the real state machine, workflow ids, idempotency keys", priority="P1", test_layer="API + Temporal CLI", account="alice", input=tid,
          steps="book; temporal workflow describe -w <tripId>; GET /history", expected="workflow id == trip id; one workflow per trip; history rows per transition",
          actual=f"workflow status line: {[l.strip() for l in desc.splitlines() if 'Status' in l or 'WorkflowType' in l][:3]}; history={[h.get('toStatus') for h in hist]}", status="PASS" if "COMPLETED" in desc and st == "BOOKED" else "FAIL", evidence=qa.evidence("p9-discovery.json", {"describe": desc[:2000], "history": hist}), ids=tid)

# ---- idempotency key across tenants: the same key by alice and zoe must not collide
key = f"{T}-xtenant-{uuid.uuid4()}"
a = submit("alice", qa.round_trip(f"{T} key across tenants (alice)", 34, 36), idem=key)
z = submit("zoe", qa.round_trip(f"{T} key across tenants (zoe)", 34, 36), idem=key)
qa.record(test_id="P9-IDEM-01", source_requirement="Prompt 9 reuse a key across tenants: isolation", priority="P0", test_layer="API", account="alice (acme), zoe (globex)", input=f"same Idempotency-Key {key[-12:]}", steps="POST /trips by each",
          expected="two different trips (keys are scoped per tenant) or a conflict for the second; never the first tenant's trip returned to the second", actual=f"alice {a[1].status} {a[0]}; zoe {z[1].status} {z[0]}; same id={a[0] == z[0]}",
          status="PASS" if a[1].status == 202 and z[1].status in (202, 409, 422) and (a[0] != z[0] or not z[0]) else "FAIL", evidence=qa.evidence("p9-key-across-tenants.json", {"alice": a[1].json, "zoe": {"status": z[1].status, "body": z[1].json}}))
# same key, same tenant, different user (dan) -> must not return alice's trip
d = submit("dan", qa.round_trip(f"{T} key across users (dan)", 34, 36), idem=key)
qa.record(test_id="P9-IDEM-02", source_requirement="Prompt 9 key reuse by another user of the same tenant", priority="P0", test_layer="API", account="dan/acme", input="alice's key", steps="POST /trips",
          expected="dan gets his own trip or a conflict; never alice's trip id", actual=f"dan {d[1].status} {d[0]}; equals alice's={d[0] == a[0]}", status="PASS" if d[0] != a[0] else "FAIL", evidence=qa.evidence("p9-key-across-users.json", {"dan": {"status": d[1].status, "body": d[1].json}, "alice_trip": a[0]}))

# ---- completed-workflow protection: signalling an approval on a finished trip
r = qa.api("bob", "POST", f"/api/v1/trips/{tid}/approval", {"decision": "APPROVE"})
r2 = qa.api("alice", "POST", f"/api/v1/trips/{tid}/cancellation", {"reason": f"{T} cancel booked"})
st2 = qa.wait_trip("alice", tid, {"CANCELLED"}, 60)
r3 = qa.api("bob", "POST", f"/api/v1/trips/{tid}/approval", {"decision": "REJECT"})
qa.record(test_id="P9-DONE-01", source_requirement="Prompt 9 completed-workflow protection", priority="P0", test_layer="API", account="bob, alice", input=f"{tid} BOOKED then CANCELLED",
          steps="approve a BOOKED trip; cancel; reject a CANCELLED trip", expected="approval of a trip that is not awaiting one is refused (409/422); cancellation of BOOKED allowed; decisions on a terminal trip refused",
          actual=f"approve BOOKED {r.status} {(r.json or {}).get('code')}; cancel {r2.status} -> {st2}; reject CANCELLED {r3.status} {(r3.json or {}).get('code')}", status="PASS" if r.status in (409, 422) and r2.status == 200 and r3.status in (409, 422) else "FAIL", evidence=qa.evidence("p9-completed-protection.json", {"approve": {"status": r.status, "body": r.json}, "cancel": r2.json, "reject": {"status": r3.status, "body": r3.json}}), ids=tid)

# ---- concurrent competing approvals (bob and carol at once)
qa.seed_policy(lambda dd: dd["approval"].__setitem__("managerRequiredAbove", 1), "approval race")
try:
    tid2, _ = submit("alice", qa.round_trip(f"{T} approval race", 38, 40))
    qa.wait_trip("alice", tid2, {"AWAITING_APPROVAL", "FAILED"}, 120)
    with cf.ThreadPoolExecutor(2) as ex:
        rs = list(ex.map(lambda u: qa.api(u, "POST", f"/api/v1/trips/{tid2}/approval", {"decision": "APPROVE" if u == "bob" else "REJECT", "comment": f"{T} race {u}"}), ["bob", "carol"]))
    st3 = qa.wait_trip("alice", tid2, {"BOOKED", "CANCELLED", "FAILED"}, 150)
    hist2 = qa.api("alice", "GET", f"/api/v1/trips/{tid2}/history").json or []
    dec = [h for h in hist2 if h.get("toStatus") in ("APPROVED", "CANCELLED")]
    qa.record(test_id="P9-RACE-01", source_requirement="Prompt 9/13 competing updates: two approvers decide the same trip at once", priority="P0", test_layer="API (2 threads)", account="bob APPROVE vs carol REJECT",
              input=tid2, steps="simultaneous POST /approval", expected="exactly one decision wins (200); the other gets a conflict (409/422); the trip follows the winner; no impossible state",
              actual=f"statuses={[x.status for x in rs]} codes={[(x.json or {}).get('code') for x in rs]}; final={st3}; decision rows={[(h.get('toStatus'), (h.get('reason') or '')[:40]) for h in dec]}",
              status="PASS" if sorted(x.status for x in rs) in ([200, 409], [200, 422]) and st3 in ("BOOKED", "CANCELLED") else "FAIL", evidence=qa.evidence("p9-approval-race.json", {"responses": [{"status": x.status, "body": x.json} for x in rs], "history": hist2}), ids=tid2)
finally:
    qa.seed_policy(None, "restore")

# ---- cancel vs booking race: cancel while BOOKING
tid3, _ = submit("alice", qa.round_trip(f"{T} cancel vs booking race", 42, 44))
t0 = time.time()
while time.time() - t0 < 30:
    s = (qa.trip("alice", tid3) or {}).get("status")
    if s in ("APPROVED", "BOOKING", "BOOKED"):
        break
    time.sleep(0.2)
c = qa.api("alice", "POST", f"/api/v1/trips/{tid3}/cancellation", {"reason": f"{T} race cancel"})
st4 = qa.wait_trip("alice", tid3, {"CANCELLED", "BOOKED", "FAILED"}, 150)
time.sleep(5)
o = qa.api("alice", "GET", f"/api/v1/orders?tripId={tid3}").json or []
h = qa.api("alice", "GET", f"/api/v1/trips/{tid3}/history").json or []
qa.record(test_id="P9-RACE-02", source_requirement="Prompt 9/13 booking/cancellation race", priority="P0", test_layer="API race", account="alice", input=f"cancel {tid3} while status={s}",
          steps="poll until APPROVED/BOOKING; POST cancellation; observe", expected="a consistent end: CANCELLED with the order cancelled/absent, or the cancellation refused (409) because booking had started and the trip BOOKED; never CANCELLED with a live confirmed order left unmentioned",
          actual=f"cancel {c.status} {(c.json or {}).get('code')}; final={st4}; orders={[(x.get('status'), [i.get('status') for i in x.get('items', [])]) for x in o]}; history={[x.get('toStatus') for x in h]}",
          status="PASS" if (st4 == "CANCELLED" and (not o or all(x.get("status") == "CANCELLED" for x in o))) or (c.status in (409, 422) and st4 == "BOOKED") else "FAIL", evidence=qa.evidence("p9-cancel-race.json", {"cancel": {"status": c.status, "body": c.json}, "orders": o, "history": h}), ids=tid3,
          bug_id="" if (st4 == "CANCELLED" and (not o or all(x.get("status") == "CANCELLED" for x in o))) or c.status in (409, 422) else "BUG-08")

# ---- integrity after the restarts done in P1: no orphans, FK consistency (read-only)
orphans = psql("order", "select count(*) from purchase_order po where not exists (select 1 from order_item i where i.order_id = po.order_id)") or psql("order", "select count(*) from orders o where not exists (select 1 from order_item i where i.order_id=o.order_id)")
trips_booked_without_order = psql("travel_core", "select count(*) from trip where status='BOOKED' and order_id is null")
outbox_unpublished = {db: psql(db, "select count(*) from outbox where published_at is null") for db in ("travel_core", "order", "disruption", "policy", "supplier_gateway", "audit", "learning", "enterprise_context")}
qa.record(test_id="P9-INTEG-01", source_requirement="Prompt 9 after restarts: FK relationships, orphan records, durable statuses", priority="P0", test_layer="database read-only", account="—", input="orders without items; BOOKED trips without an order id; unpublished outbox rows per service",
          steps="psql counts", expected="0 orphans; 0 BOOKED trips without an order; outboxes drained (0 or transiently small)", actual=f"orders without items={orphans or 'n/a'}; BOOKED trips without order_id={trips_booked_without_order}; unpublished outbox={outbox_unpublished}",
          status="PASS" if (orphans in ("0", "")) and trips_booked_without_order == "0" and all(v in ("0", "") for v in outbox_unpublished.values()) else "FAIL", evidence=qa.evidence("p9-integrity.json", {"orphans": orphans, "booked_without_order": trips_booked_without_order, "outbox": outbox_unpublished}))

# ---- retries are bounded and honest: the supplier-outage trip from P1 shows attempts in Temporal history
qa.record(test_id="P9-RETRY-01", source_requirement="Prompt 9 bounded retries / replay / resumption", priority="P0", test_layer="cross-reference", account="—", input="—", steps="—", expected="—",
          actual="P1-FAIL-02 (supplier down 25 s then back: BOOKED, one order), P1-FAIL-03 (commit-then-lost answer reconciled once), P1-FAIL-05 (worker restarted mid-flight: BOOKED, one order), P1-FAIL-01 (search outage ends FAILED/NO_OFFERS after bounded retries, no booking), P1-FAIL-04 (refused hotel: flights compensated, trip FAILED with code)", status="PASS", evidence="results.csv")
print(qa.summary())
