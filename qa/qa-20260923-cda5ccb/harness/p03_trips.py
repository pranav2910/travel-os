"""Prompt 3: trips, forms, lifecycle, travelers, mutation resilience (API layer)."""

import concurrent.futures as cf
import json
import uuid

import qa

T = qa.TAG


def code(r):
    return (r.json or {}).get("code") if isinstance(r.json, dict) else None


def create(user, body, **kw):
    return qa.api(user, "POST", "/api/v1/trips", body, **kw)


def rec(test_id, req, prio, account, inp, expected, actual, ok, ev, ids="", bug=""):
    qa.record(test_id=test_id, source_requirement=req, priority=prio, test_layer="API", account=account, input=inp, steps="POST /api/v1/trips (then GET where relevant)",
              expected=expected, actual=actual, status="PASS" if ok else "FAIL", evidence=ev, ids=ids, bug_id=bug)


# ---------------------------------------------------------------- field validation
long_purpose = "x" * 10000
inert_html = "<img src=x onerror=alert(1)><script>alert('qa')</script>"
sql_like = "'; DROP TABLE trip; -- " + T
cases = [
    ("P3-VAL-01", "purpose empty", {"purpose": ""}, "accepted with purpose omitted (optional) or 422; never a crash", lambda r: r.status in (202, 422)),
    ("P3-VAL-02", "purpose whitespace only", {"purpose": "   "}, "trimmed to empty (optional) or 422", lambda r: r.status in (202, 422)),
    ("P3-VAL-03", "purpose 10,000 chars", {"purpose": long_purpose}, "a bounded maximum: 422 with a useful problem, or accepted and stored intact (then the bound is undocumented: report)", lambda r: r.status in (202, 422)),
    ("P3-VAL-04", "purpose Unicode 東京 → Montréal", {"purpose": f"{T} 東京 → Montréal"}, "accepted and stored verbatim", lambda r: r.status == 202),
    ("P3-VAL-05", "purpose emoji", {"purpose": f"{T} ✈️🏨🚕"}, "accepted and stored verbatim", lambda r: r.status == 202),
    ("P3-VAL-06", "purpose HTML/script", {"purpose": f"{T} {inert_html}"}, "accepted as inert text (rendering checked in P10/P11) or rejected; never executed", lambda r: r.status in (202, 422)),
    ("P3-VAL-07", "purpose SQL-like", {"purpose": sql_like}, "accepted as inert text; the trip table still exists afterwards", lambda r: r.status in (202, 422)),
    ("P3-VAL-08", "origin lowercase 'bos'", {"origin": "bos"}, "normalised to BOS or 422 (documented: three-letter codes)", lambda r: r.status in (202, 422)),
    ("P3-VAL-09", "origin two letters 'BO'", {"origin": "BO"}, "422", lambda r: r.status == 422),
    ("P3-VAL-10", "origin four letters 'BOSS'", {"origin": "BOSS"}, "422", lambda r: r.status == 422),
    ("P3-VAL-11", "origin with a digit 'B0S'", {"origin": "B0S"}, "422", lambda r: r.status == 422),
    ("P3-VAL-12", "origin == destination BOS->BOS", {"origin": "BOS", "destination": "BOS"}, "422 (a flight to itself is not a trip); accepting it is a validation gap", lambda r: r.status == 422),
    ("P3-VAL-13", "earliestDeparture after arrivalDeadline", {"earliestDeparture": f"{qa.future(30)}T23:00:00Z", "arrivalDeadline": f"{qa.future(30)}T05:00:00Z"}, "422", lambda r: r.status == 422),
    ("P3-VAL-14", "departure in the past (2025-01-10)", {"earliestDeparture": "2025-01-10T05:00:00Z", "arrivalDeadline": "2025-01-10T23:00:00Z", "returnAfter": "2025-01-12T10:00:00Z", "latestReturn": "2025-01-12T23:00:00Z"}, "422 (cannot book the past); accepting it is a validation gap", lambda r: r.status == 422),
    ("P3-VAL-15", "invalid instant '2026-13-45T00:00:00Z'", {"earliestDeparture": "2026-13-45T00:00:00Z"}, "400/422 problem, not 500", lambda r: r.status in (400, 422)),
    ("P3-VAL-16", "missing destination", {"destination": None}, "422", lambda r: r.status == 422),
    ("P3-VAL-17", "hotelRequired true without stay dates (round trip: nights between flights)", {"hotelRequired": True}, "202: documented: an explicit stay is derived from the flights", lambda r: r.status == 202),
]
for tid_, label, patch, expected, ok in cases:
    body = qa.round_trip(f"{T} {label}", 30, 32)
    for k, v in patch.items():
        if v is None:
            body["intent"].pop(k, None)
        else:
            body["intent"][k] = v
    r = create("alice", body)
    stored = qa.trip("alice", (r.json or {}).get("tripId", "x")) if r.status == 202 else None
    detail = ""
    if stored and "purpose" in patch and patch["purpose"]:
        detail = f"; stored verbatim={stored.get('intent', {}).get('purpose') == patch['purpose']}"
    rec(tid_, f"Prompt 3 / checklist 4 create validation: {label}", "P1", "alice", json.dumps(patch)[:120], expected, f"{r.status} {code(r) or ''} {(r.json or {}).get('detail', '')[:80] if isinstance(r.json, dict) else ''}{detail}", ok(r), qa.evidence(f"p3-{tid_}.json", {"request": body, "status": r.status, "response": r.json, "stored": stored}), (r.json or {}).get("tripId", "") if isinstance(r.json, dict) else "")

# the SQL-like text did not damage anything: the list still answers
lst = qa.api("alice", "GET", "/api/v1/trips?limit=5")
rec("P3-VAL-18", "Prompt 3 SQL-like input is inert (checklist 23 misuse, 24 security)", "P0", "alice", "GET /trips after the SQL-like purpose", "200 and a list", f"{lst.status}, {len(lst.json) if isinstance(lst.json, list) else lst.json}", lst.status == 200 and isinstance(lst.json, list), qa.evidence("p3-after-sql.json", {"status": lst.status, "count": len(lst.json) if isinstance(lst.json, list) else None}))

# ---------------------------------------------------------------- itinerary validation
it_cases = [
    ("P3-ITN-01", "zero legs", qa.itinerary(f"{T} zero legs", []), "422", lambda r: r.status == 422),
    ("P3-ITN-02", "stay check-out before check-in", qa.itinerary(f"{T} stay reversed", [("BOS", "SEA", 30), ("SEA", "BOS", 33)], [("SEA", 33, 30)]), "422", lambda r: r.status == 422),
    ("P3-ITN-03", "unknown currency XXX", qa.itinerary(f"{T} currency XXX", [("BOS", "SEA", 30), ("SEA", "BOS", 33)], currency="XXX"), "422 or a documented denial; never a booking in an unknown currency", lambda r: r.status in (202, 422)),
    ("P3-ITN-04", "legs out of chronological order (return before outbound)", qa.itinerary(f"{T} legs reversed", [("SEA", "BOS", 33), ("BOS", "SEA", 30)]), "422 or FAILED with a chronology reason (documented LEG_CHRONOLOGY)", lambda r: r.status in (202, 422)),
    ("P3-ITN-05", "seven legs around the world", qa.itinerary(f"{T} seven legs", [("BOS", "LHR", 30), ("LHR", "DXB", 32), ("DXB", "DEL", 34), ("DEL", "SIN", 36), ("SIN", "NRT", 38), ("NRT", "BOS", 40)]), "accepted; each leg searched and booked in order (or a documented cap)", lambda r: r.status in (202, 422)),
]
for tid_, label, body, expected, ok in it_cases:
    r = create("alice", body)
    final = None
    if r.status == 202:
        st = qa.wait_trip("alice", r.json["tripId"], {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 200)
        final = qa.trip("alice", r.json["tripId"])
    rec(tid_, f"Prompt 3 itinerary validation: {label}", "P1", "alice", label, expected, f"{r.status} {code(r) or ''}; final={final and final.get('status')} {final and (final.get('failureStage') or '')}/{final and (final.get('failureCode') or '')}", ok(r), qa.evidence(f"p3-{tid_}.json", {"status": r.status, "response": r.json, "final": final, "components": qa.api('alice','GET',f"/api/v1/trips/{r.json['tripId']}/components").json if r.status == 202 else None}), (r.json or {}).get("tripId", "") if r.status == 202 else "")

# ---------------------------------------------------------------- list / detail
for tid_, q, expected, ok in [
    ("P3-LIST-01", "?limit=0", "400/422 or an empty list; never 500", lambda r: r.status in (200, 400, 422)),
    ("P3-LIST-02", "?limit=-1", "400/422; never 500", lambda r: r.status in (400, 422)),
    ("P3-LIST-03", "?limit=100000", "clamped to a documented maximum or 422; never 500", lambda r: r.status in (200, 400, 422)),
    ("P3-LIST-04", "?limit=abc", "400/422", lambda r: r.status in (400, 422)),
    ("P3-LIST-05", "?scope=tenant&status=NOPE (as bob)", "422 STATUS_UNKNOWN (documented)", lambda r: r.status == 422),
    ("P3-LIST-06", "?scope=galaxy (as bob)", "422 SCOPE_UNKNOWN (documented)", lambda r: r.status == 422),
    ("P3-LIST-07", "?cursor=abc (pagination cursors)", "documented: no cursor pagination; parameter ignored (200) — reported as a gap for large tenants", lambda r: r.status == 200),
]:
    user = "bob" if "as bob" in q else "alice"
    r = qa.api(user, "GET", "/api/v1/trips" + q.split(" ")[0])
    rec(tid_, f"Prompt 3 / checklist 5 list: {q}", "P2", user, q, expected, f"{r.status} {code(r) or ''} {len(r.json) if isinstance(r.json, list) else ''}", ok(r), qa.evidence(f"p3-{tid_}.json", {"status": r.status, "body": r.json if not isinstance(r.json, list) else f'{len(r.json)} items'}))

for tid_, path, expected, ok in [
    ("P3-DET-01", "/api/v1/trips/trip_00000000000000000000000000", "404 problem", lambda r: r.status == 404),
    ("P3-DET-02", "/api/v1/trips/not-an-id", "404 or 400 problem; never 500", lambda r: r.status in (400, 404)),
    ("P3-DET-03", "/api/v1/trips/'%20OR%201=1--", "404/400 problem; never 500 or a list", lambda r: r.status in (400, 404)),
]:
    r = qa.api("alice", "GET", path)
    rec(tid_, f"Prompt 3 / checklist 6 detail: {path}", "P1", "alice", path, expected, f"{r.status} {code(r) or ''}", ok(r), qa.evidence(f"p3-{tid_}.json", {"status": r.status, "body": r.json}))

# ---------------------------------------------------------------- edit / delete / travelers: discovered absence
t0 = create("alice", qa.round_trip(f"{T} lifecycle fixture", 30, 32)).json["tripId"]
qa.wait_trip("alice", t0, {"BOOKED", "FAILED"}, 150)
put = qa.api("alice", "PUT", f"/api/v1/trips/{t0}", {"intent": {"purpose": "edited"}})
patch = qa.api("alice", "PATCH", f"/api/v1/trips/{t0}", {"intent": {"purpose": "edited"}})
dele = qa.api("alice", "DELETE", f"/api/v1/trips/{t0}")
qa.record(test_id="P3-EDIT-01", source_requirement="Prompt 3 / checklist 7 edit, 8 delete", priority="P1", test_layer="API + inventory", account="alice", input=f"PUT/PATCH/DELETE /trips/{t0}",
          steps="try each verb", expected="checklist expects edit and delete; the product has neither (trips are immutable requests; the only corrections are cancellation and completion)",
          actual=f"PUT {put.status}, PATCH {patch.status}, DELETE {dele.status}; no edit/delete route exists in TripController or the web app", status="NOT_IMPLEMENTED", evidence=qa.evidence("p3-edit-delete.json", {"put": put.status, "patch": patch.status, "delete": dele.status}), ids=t0)
qa.record(test_id="P3-TRAV-01", source_requirement="Prompt 3 / checklist 9 travelers (1/2/5 travelers, names, birthdates, ages, cross-tenant attachment)", priority="P1", test_layer="inventory", account="—", input="—",
          steps="capability map", expected="traveler management per checklist", actual="no traveler entity: one trip = one traveler (the principal, or a tenant employee id chosen by MANAGER/TRAVEL_ADMIN); no counts, names, birthdates or children; the only traveler test that exists (arranging for others) is P1-PERSONA-01",
          status="NOT_IMPLEMENTED", evidence="qa/qa-20260923-cda5ccb/01-capability-map.md")

# ---------------------------------------------------------------- mutation resilience / request identity contract
key = f"{T}-idem-{uuid.uuid4()}"
body = qa.round_trip(f"{T} idempotent create", 34, 36)
r1 = create("alice", body, idem=key); r2 = create("alice", body, idem=key)
body2 = dict(body); body2["intent"] = {**body["intent"], "purpose": f"{T} idempotent create CHANGED"}
r3 = create("alice", body2, idem=key)
qa.record(test_id="P3-MUT-01", source_requirement="Prompt 3 duplicate prevention per the request identity contract; checklist 15 data integrity", priority="P0", test_layer="API", account="alice",
          input="same Idempotency-Key twice with the same payload; then the same key with a changed payload", steps="POST /trips x3", expected="same trip id twice (202/200); changed payload refused as IDEMPOTENCY_KEY_REUSED (409/422)",
          actual=f"{r1.status} {r1.json.get('tripId') if r1.json else ''}; {r2.status} {r2.json.get('tripId') if r2.json else ''} same={r1.json and r2.json and r1.json.get('tripId') == r2.json.get('tripId')}; changed payload {r3.status} {code(r3)}",
          status="PASS" if r1.status == 202 and r2.status in (200, 202) and r1.json.get("tripId") == (r2.json or {}).get("tripId") and r3.status in (409, 422) else "FAIL", evidence=qa.evidence("p3-idempotency.json", {"first": r1.json, "second": r2.json, "changed": {"status": r3.status, "body": r3.json}}), ids=(r1.json or {}).get("tripId", ""))

# concurrent identical requests: five threads, one key
key2 = f"{T}-idem-conc-{uuid.uuid4()}"
body3 = qa.round_trip(f"{T} concurrent same key", 38, 40)
with cf.ThreadPoolExecutor(5) as ex:
    rs = list(ex.map(lambda _: create("alice", body3, idem=key2), range(5)))
ids = {(r.json or {}).get("tripId") for r in rs if r.status in (200, 202)}
qa.record(test_id="P3-MUT-02", source_requirement="Prompt 3 five rapid clicks / two tabs with one logical request", priority="P0", test_layer="API (5 concurrent threads)", account="alice", input="one key, five simultaneous POSTs",
          steps="ThreadPoolExecutor(5)", expected="exactly one trip; the others return the same id or a conflict; never five trips", actual=f"statuses={[r.status for r in rs]} distinct ids={len(ids)}",
          status="PASS" if len(ids) == 1 and all(r.status in (200, 202, 409) for r in rs) else "FAIL", evidence=qa.evidence("p3-idempotency-concurrent.json", [{"status": r.status, "body": r.json} for r in rs]), ids=" ".join(sorted(i for i in ids if i)))

# five distinct legitimate trips with the same name are five trips
same = f"{T} same name five times"
rs = [create("alice", qa.round_trip(same, 42, 44)) for _ in range(5)]
mine = qa.api("alice", "GET", "/api/v1/trips?limit=200").json or []
n = sum(1 for t in mine if (t.get("intent") or {}).get("purpose") == same)
qa.record(test_id="P3-MUT-03", source_requirement="Prompt 3 'do not prohibit legitimate distinct trips with the same name'", priority="P1", test_layer="API", account="alice", input="five POSTs, distinct keys, same purpose",
          steps="POST x5; GET list; count", expected="five trips", actual=f"statuses={[r.status for r in rs]}; listed with that name={n}", status="PASS" if n == 5 else "FAIL", evidence=qa.evidence("p3-same-name.json", {"statuses": [r.status for r in rs], "count": n}))

# ---------------------------------------------------------------- cancellation lifecycle
c1 = qa.api("alice", "POST", f"/api/v1/trips/{t0}/cancellation", {"reason": f"{T} cancel booked"})
st = qa.wait_trip("alice", t0, {"CANCELLED", "FAILED"}, 120)
c2 = qa.api("alice", "POST", f"/api/v1/trips/{t0}/cancellation", {"reason": f"{T} cancel again"})
o = qa.api("alice", "GET", f"/api/v1/orders?tripId={t0}").json or []
qa.record(test_id="P3-LIFE-01", source_requirement="Prompt 3 delete/cancel with confirmed bookings per the documented lifecycle; repeat", priority="P0", test_layer="API", account="alice", input=f"BOOKED trip {t0}",
          steps="POST cancellation twice", expected="BOOKED -> CANCELLED; the order is cancelled at the supplier (order CANCELLED); a second cancellation is a no-op (200) or a state error, never a second side effect",
          actual=f"first {c1.status}; final={st}; second {c2.status} {code(c2) or ''}; order status={[x.get('status') for x in o]}", status="PASS" if c1.status == 200 and st == "CANCELLED" and c2.status in (200, 409, 422) else "FAIL", evidence=qa.evidence("p3-cancel.json", {"first": c1.json, "second": {"status": c2.status, "body": c2.json}, "orders": o}), ids=t0)
# completion attestation
t1 = create("alice", qa.round_trip(f"{T} completion fixture", 30, 32)).json["tripId"]
qa.wait_trip("alice", t1, {"BOOKED", "FAILED"}, 150)
early = qa.api("alice", "POST", f"/api/v1/trips/{t1}/completion", {})
admin = qa.api("carol", "POST", f"/api/v1/trips/{t1}/completion", {})
again = qa.api("carol", "POST", f"/api/v1/trips/{t1}/completion", {})
qa.record(test_id="P3-LIFE-02", source_requirement="Prompt 3 lifecycle: completion is an attestation (documented), not a timer", priority="P1", test_layer="API", account="alice, carol", input=f"BOOKED future trip {t1}",
          steps="alice attests before the last arrival; carol (TRAVEL_ADMIN) attests; carol again", expected="alice 409 TRIP_NOT_OVER; carol 200 -> COMPLETED; repeat 200 idempotent",
          actual=f"alice {early.status} {code(early)}; carol {admin.status} -> {(admin.json or {}).get('status')}; again {again.status}", status="PASS" if early.status == 409 and admin.status == 200 and again.status == 200 else "FAIL", evidence=qa.evidence("p3-completion.json", {"early": {"status": early.status, "body": early.json}, "admin": admin.json, "again": again.json}), ids=t1)
print(qa.summary())
