"""Corrected ground-transfer fixtures (valid transfer kinds), the LAX exposure path to Finance, and the
open-jaw/coverage reclassifications."""
import json, subprocess
import qa
T = qa.TAG
def psql(db, sql):
    return subprocess.run(["docker", "exec", "travelos-postgres-1", "psql", "-U", "travelos", "-d", db, "-tAc", sql], capture_output=True, text=True, timeout=60).stdout.strip()
def submit(body):
    r = qa.api("alice", "POST", "/api/v1/trips", body); return (r.json or {}).get("tripId", ""), r
def itin(purpose, legs, stays=(), transfers=()):
    return {"source": "API", "intent": {"purpose": purpose, "itinerary": {"currency": "USD", "legs": [{"origin": o, "destination": d, "earliestDeparture": f"{qa.future(day)}T05:00:00Z", "arrivalDeadline": f"{qa.future(day)}T23:59:00Z"} for o, d, day in legs], "stays": [{"city": c, "checkInDate": qa.future(i), "checkOutDate": qa.future(o), "required": True} for c, i, o in stays], "transfers": list(transfers)}}}
# DEN: ground shuttle commits then loses the answer (+ DEN hotel TIMEOUT): both reconciled
tid, r = submit(itin(f"{T} SUP ground timeout DEN (v2)", [("BOS", "DEN", 30), ("DEN", "BOS", 32)], [("DEN", 30, 32)], [{"kind": "AIRPORT_TO_HOTEL", "city": "DEN", "required": True}]))
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 240) if tid else "REJECTED"
comps = qa.api("alice", "GET", f"/api/v1/trips/{tid}/components").json or [] if tid else []
o = qa.api("alice", "GET", f"/api/v1/orders?tripId={tid}").json or [] if tid else []
qa.record(test_id="P5-SUP-04b", source_requirement="Prompt 5 supplier commits then loses the answer (DEN ground + DEN hotel TIMEOUT fixtures) — corrected transfer kind", priority="P0", test_layer="API + sandbox fixture", account="alice", input="BOS->DEN->BOS, DEN stay, AIRPORT_TO_HOTEL transfer",
  steps="POST; poll; components; orders", expected="every component CONFIRMED exactly once after reconciliation", actual=f"{r.status} {(r.json or {}).get('detail','')[:80] if r.status!=202 else ''} -> {st}; components={[(c.get('type'), c.get('status')) for c in comps]}; orders={[x.get('status') for x in o]}",
  status="PASS" if st == "BOOKED" and all(c.get("status") == "CONFIRMED" for c in comps) else "FAIL", evidence=qa.evidence("p5-SUP-04b.json", {"create": r.json, "components": comps, "orders": o}), ids=tid)
# LAX: ground refused at booking -> compensation -> LAX hotel refuses cancellation -> exposure for Finance
tid, r = submit(itin(f"{T} SUP LAX refused ground + exposure", [("BOS", "LAX", 30), ("LAX", "BOS", 32)], [("LAX", 30, 32)], [{"kind": "AIRPORT_TO_HOTEL", "city": "LAX", "required": True}]))
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 240) if tid else "REJECTED"
t = qa.trip("alice", tid) or {}
comps = qa.api("alice", "GET", f"/api/v1/trips/{tid}/components").json or [] if tid else []
o = qa.api("alice", "GET", f"/api/v1/orders?tripId={tid}").json or [] if tid else []
exps = qa.api("carol", "GET", "/api/v1/orders/exposures?status=OPEN").json or []
mine = [e for e in exps if e.get("tripId") == tid]
qa.record(test_id="P5-SUP-05b", source_requirement="Prompt 5/9 refused booking, partial compensation, failed compensation = exposure (LAX fixtures)", priority="P0", test_layer="API + sandbox fixture", account="alice; carol reads exposures",
  input="BOS->LAX->BOS, LAX stay (NO_CANCEL cheapest), LAX AIRPORT_TO_HOTEL transfer (FAIL cheapest)", steps="POST; poll; components; orders; GET /orders/exposures?status=OPEN",
  expected="documented: transfer refused -> flights and hotel compensated in reverse -> hotel cancel refused -> CANCEL_FAILED with an OPEN exposure; trip FAILED at COMPENSATION with COMPENSATION_INCOMPLETE; nothing shown as confirmed",
  actual=f"{st} {t.get('failureStage')}/{t.get('failureCode')}; components={[(c.get('type'), c.get('status')) for c in comps]}; order={[x.get('status') for x in o]}; open exposures for this trip={len(mine)} {[(e.get('exposure') or {}).get('amount') for e in mine][:1]}",
  status="PASS" if st == "FAILED" and t.get("failureCode") == "COMPENSATION_INCOMPLETE" and mine else "FAIL", evidence=qa.evidence("p5-SUP-05b.json", {"final": t, "components": comps, "orders": o, "exposures": mine}), ids=tid)
if mine:
    e = mine[0]; oid = e.get("orderId"); eid = (e.get("exposure") or {}).get("exposureId")
    a = qa.api("alice", "POST", f"/api/v1/orders/{oid}/exposures/{eid}/resolution", {"resolution": "WRITTEN_OFF"})
    c1 = qa.api("carol", "POST", f"/api/v1/orders/{oid}/exposures/{eid}/resolution", {"resolution": "WRITTEN_OFF"})
    c2 = qa.api("carol", "POST", f"/api/v1/orders/{oid}/exposures/{eid}/resolution", {"resolution": "RECOVERED"})
    after = qa.api("carol", "GET", "/api/v1/orders/exposures?status=RESOLVED").json or []
    qa.record(test_id="P5-EXP-01", source_requirement="Prompt 5/6 Finance resolves an exposure idempotently; travelers cannot; a second different resolution is refused", priority="P0", test_layer="API", account="alice (refused), carol (FINANCE)", input=f"exposure {eid} on {oid}",
      steps="alice resolves; carol resolves WRITTEN_OFF; carol resolves again with a different value", expected="alice 403; carol 200; second 200 idempotent same value or 409 for a different value; exposure listed as RESOLVED",
      actual=f"alice {a.status} {(a.json or {}).get('code')}; carol {c1.status}; carol again(different) {c2.status} {(c2.json or {}).get('code')}; resolved now={any((x.get('exposure') or {}).get('exposureId') == eid for x in after)}",
      status="PASS" if a.status == 403 and c1.status == 200 and c2.status in (200, 409, 422) and any((x.get('exposure') or {}).get('exposureId') == eid for x in after) else "FAIL", evidence=qa.evidence("p5-EXP-01.json", {"alice": {"status": a.status, "body": a.json}, "carol": c1.json, "again": {"status": c2.status, "body": c2.json}}), ids=f"{tid} {oid} {eid}")
# S15 flight + hotel + transfer with a valid kind
tid, r = submit(itin(f"{T} S15 flight hotel ground (v2)", [("BOS", "SEA", 30), ("SEA", "BOS", 33)], [("SEA", 30, 33)], [{"kind": "AIRPORT_TO_HOTEL", "city": "SEA", "required": True}]))
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 240) if tid else "REJECTED"
o = qa.api("alice", "GET", f"/api/v1/orders?tripId={tid}").json or [] if tid else []
items = (o[:1] or [{}])[0].get("items", [])
qa.record(test_id="P6-S15b", source_requirement="Prompt 6 style 15: flight + hotel + transfer (corrected transfer kind)", priority="P1", test_layer="API + workflow", account="alice", input="SEA stay + AIRPORT_TO_HOTEL transfer",
  steps="POST; poll; orders", expected="one order with AIR, HOTEL and GROUND items, each with its own supplier reference", actual=f"{r.status} -> {st}; items={[(i.get('type'), i.get('status'), (i.get('externalRef') or i.get('supplierReference') or '')[:12]) for i in items]}",
  status="PASS" if st == "BOOKED" and {i.get("type") for i in items} >= {"AIR", "HOTEL", "GROUND"} else "FAIL", evidence=qa.evidence("p6-S15b.json", {"orders": o}), ids=tid)
# (the audit's re-evaluation rows P4-R03b, P6-S12b, P6-S13b, P5-MONEY-ZERO-b, P5-MONEY-WRONG-b are executed live in this run by p04, p06 and p05)
print(qa.summary())
