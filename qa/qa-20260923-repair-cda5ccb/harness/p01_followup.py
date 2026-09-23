"""Prompt 1 corrections: a true cancel-while-planning race, a true policy DENY, and the arranged-trip snapshot."""
import json, time
import qa
T = qa.TAG
# cancel while planning (race right after submit)
r = qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} F1b cancel while planning", 30, 32)); tid = r.json["tripId"]
c = qa.api("alice", "POST", f"/api/v1/trips/{tid}/cancellation", {"reason": f"{T} changed my mind"})
st = qa.wait_trip("alice", tid, {"CANCELLED", "BOOKED", "FAILED"}, 120); t = qa.trip("alice", tid)
o = qa.api("alice", "GET", f"/api/v1/orders?tripId={tid}").json or []
h = qa.api("alice", "GET", f"/api/v1/trips/{tid}/history").json or []
ok = (c.status == 200 and st == "CANCELLED" and (len(o) == 0 or all(x.get("status") == "CANCELLED" for x in o))) or (c.status in (409, 422) and st == "BOOKED")
qa.record(test_id="P1-FAIL-01c", source_requirement="Prompt 1: stop while planning (replaces P1-FAIL-01b whose trip had already failed)", priority="P0", test_layer="API + workflow race", account="alice",
  input="POST /trips then POST /cancellation ~200 ms later", steps="submit; cancel at once; poll; read orders and history", expected="cancellation accepted from SUBMITTED/PLANNING; the workflow stops before booking (no order) or, if the booking already committed, the order is cancelled; the final status is CANCELLED and history says why",
  actual=f"cancel {c.status} {(c.json or {}).get('code','')}; final={st}; orders={[x.get('status') for x in o]}; history={[x.get('toStatus') for x in h]}", status="PASS" if ok else "FAIL", evidence=qa.evidence("p1-F1c-cancel-race.json", {"cancel": {"status": c.status, "body": c.json}, "final": t, "orders": o, "history": h}), ids=tid)
# true denial: trip.onViolation = DENY
qa.seed_policy(lambda d: d.__setitem__("trip", {"maxTotal": 100, "onViolation": "DENY"}), "deny over 1 dollar")
try:
    r = qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} F6b policy denies (onViolation DENY)", 48, 50)); tid = r.json["tripId"]
    st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 120); t = qa.trip("alice", tid)
    pd = qa.api("alice", "GET", f"/api/v1/policy-decisions?tripId={tid}").json or []
    reasons = sorted({rr.get("code") for d in pd for rr in ((d.get("decision") or {}).get("reasons") or [])})
    qa.record(test_id="P1-FAIL-06b", source_requirement="Prompt 1 failure at policy (corrects P1-FAIL-06: over trip.maxTotal is REQUIRE_APPROVAL by the seed's own rule; DENY must be asked for explicitly)", priority="P0", test_layer="API", account="alice (policy by carol)",
      input="trip.maxTotal=USD 1.00 onViolation=DENY", steps="POST /policies; POST /trips; poll; decisions", expected="FAILED at POLICY with ALL_CANDIDATES_DENIED; every decision DENY with the reason code; version recorded", actual=f"status={st} {t.get('failureStage')}/{t.get('failureCode')}; {len(pd)} decisions; reason codes={reasons}; policy v{(pd[:1] or [{}])[0].get('policyVersion')}",
      status="PASS" if st == "FAILED" and t.get("failureCode") == "ALL_CANDIDATES_DENIED" else "FAIL", evidence=qa.evidence("p1-F6b-deny.json", {"final": t, "reasons": reasons, "first": pd[:1]}), ids=tid)
finally:
    qa.seed_policy(None, "restore")
# arranged trip: the arranger must say who travels; the snapshot then carries the traveler (BUG-04)
anon = qa.api("carol", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} persona: carol arranges for alice (no identity)", 56, 58), "travelerId": "emp_1001"})
named = qa.api("carol", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} persona: carol arranges for alice", 56, 58), "travelerId": "emp_1001", "traveler": {"givenName": "Alice", "familyName": "Nguyen", "email": "alice@acme.example"}})
dan = qa.api("dan", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} persona: dan tries to arrange for alice", 56, 58), "travelerId": "emp_1001", "traveler": {"givenName": "Alice", "familyName": "Nguyen", "email": "alice@acme.example"}})
ct = qa.trip("carol", (named.json or {}).get("tripId", "x")) or {}
snap = ct.get("traveler") or {}
qa.record(test_id="P1-PERSONA-01c", source_requirement="Prompt 1 power-user persona: arranging for another traveler (BUG-04: the traveler snapshot must carry the traveler)", priority="P1", test_layer="API", account="carol (TRAVEL_ADMIN) for alice; dan refused",
  input="POST /trips travelerId=emp_1001 without and with traveler{givenName,familyName,email}", steps="carol x2; dan", expected="carol without identity 422 TRAVELER_IDENTITY_REQUIRED; carol with identity 202 and snapshot name/email = the traveler; dan 403",
  actual=f"anonymous {anon.status} {(anon.json or {}).get('code')}; named {named.status} travelerId={ct.get('travelerId')} snapshot={snap}; dan {dan.status} {(dan.json or {}).get('code')}",
  status="PASS" if anon.status == 422 and named.status == 202 and ct.get("travelerId") == "emp_1001" and snap.get("givenName") == "Alice" and snap.get("email") == "alice@acme.example" and dan.status == 403 else "FAIL",
  evidence=qa.evidence("p1-persona-arrange.json", {"anonymous": {"status": anon.status, "body": anon.json}, "named": named.json, "trip": ct, "dan": {"status": dan.status, "body": dan.json}}), ids=(named.json or {}).get("tripId", ""))
print(qa.summary())
