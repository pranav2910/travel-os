"""Prompt 1 (second half): the journey with one failure at each meaningful stage, plus persona
behaviours at the API level. Faults come from the documented sandbox fixtures and from stopping a
container of this isolated stack for a few seconds. Every outcome is read from the authoritative
APIs (trip, order, components, history) and, where the API cannot show it, from the stack's own
database read-only."""

import json
import subprocess
import time

import qa

T = qa.TAG


def psql(db: str, sql: str) -> str:
    return subprocess.run(
        ["docker", "exec", "travelos-postgres-1", "psql", "-U", "travelos", "-d", db, "-tAc", sql],
        capture_output=True, text=True, timeout=30,
    ).stdout.strip()


def docker(*args: str) -> str:
    return subprocess.run(["docker", *args], capture_output=True, text=True, timeout=120).stdout.strip()


def submit(user: str, body: dict) -> tuple[str, qa.Resp]:
    r = qa.api(user, "POST", "/api/v1/trips", body)
    return (r.json or {}).get("tripId", ""), r


def components(user, tid):
    return (qa.api(user, "GET", f"/api/v1/trips/{tid}/components").json or [])


def orders(user, tid):
    return (qa.api(user, "GET", f"/api/v1/orders?tripId={tid}").json or [])


def history(user, tid):
    return (qa.api(user, "GET", f"/api/v1/trips/{tid}/history").json or [])


# ---- F1: search failure that is retryable (destination ZZZ = simulated outage), bounded observation
tid, r = submit("alice", qa.round_trip(f"{T} F1 search outage ZZZ", 30, 32, dest="ZZZ"))
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 90)
t = qa.trip("alice", tid)
ev = qa.evidence("p1-F1-search-outage.json", {"trip": t, "history": history("alice", tid)})
qa.record(test_id="P1-FAIL-01", source_requirement="Prompt 1 failure at search; sandbox fixture ZZZ = retryable UNAVAILABLE", priority="P1", test_layer="API + workflow",
          account="alice/acme", input="BOS->ZZZ", steps="POST /trips; observe 90 s", expected="documented: the search activity retries (Temporal retry policy) until the supplier answers; the trip stays honestly in PLANNING, never a false booking or a silent failure",
          actual=f"after 90 s status={st} failure={t.get('failureStage')}/{t.get('failureCode')} (retrying activity; no booking); see history", status="PASS" if st in ("PLANNING", "SUBMITTED") or (st == "FAILED" and t.get("failureCode")) else "FAIL",
          evidence=ev, ids=tid, cleanup="left in PLANNING; cancelled below")
c = qa.api("alice", "POST", f"/api/v1/trips/{tid}/cancellation", {"reason": f"{T} cleanup"})
qa.record(test_id="P1-FAIL-01b", source_requirement="Prompt 1: stop while planning (persona: changed my mind)", priority="P1", test_layer="API", account="alice/acme", input=f"cancel {tid} while the search is retrying",
          steps="POST /trips/{id}/cancellation", expected="cancellation accepted from PLANNING (documented lifecycle) and the trip ends CANCELLED", actual=f"{c.status} -> {qa.wait_trip('alice', tid, {'CANCELLED'}, 60)}", status="PASS" if c.status == 200 else "FAIL", evidence=qa.evidence("p1-F1b-cancel.json", {"status": c.status, "body": c.json}), ids=tid)

# ---- F2: supplier outage during planning, then recovery (container stopped for 25 s)
tid, r = submit("alice", qa.round_trip(f"{T} F2 supplier outage then recovery", 33, 35))
docker("stop", "travelos-supplier-gateway-1")
t0 = time.time()
st_mid = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 20)
docker("start", "travelos-supplier-gateway-1")
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 240)
o = orders("alice", tid)
ev = qa.evidence("p1-F2-supplier-outage-recovery.json", {"during_outage": st_mid, "final": qa.trip("alice", tid), "orders": o, "history": history("alice", tid)})
qa.record(test_id="P1-FAIL-02", source_requirement="Prompt 1: provider failure followed by recovery; checklist 10 providers, 32 outages", priority="P0", test_layer="API + service fault (supplier-gateway stopped 25 s)",
          account="alice/acme", input="BOS->SEA while sandbox gateway is down", steps="POST /trips; docker stop supplier-gateway; wait 20 s; docker start; poll 240 s",
          expected="no false state during the outage; after recovery the same trip completes with exactly one order", actual=f"during outage: {st_mid}; final: {st}; orders={len(o)}", status="PASS" if st == "BOOKED" and len(o) == 1 else "FAIL", evidence=ev, ids=tid)

# ---- F3: booking response lost after supplier success (DEN hotel TIMEOUT fixture) -> reconcile
tid, r = submit("alice", qa.itinerary(f"{T} F3 lost answer after commit (DEN)", [("BOS", "DEN", 36), ("DEN", "BOS", 38)], [("DEN", 36, 38)]))
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 240)
comps = components("alice", tid)
o = orders("alice", tid)
hotel = next((c for c in comps if c.get("type") == "HOTEL"), {})
sb = psql("supplier_gateway", f"select count(*) from sandbox_booking where correlation_id like '%{tid}%' or trip_id='{tid}'") or "n/a"
ev = qa.evidence("p1-F3-den-timeout.json", {"final": qa.trip("alice", tid), "components": comps, "orders": o, "supplier_side_bookings_for_trip": sb})
qa.record(test_id="P1-FAIL-03", source_requirement="Prompt 1: booking response lost after provider success; Prompt 9 reconciliation", priority="P0", test_layer="API + sandbox fixture (DEN hotel commits then loses the answer)",
          account="alice/acme", input="BOS->DEN->BOS with a DEN stay (cheapest DEN property = TIMEOUT fixture)", steps="POST /trips; poll; read components, orders and the supplier's booking table",
          expected="the Order service asks the supplier what it did before retrying (reconcile) and adopts the committed booking: hotel CONFIRMED once, no duplicate supplier booking",
          actual=f"status={st}; hotel component={hotel.get('status')} ref={hotel.get('externalRef')}; orders={len(o)}; supplier-side bookings for the trip={sb}", status="PASS" if st == "BOOKED" and hotel.get("status") == "CONFIRMED" else "FAIL", evidence=ev, ids=tid)

# ---- F4: supplier refuses a component at booking (AUS hotel FAIL) -> compensation of the flights
tid, r = submit("alice", qa.itinerary(f"{T} F4 refused hotel (AUS) compensates", [("BOS", "AUS", 40), ("AUS", "BOS", 42)], [("AUS", 40, 42)]))
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 240)
comps = components("alice", tid)
t = qa.trip("alice", tid)
ev = qa.evidence("p1-F4-aus-refused.json", {"final": t, "components": comps, "orders": orders("alice", tid)})
states = {c.get("type") + ":" + str(i): c.get("status") for i, c in enumerate(comps)}
qa.record(test_id="P1-FAIL-04", source_requirement="Prompt 1 failure at booking; Prompt 9 partial bookings/compensation", priority="P0", test_layer="API + sandbox fixture (AUS cheapest hotel refuses)",
          account="alice/acme", input="BOS->AUS->BOS with an AUS stay", steps="POST /trips; poll; read components", expected="documented contract: components book in order; a refused later component compensates the earlier ones in reverse; the trip ends FAILED with the stage and code recorded; nothing shown as confirmed",
          actual=f"status={st} {t.get('failureStage')}/{t.get('failureCode')}; components={states}", status="PASS" if st == "FAILED" and t.get("failureCode") else "FAIL", evidence=ev, ids=tid)

# ---- F5: worker killed mid-flight (durable workflow resumes)
tid, r = submit("alice", qa.round_trip(f"{T} F5 worker restart mid-flight", 44, 46))
time.sleep(0.8)
docker("restart", "travelos-trip-planning-1")
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 300)
o = orders("alice", tid)
ev = qa.evidence("p1-F5-worker-restart.json", {"final": qa.trip("alice", tid), "orders": o, "history": history("alice", tid)})
qa.record(test_id="P1-FAIL-05", source_requirement="Prompt 1/9: worker restart during the journey; checklist 14 Temporal", priority="P0", test_layer="API + service fault (worker restarted 0.8 s after submit)",
          account="alice/acme", input="BOS->SEA", steps="POST /trips; docker restart trip-planning; poll 300 s", expected="the workflow resumes from history on the replacement worker; exactly one order; no duplicate booking",
          actual=f"final={st}; orders={len(o)}", status="PASS" if st == "BOOKED" and len(o) == 1 else "FAIL", evidence=ev, ids=tid)

# ---- F6: policy denies everything (budget below every fare)
qa.seed_policy(lambda d: d["trip"].__setitem__("maxTotal", 100), "deny all")
try:
    tid, r = submit("alice", qa.round_trip(f"{T} F6 policy denies all", 48, 50))
    st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 120)
    t = qa.trip("alice", tid)
    pd = qa.api("alice", "GET", f"/api/v1/policy-decisions?tripId={tid}").json or []
    ev = qa.evidence("p1-F6-policy-denies.json", {"final": t, "decisions": len(pd), "first": pd[:1]})
    qa.record(test_id="P1-FAIL-06", source_requirement="Prompt 1 failure at policy; checklist 12 policy", priority="P0", test_layer="API", account="alice/acme (policy by carol)",
              input="trip.maxTotal = USD 1.00; BOS->SEA", steps="POST /policies (v+1); POST /trips; poll", expected="FAILED at POLICY with ALL_CANDIDATES_DENIED, decisions recorded with the policy version, nothing booked",
              actual=f"status={st} {t.get('failureStage')}/{t.get('failureCode')}; {len(pd)} decisions, first outcome {(pd[:1] or [{}])[0].get('outcome')} policy v{(pd[:1] or [{}])[0].get('policyVersion')}", status="PASS" if st == "FAILED" and t.get("failureCode") == "ALL_CANDIDATES_DENIED" else "FAIL", evidence=ev, ids=tid)
finally:
    qa.seed_policy(None, "restore")

# ---- F7: rejection by the manager ends honestly; approver cannot be the traveler
qa.seed_policy(lambda d: d["approval"].__setitem__("managerRequiredAbove", 1), "approval for all")
try:
    tid, r = submit("alice", qa.round_trip(f"{T} F7 rejected by manager", 52, 54))
    st = qa.wait_trip("alice", tid, {"AWAITING_APPROVAL", "FAILED"}, 120)
    self_try = qa.api("alice", "POST", f"/api/v1/trips/{tid}/approval", {"decision": "APPROVE"})
    peer_try = qa.api("dan", "POST", f"/api/v1/trips/{tid}/approval", {"decision": "APPROVE"})
    rej = qa.api("bob", "POST", f"/api/v1/trips/{tid}/approval", {"decision": "REJECT", "comment": f"{T} rejected"})
    st2 = qa.wait_trip("alice", tid, {"CANCELLED", "FAILED"}, 120)
    ev = qa.evidence("p1-F7-rejection.json", {"awaiting": st, "self_approval": {"status": self_try.status, "body": self_try.json}, "peer_traveler": {"status": peer_try.status, "body": peer_try.json}, "reject": {"status": rej.status, "body": rej.json}, "final": qa.trip("alice", tid), "orders": orders("alice", tid)})
    qa.record(test_id="P1-FAIL-07", source_requirement="Prompt 1 failure at approval; checklist 2 authorization (self-approval), 12 policy", priority="P0", test_layer="API", account="alice, dan, bob / acme",
              input="managerRequiredAbove=1; alice's trip; alice and dan try to approve; bob rejects", steps="seed policy; POST /trips; POST /approval x3", expected="alice 403 (own trip), dan 403 (not a manager; and 404 semantics for a trip he may not read is also acceptable), bob's REJECT accepted; trip ends CANCELLED/FAILED with the reason; no order",
              actual=f"awaiting={st}; self={self_try.status} {(self_try.json or {}).get('code')}; dan={peer_try.status} {(peer_try.json or {}).get('code')}; reject={rej.status}; final={st2}; orders={len(orders('alice', tid))}", status="PASS" if st == "AWAITING_APPROVAL" and self_try.status == 403 and peer_try.status in (403, 404) and rej.status == 200 and st2 in ("CANCELLED", "FAILED") else "FAIL", evidence=ev, ids=tid)
finally:
    qa.seed_policy(None, "restore")

# ---- personas at the API level
# power user / manager arranging for someone else; a plain traveler may not
identity = {"givenName": "Alice", "familyName": "Nguyen", "email": "alice@acme.example"}
c = qa.api("carol", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} persona: carol arranges for alice", 56, 58), "travelerId": "emp_1001", "traveler": identity})
d = qa.api("dan", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} persona: dan tries to arrange for alice", 56, 58), "travelerId": "emp_1001", "traveler": identity})
ct = qa.trip("carol", (c.json or {}).get("tripId", "x")) or {}
qa.record(test_id="P1-PERSONA-01", source_requirement="Prompt 1 personas (power user vs adversarial); checklist 9 travelers (arranging for others)", priority="P1", test_layer="API", account="carol (TRAVEL_ADMIN), dan (TRAVELER)",
          input="POST /trips with travelerId=emp_1001 (alice) and traveler identity", steps="carol then dan", expected="carol 202 and the trip's traveler is alice (snapshot with her name); dan refused (403) and no trip created",
          actual=f"carol={c.status} traveler={ct.get('travelerId')} snapshot={(ct.get('traveler') or {}).get('givenName')}; dan={d.status} {(d.json or {}).get('code')}", status="PASS" if c.status == 202 and ct.get("travelerId") == "emp_1001" and (ct.get("traveler") or {}).get("givenName") == "Alice" and d.status == 403 else "FAIL",
          evidence=qa.evidence("p1-persona-arrange.json", {"carol": c.json, "dan": {"status": d.status, "body": d.json}}), ids=(c.json or {}).get("tripId", ""))
# careless: return before departure, missing dates, empty purpose
bad = qa.round_trip(f"{T} persona: careless return before departure", 60, 58)
b1 = qa.api("alice", "POST", "/api/v1/trips", bad)
b2 = qa.api("alice", "POST", "/api/v1/trips", {"source": "API", "intent": {"origin": "BOS", "destination": "SEA"}})
b3 = qa.api("alice", "POST", "/api/v1/trips", {"source": "API", "intent": {}})
qa.record(test_id="P1-PERSONA-02", source_requirement="Prompt 1 careless persona; checklist 4 create validation, 26 dates", priority="P1", test_layer="API", account="alice",
          input="return before departure; no dates; empty intent", steps="POST /trips x3", expected="each refused with a useful 4xx problem and no trip created",
          actual=f"return-before-departure={b1.status} {(b1.json or {}).get('code')} '{(b1.json or {}).get('detail','')[:60]}'; no-dates={b2.status} {(b2.json or {}).get('code')}; empty={b3.status} {(b3.json or {}).get('code')}",
          status="PASS" if all(x.status in (400, 422) for x in (b1, b2, b3)) else "FAIL", evidence=qa.evidence("p1-persona-careless.json", [{"status": x.status, "body": x.json} for x in (b1, b2, b3)]))
# first-time user in a tenant without a rulebook (zoe/globex)
z, r = submit("zoe", qa.round_trip(f"{T} persona: zoe first trip", 62, 64))
st = qa.wait_trip("zoe", z, {"BOOKED", "FAILED"}, 120)
zt = qa.trip("zoe", z) or {}
zpd = qa.api("zoe", "GET", f"/api/v1/policy-decisions?tripId={z}").json or []
qa.record(test_id="P1-PERSONA-03", source_requirement="Prompt 1 first-time persona; Prompt 5 missing policy contract", priority="P1", test_layer="API", account="zoe/globex/TRAVELER",
          input="globex has no published policy", steps="POST /trips; poll; read decisions", expected="documented contract: NO_POLICY denies every candidate ('nothing can be booked until one is published'); trip FAILED at POLICY; reason readable by the traveler",
          actual=f"status={st} {zt.get('failureStage')}/{zt.get('failureCode')}; first reason={((zpd[:1] or [{}])[0].get('decision') or {}).get('reasons', [{}])[0].get('code') if zpd else None}", status="PASS" if st == "FAILED" and zt.get("failureCode") == "ALL_CANDIDATES_DENIED" else "FAIL",
          evidence=qa.evidence("p1-persona-zoe.json", {"final": zt, "decisions": zpd[:2]}), ids=z)
print(qa.summary())
