"""Targeted regressions for the defects fixed after the qa-20260923-cda5ccb audit.

Run with `--phase before` against the audited images (cda5ccb) to reproduce each finding, then with
`--phase after` against the repaired images. Every row is a new row in this run's results.csv; the
original audit's ledger and evidence are not touched. Rows are named <bug>-<n>[-before|-after].

Supplier state is read from the sandbox supplier gateway's own tables (read-only), never inferred
from the application's answer alone.
"""

import concurrent.futures as cf
import datetime as dt
import json
import subprocess
import sys
import time
import urllib.request
import uuid
import zoneinfo

import qa

PHASE = "after"
for i, a in enumerate(sys.argv):
    if a == "--phase" and i + 1 < len(sys.argv):
        PHASE = sys.argv[i + 1]
T = f"{qa.TAG} {PHASE}"
SUFFIX = f"-{PHASE}"


def psql(db, sql):
    return subprocess.run(["docker", "exec", "travelos-postgres-1", "psql", "-U", "travelos", "-d", db, "-tAc", sql], capture_output=True, text=True, timeout=60).stdout.strip()


def supplier_state(order):
    """The supplier's own view of every item of an order: (provider, external ref, status)."""
    out = []
    for it in order.get("items", []):
        ref = it.get("externalRef") or it.get("supplierReference") or ""
        if not ref:
            out.append((it.get("provider"), "", "NO-REF"))
            continue
        st = psql("supplier_gateway", f"select status from sandbox_order where external_order_id='{ref}'") or psql("supplier_gateway", f"select status from sandbox_booking where booking_id='{ref}' or confirmation='{ref}'")
        out.append((it.get("provider"), ref, st or "UNKNOWN"))
    return out


def orders(user, tid):
    return qa.api(user, "GET", f"/api/v1/orders?tripId={tid}").json or []


def code(r):
    return (r.json or {}).get("code") if isinstance(r.json, dict) else None


def rec(test_id, req, prio, layer, account, inp, steps, expected, actual, ok, ev, ids="", bug=""):
    qa.record(test_id=f"{test_id}{SUFFIX}", source_requirement=req, priority=prio, test_layer=layer, account=account, input=inp, steps=steps, expected=expected, actual=actual,
              status="PASS" if ok else "FAIL", evidence=ev, ids=ids, bug_id=bug if not ok else "")


def create(user, body, **kw):
    return qa.api(user, "POST", "/api/v1/trips", body, **kw)


# ---------------------------------------------------------------- BUG-08: cancelling a booked trip
r = create("alice", qa.round_trip(f"{T} BUG-08 booked cancel", 30, 32)); tid = r.json["tripId"]
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED"}, 180)
before_orders = orders("alice", tid)
before_supplier = supplier_state(before_orders[0]) if before_orders else []
c1 = qa.api("alice", "POST", f"/api/v1/trips/{tid}/cancellation", {"reason": f"{T} meeting moved to video"})
seen = [(c1.json or {}).get("status")]
final = qa.poll(lambda: qa.trip("alice", tid), lambda t: t is not None and t.get("status") in ("CANCELLED", "FAILED", "BOOKED"), 150)
seen.append((final or {}).get("status"))
time.sleep(3)
after_orders = orders("alice", tid)
after_supplier = supplier_state(after_orders[0]) if after_orders else []
c2 = qa.api("alice", "POST", f"/api/v1/trips/{tid}/cancellation", {"reason": f"{T} again"})
hist = qa.api("alice", "GET", f"/api/v1/trips/{tid}/history").json or []
ledger = qa.api("alice", "GET", f"/api/v1/audit/trips/{tid}/decisions").json or {}
order_cancelled = bool(after_orders) and all(o.get("status") == "CANCELLED" for o in after_orders)
supplier_cancelled = bool(after_supplier) and all(s[2] == "CANCELLED" for s in after_supplier)
ok = st == "BOOKED" and c1.status == 200 and seen[0] in ("CANCELLING", "CANCELLED") and seen[-1] == "CANCELLED" and order_cancelled and supplier_cancelled and c2.status == 200 and (c2.json or {}).get("status") == "CANCELLED" and ledger.get("status") == "CANCELLED"
rec("BUG-08-1", "BUG-08 / P3-LIFE-01b: cancelling a BOOKED trip must release the reservation before the trip reads CANCELLED (application, order and supplier state agree)", "P0", "API + workflow + supplier DB (read-only)", "alice",
    f"BOOKED trip {tid}", "POST cancellation; poll trip; read orders; read sandbox_order/sandbox_booking status; POST cancellation again; audit ledger",
    "200 with CANCELLING (or CANCELLED if already released); final CANCELLED only with every order CANCELLED and every supplier record CANCELLED; second POST 200 idempotent; ledger status CANCELLED",
    f"booked={st}; cancel {c1.status} -> {seen[0]}; final={seen[-1]}; orders={[o.get('status') for o in after_orders]}; supplier before={before_supplier} after={after_supplier}; again {c2.status} {(c2.json or {}).get('status')}; ledger={ledger.get('status')}; history={[h.get('to') or h.get('toStatus') for h in hist]}",
    ok, qa.evidence(f"bug08-booked-cancel{SUFFIX}.json", {"first": c1.json, "final": final, "orders_before": before_orders, "orders_after": after_orders, "supplier_before": before_supplier, "supplier_after": after_supplier, "again": {"status": c2.status, "body": c2.json}, "history": hist, "ledger_status": ledger.get("status")}), tid, "BUG-08")

# a supplier that refuses: the LAX hotel fixture (non-refundable) — the trip must NOT read CANCELLED
body = qa.itinerary(f"{T} BUG-08 refused release", [("BOS", "LAX", 34), ("LAX", "BOS", 35)], [("LAX", 34, 35)])
r = create("alice", body); tid2 = (r.json or {}).get("tripId", "")
st2 = qa.wait_trip("alice", tid2, {"BOOKED", "FAILED"}, 240) if tid2 else f"REJECTED-{r.status}"
if st2 == "BOOKED":
    c = qa.api("alice", "POST", f"/api/v1/trips/{tid2}/cancellation", {"reason": f"{T} conference cancelled"})
    t = qa.poll(lambda: qa.trip("alice", tid2), lambda t: t is not None and (t.get("failureCode") or t.get("status") in ("CANCELLED", "FAILED")), 150) or {}
    o = orders("alice", tid2)
    sup = supplier_state(o[0]) if o else []
    exposures = [e for e in (o[:1] or [{}])[0].get("exposures", []) if e.get("status") == "OPEN"]
    hotel_confirmed_at_supplier = any(s[0] == "sandbox-hotel" and s[2] in ("CONFIRMED", "CHANGED") for s in sup)
    ok2 = c.status == 200 and t.get("status") == "CANCELLING" and t.get("failureCode") == "CANCELLATION_INCOMPLETE" and (o[:1] or [{}])[0].get("status") == "CANCELLATION_PENDING" and len(exposures) == 1 and hotel_confirmed_at_supplier
    actual2 = f"cancel {c.status}; trip={t.get('status')}/{t.get('failureCode')}; order={(o[:1] or [{}])[0].get('status')} items={[(i.get('type'), i.get('status')) for i in (o[:1] or [{}])[0].get('items', [])]}; open exposures={len(exposures)}; supplier={sup}"
    ev = {"cancel": c.json, "trip": t, "orders": o, "supplier": sup}
    if ok2:
        # Finance resolves the exposure: only now CANCELLED
        eid = exposures[0]["exposureId"]; oid = o[0]["orderId"]
        res = qa.api("carol", "POST", f"/api/v1/orders/{oid}/exposures/{eid}/resolution", {"resolution": f"{T} released by phone with the property"})
        t3 = qa.poll(lambda: qa.trip("alice", tid2), lambda t: t is not None and t.get("status") == "CANCELLED", 200) or {}
        o3 = orders("alice", tid2)
        ok3 = res.status == 200 and t3.get("status") == "CANCELLED" and (o3[:1] or [{}])[0].get("status") == "CANCELLED" and not t3.get("failureCode")
        rec("BUG-08-3", "BUG-08: a refused release is finished by a person (exposure resolution); the trip is CANCELLED only then", "P0", "API + workflow", "carol (FINANCE)", f"{tid2} exposure {eid}",
            "POST resolution; poll trip; read order", "200; trip CANCELLED with no failure code; order CANCELLED", f"resolve {res.status}; trip={t3.get('status')}/{t3.get('failureCode')}; order={(o3[:1] or [{}])[0].get('status')}", ok3,
            qa.evidence(f"bug08-refused-resolved{SUFFIX}.json", {"resolve": {"status": res.status, "body": res.json}, "trip": t3, "orders": o3}), tid2, "BUG-08")
else:
    ok2 = False; actual2 = f"fixture trip ended {st2} ({code(r)}); cannot test the refused release"; ev = {"create": {"status": r.status, "body": r.json}}
rec("BUG-08-2", "BUG-08: a supplier that refuses the release (LAX non-refundable hotel) keeps the trip CANCELLING with CANCELLATION_INCOMPLETE and an open exposure; the reservation is still CONFIRMED at the supplier and the trip never reads CANCELLED", "P0", "API + workflow + supplier DB (read-only)", "alice",
    f"BOS-LAX-BOS with a LAX stay ({tid2})", "book; POST cancellation; poll; read order, exposures and sandbox_booking", "200; trip CANCELLING/CANCELLATION_INCOMPLETE; order CANCELLATION_PENDING with the hotel CANCEL_FAILED and one OPEN exposure; hotel still CONFIRMED at the supplier",
    actual2, ok2, qa.evidence(f"bug08-refused{SUFFIX}.json", ev), tid2, "BUG-08")

# cancel while awaiting approval / approved: no order is ever created and the approval can no longer be decided
qa.seed_policy(lambda dd: dd["approval"].__setitem__("managerRequiredAbove", 1), "approval required")
try:
    r = create("alice", qa.round_trip(f"{T} BUG-08 withdrawn while awaiting approval", 36, 38)); tid4 = r.json["tripId"]
    st4 = qa.wait_trip("alice", tid4, {"AWAITING_APPROVAL", "FAILED"}, 120)
    c = qa.api("alice", "POST", f"/api/v1/trips/{tid4}/cancellation", {"reason": f"{T} withdrawn"})
    late = qa.api("bob", "POST", f"/api/v1/trips/{tid4}/approval", {"decision": "APPROVE", "comment": "late"})
    time.sleep(8)
    t4 = qa.trip("alice", tid4) or {}
    o4 = orders("alice", tid4)
    ok4 = st4 == "AWAITING_APPROVAL" and c.status == 200 and (c.json or {}).get("status") == "CANCELLED" and late.status in (409, 422) and t4.get("status") == "CANCELLED" and not o4
    rec("BUG-08-4", "BUG-08 (race) / P9-RACE-02: a trip withdrawn while awaiting approval is CANCELLED at once, a late approval is refused, and nothing is booked", "P0", "API + workflow", "alice, bob",
        tid4, "POST cancellation while AWAITING_APPROVAL; bob approves afterwards; read trip and orders", "cancel 200 CANCELLED; approval 409 TRIP_NOT_AWAITING_APPROVAL; no order",
        f"awaiting={st4}; cancel {c.status} {(c.json or {}).get('status')}; late approval {late.status} {code(late)}; final={t4.get('status')}; orders={len(o4)}", ok4,
        qa.evidence(f"bug08-withdrawn{SUFFIX}.json", {"cancel": c.json, "late": {"status": late.status, "body": late.json}, "final": t4, "orders": o4}), tid4, "BUG-08")
finally:
    qa.seed_policy(None, "restore")

# ---------------------------------------------------------------- BUG-01 / BUG-10: locations
for tid_, label, body, expect_hint in [
    ("BUG-01-1", "QQQ -> BOS (round trip)", qa.round_trip(f"{T} BUG-01 QQQ", 30, 32, origin="QQQ", dest="BOS"), "QQQ"),
    ("BUG-01-2", "BOS -> QQQ (round trip)", qa.round_trip(f"{T} BUG-01 to QQQ", 30, 32, origin="BOS", dest="QQQ"), "QQQ"),
    ("BUG-01-3", "NYC (city code) -> LAX (round trip)", qa.round_trip(f"{T} BUG-01 NYC", 30, 34, origin="NYC", dest="LAX"), "JFK, EWR, LGA"),
    ("BUG-01-4", "itinerary with a second leg to LON (city code)", qa.itinerary(f"{T} BUG-01 itinerary LON", [("BOS", "JFK", 30), ("JFK", "LON", 32)]), "LHR, LGW"),
    ("BUG-01-5", "itinerary leg to QQQ", qa.itinerary(f"{T} BUG-01 itinerary QQQ", [("BOS", "QQQ", 30)]), "QQQ"),
]:
    r = create("alice", body)
    ok = r.status == 422 and code(r) == "UNKNOWN_LOCATION" and expect_hint in str((r.json or {}).get("detail", ""))
    rec(tid_, f"BUG-01/BUG-10: {label} must be refused with UNKNOWN_LOCATION and an actionable hint (never booked)", "P0", "API", "alice", label, "POST /trips", f"422 UNKNOWN_LOCATION mentioning '{expect_hint}'",
        f"{r.status} {code(r) or ''} {str((r.json or {}).get('detail', ''))[:120]}", ok, qa.evidence(f"bug01-{tid_}{SUFFIX}.json", {"request": body, "status": r.status, "body": r.json}), (r.json or {}).get("tripId", "") if r.status == 202 else "", "BUG-01")
# the catalog covers the airports the audit found refused on the itinerary path (BUG-10)
body = qa.itinerary(f"{T} BUG-10 FCO/NRT/DXB/SIN itinerary", [("JFK", "FCO", 40), ("FCO", "DXB", 43), ("DXB", "SIN", 46), ("SIN", "NRT", 49), ("NRT", "JFK", 52)])
r = create("alice", body)
rec("BUG-10-1", "BUG-10: a multi-city itinerary through Rome, Dubai, Singapore and Tokyo is accepted by the catalog (round trips and itineraries share one table)", "P2", "API", "alice", "JFK-FCO-DXB-SIN-NRT-JFK", "POST /trips", "202 (accepted; booking outcome recorded separately)",
    f"{r.status} {code(r) or ''}", r.status == 202, qa.evidence(f"bug10-catalog{SUFFIX}.json", {"request": body, "status": r.status, "body": r.json}), (r.json or {}).get("tripId", "") if r.status == 202 else "", "BUG-10")

# ---------------------------------------------------------------- BUG-03: departures in the past (departure airport's clock)
NY = zoneinfo.ZoneInfo("America/New_York")
now_utc = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
def rt_at(purpose, dep: dt.datetime, deadline: dt.datetime, origin="BOS", dest="SEA"):
    b = {"source": "API", "intent": {"purpose": purpose, "origin": origin, "destination": dest, "earliestDeparture": dep.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z"), "arrivalDeadline": deadline.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z")}}
    return b
yesterday_ny = (now_utc.astimezone(NY) - dt.timedelta(days=1)).replace(hour=6, minute=0, second=0)
today_ny = now_utc.astimezone(NY)
cases = [
    ("BUG-03-1", "yesterday (Boston local)", rt_at(f"{T} BUG-03 yesterday", yesterday_ny, yesterday_ny.replace(hour=23, minute=59)), 422, "DEPARTURE_IN_PAST"),
    ("BUG-03-2", "2025-01-10 (P3-VAL-14)", qa.round_trip(f"{T} BUG-03 2025", 30, 32) | {"intent": {**qa.round_trip("x", 30, 32)["intent"], "purpose": f"{T} BUG-03 2025", "earliestDeparture": "2025-01-10T05:00:00Z", "arrivalDeadline": "2025-01-10T23:00:00Z", "returnAfter": "2025-01-12T10:00:00Z", "latestReturn": "2025-01-12T23:00:00Z"}}, 422, "DEPARTURE_IN_PAST"),
    ("BUG-03-3", "window closed one minute ago", rt_at(f"{T} BUG-03 just closed", now_utc - dt.timedelta(hours=5), now_utc - dt.timedelta(minutes=1)), 422, "DEPARTURE_IN_PAST"),
    ("BUG-03-4", "later today: window opened 4 h ago, closes in 6 h", rt_at(f"{T} BUG-03 later today", now_utc - dt.timedelta(hours=4), now_utc + dt.timedelta(hours=6)), 202, None),
    ("BUG-03-5", "midnight in Boston vs UTC: a window that closes at the coming Boston midnight is still open", rt_at(f"{T} BUG-03 boston midnight", today_ny.replace(hour=5, minute=0, second=0), (today_ny + dt.timedelta(days=1)).replace(hour=0, minute=0, second=0)), 202, None),
    ("BUG-03-6", "timezone west: a Honolulu departure whose window closes at 23:00 Honolulu today (already tomorrow in UTC)", rt_at(f"{T} BUG-03 HNL", now_utc.astimezone(zoneinfo.ZoneInfo("Pacific/Honolulu")).replace(hour=5, minute=0, second=0), now_utc.astimezone(zoneinfo.ZoneInfo("Pacific/Honolulu")).replace(hour=23, minute=0, second=0), origin="HNL", dest="LAX"), 202, None),
]
for tid_, label, body, want, want_code in cases:
    r = create("alice", body)
    ok = r.status == want and (want_code is None or code(r) == want_code)
    detail = str((r.json or {}).get("detail", ""))[:140]
    rec(tid_, f"BUG-03: {label}", "P0", "API", "alice", json.dumps(body["intent"])[:140], "POST /trips", f"{want} {want_code or ''}".strip() + ("; the message names the departure airport's local time" if want == 422 else ""),
        f"{r.status} {code(r) or ''} {detail}", ok, qa.evidence(f"bug03-{tid_}{SUFFIX}.json", {"request": body, "status": r.status, "body": r.json, "now_utc": now_utc.isoformat()}), (r.json or {}).get("tripId", "") if r.status == 202 else "", "BUG-03")
    if r.status == 202:
        qa.api("alice", "POST", f"/api/v1/trips/{r.json['tripId']}/cancellation", {"reason": f"{T} fixture cleanup"})

# ---------------------------------------------------------------- BUG-07: concurrent identical submissions
key = f"{qa.TAG}-{PHASE}-idem-conc-{uuid.uuid4()}"  # no spaces: a key is one token
body = qa.round_trip(f"{T} BUG-07 concurrent same key", 38, 40)
with cf.ThreadPoolExecutor(5) as ex:
    rs = list(ex.map(lambda _: create("alice", body, idem=key), range(5)))
ids = {(r.json or {}).get("tripId") for r in rs if r.status in (200, 202)}
ok = len(ids) == 1 and all(r.status in (200, 202) for r in rs)
rec("BUG-07-1", "BUG-07 / P3-MUT-02: five concurrent identical submissions answer with one logical trip and a documented success/replay status, never 500", "P1", "API (5 threads)", "alice", "one key, five simultaneous POSTs", "ThreadPoolExecutor(5)",
    "every answer 202 (or 200) with the same trip id; one row", f"statuses={[r.status for r in rs]} codes={[code(r) for r in rs]} distinct ids={len(ids)}", ok,
    qa.evidence(f"bug07-concurrent{SUFFIX}.json", [{"status": r.status, "body": r.json} for r in rs]), " ".join(sorted(i for i in ids if i)), "BUG-07")

# ---------------------------------------------------------------- BUG-05: unsupported currency and failure-code hygiene
r = create("alice", qa.itinerary(f"{T} BUG-05 currency XXX", [("BOS", "SEA", 30), ("SEA", "BOS", 33)], currency="XXX"))
ok = r.status == 422 and code(r) == "CURRENCY_UNSUPPORTED"
rec("BUG-05-1", "BUG-05 / P3-ITN-03: an unsupported currency (XXX, a valid ISO code with no money) is refused before planning with a stable code, never a Java exception name", "P1", "API", "alice", "itinerary currency=XXX", "POST /trips",
    "422 CURRENCY_UNSUPPORTED naming the supported currencies", f"{r.status} {code(r) or ''} {str((r.json or {}).get('detail', ''))[:120]}", ok, qa.evidence(f"bug05-xxx{SUFFIX}.json", {"status": r.status, "body": r.json}), (r.json or {}).get("tripId", "") if r.status == 202 else "", "BUG-05")
r = create("alice", qa.itinerary(f"{T} BUG-05 currency GBP", [("BOS", "LHR", 30), ("LHR", "BOS", 33)], currency="GBP"))
rec("BUG-05-2", "BUG-05: a currency no flight can be quoted in (GBP: the sandbox airline quotes USD only, nothing converts) is refused up front, never accepted and then failed at OPTIMIZATION", "P2", "API", "alice", "itinerary currency=GBP", "POST /trips", "422 CURRENCY_UNSUPPORTED naming USD", f"{r.status} {code(r) or ''} {str((r.json or {}).get('detail', ''))[:100]}", r.status == 422 and code(r) == "CURRENCY_UNSUPPORTED", qa.evidence(f"bug05-gbp{SUFFIX}.json", {"status": r.status, "body": r.json}), (r.json or {}).get("tripId", "") if r.status == 202 else "", "BUG-05")
# failure codes recorded in this run never carry a class name
# this run's trips only: rows written before the fix keep their historical codes on purpose
codes = json.loads(psql("travel_core", f"select coalesce(json_agg(distinct failure_code), '[]') from trip where failure_code is not null and purpose like '{qa.TAG}%'") or "[]")
bad = [c for c in codes if c and ("." in c or c.endswith("Exception") or c.endswith("Error"))]
rec("BUG-05-3", "BUG-05: no trip failure code recorded in this run is an exception class name (read-only DB scan of the run's trips; earlier trips keep their historical codes)", "P1", "DB read-only", "—", "select distinct failure_code from trip where purpose like '<run tag>%'", "psql", "no code containing '.' or ending in Exception/Error",
    f"codes={codes}; offending={bad}", not bad, qa.evidence(f"bug05-failure-codes{SUFFIX}.json", {"codes": codes, "offending": bad}), "", "BUG-05")

# ---------------------------------------------------------------- BUG-04: traveler identity for arranged trips
anon = qa.api("carol", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} BUG-04 carol arranges for alice (no identity)", 56, 58), "travelerId": "emp_1001"})
named = qa.api("carol", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} BUG-04 carol arranges for alice", 56, 58), "travelerId": "emp_1001", "traveler": {"givenName": "Alice", "familyName": "Nguyen", "email": "alice@acme.example"}})
ct = qa.trip("carol", (named.json or {}).get("tripId", "x")) or {}
snap = ct.get("traveler") or {}
ok = anon.status == 422 and code(anon) == "TRAVELER_IDENTITY_REQUIRED" and named.status == 202 and snap.get("givenName") == "Alice" and snap.get("email") == "alice@acme.example"
rec("BUG-04-1", "BUG-04 / P1-PERSONA-01b: a trip arranged for another traveler requires that traveler's identity; the stored snapshot carries it", "P1", "API", "carol (TRAVEL_ADMIN) for alice", "POST /trips travelerId=emp_1001 without, then with traveler{givenName,familyName,email}",
    "POST x2; GET trip", "422 TRAVELER_IDENTITY_REQUIRED; then 202 with traveler snapshot name/email", f"anonymous {anon.status} {code(anon) or ''}; named {named.status} snapshot={snap}", ok,
    qa.evidence(f"bug04-arranged{SUFFIX}.json", {"anonymous": {"status": anon.status, "body": anon.json}, "named": named.json, "trip": ct}), (named.json or {}).get("tripId", ""), "BUG-04")

# ---------------------------------------------------------------- BUG-06: list limits
lim = {q: qa.api("alice", "GET", f"/api/v1/trips?limit={q}") for q in ("0", "-1", "201", "200", "1")}
ok = all(lim[q].status == 422 and code(lim[q]) == "LIMIT_OUT_OF_RANGE" for q in ("0", "-1", "201")) and all(lim[q].status == 200 for q in ("200", "1"))
rec("BUG-06-1", "BUG-06 / P3-LIST-01..03: a limit outside 1..200 is refused (422 LIMIT_OUT_OF_RANGE), not silently clamped", "P2", "API", "alice", "limit=0,-1,201,200,1", "GET /trips", "422 for 0/-1/201; 200 for 200/1",
    {q: f"{r.status} {code(r) or ''}" for q, r in lim.items()}, ok, qa.evidence(f"bug06-limits{SUFFIX}.json", {q: {"status": r.status, "body": r.json if r.status != 200 else f"{len(r.json)} items"} for q, r in lim.items()}), "", "BUG-06")

# ---------------------------------------------------------------- BUG-11 / BUG-12: refunds
r = create("alice", qa.round_trip(f"{T} BUG-12 refund fixture", 30, 32)); tid5 = r.json["tripId"]
qa.wait_trip("alice", tid5, {"BOOKED", "FAILED"}, 180)
o5 = orders("alice", tid5)
oid5 = (o5[:1] or [{}])[0].get("orderId", "ord_x")
refunds = {}
for label, amt, cur in [("zero", 0, "USD"), ("one-cent", 1, "USD"), ("negative", -100, "USD"), ("eur-on-usd", 100, "EUR")]:
    refunds[label] = qa.api("carol", "POST", "/api/v1/learning/outcomes/refunds", {"tripId": tid5, "orderId": oid5, "amountMinor": amt, "currency": cur, "reference": f"{T}-{label}-{uuid.uuid4().hex[:6]}"})
ok = refunds["zero"].status == 200 and refunds["one-cent"].status == 200 and refunds["negative"].status in (400, 422) and refunds["eur-on-usd"].status == 422 and code(refunds["eur-on-usd"]) == "CURRENCY_MISMATCH"
rec("BUG-12-1", "BUG-11 (contract: zero is a legitimate settlement) and BUG-12 (a refund in another currency than the order's is refused): Finance refund recording", "P1", "API", "carol (FINANCE)", f"order {oid5}: 0 USD, 1 USD, -100 USD, 100 EUR",
    "POST /learning/outcomes/refunds x4", "zero 200 (REFUND_SETTLED, ledgered with amount 0); one cent 200; negative 400/422; EUR 422 CURRENCY_MISMATCH", {k: f"{v.status} {code(v) or ''}" for k, v in refunds.items()}, ok,
    qa.evidence(f"bug12-refunds{SUFFIX}.json", {k: {"status": v.status, "body": v.json} for k, v in refunds.items()}), f"{tid5} {oid5}", "BUG-12")

# ---------------------------------------------------------------- BUG-09: security headers on every edge response; BUG-14: outage problem documents
def hdrs(url, method="GET", token=None, timeout=75):
    req = urllib.request.Request(url, method=method, headers={"Authorization": f"Bearer {token}"} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode(errors="replace")[:400]
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode(errors="replace")[:400]
wanted = ("Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Permissions-Policy")
checks = {}
for path in ("/", "/trips/new", "/config.json", "/api/v1/trips?limit=1", "/api/v1/nope", "/assets/"):
    st_, h, _ = hdrs(f"{qa.BASE}{path}", token=qa.token("alice") if path.startswith("/api/v1/trips") else None)
    checks[path] = {"status": st_, **{k: h.get(k) for k in wanted}}
ok = all(all(v.get(k) for k in wanted) for v in checks.values())
rec("BUG-09-1", "BUG-09 / P11-HDR-01: CSP, nosniff, frame-options, referrer-policy and permissions-policy on every response the edge serves (shell, deep link, config, proxied API, unknown route, assets)", "P2", "HTTP", "alice/none", "GET six paths", "inspect headers",
    "every response carries all five headers", json.dumps(checks)[:700], ok, qa.evidence(f"bug09-headers{SUFFIX}.json", checks), "", "BUG-09")

if PHASE == "after" or "--outage" in sys.argv:
    subprocess.run(["docker", "stop", "travelos-travel-core-1"], capture_output=True, text=True, timeout=120)
    try:
        time.sleep(2)
        t_start = time.time()
        st_, h, body_ = hdrs(f"{qa.BASE}/api/v1/trips?limit=1", token=qa.token("alice"))
        took = round(time.time() - t_start, 1)
        try:
            doc = json.loads(body_)
        except json.JSONDecodeError:
            doc = None
        ok = st_ in (502, 503, 504) and (h.get("Content-Type") or "").startswith("application/problem+json") and isinstance(doc, dict) and doc.get("status") == st_ and doc.get("code") in ("UPSTREAM_UNAVAILABLE", "UPSTREAM_TIMEOUT") and h.get("Content-Security-Policy")
        rec("BUG-14-1", "BUG-14 / P12-OUT: an upstream outage answers a problem+json document with a stable code and the security headers, not nginx's HTML", "P2", "HTTP + container fault", "alice", "docker stop travel-core; GET /api/v1/trips via the edge", "stop; GET; start",
            "503 (or 502/504) application/problem+json {status, code UPSTREAM_UNAVAILABLE, detail}, within seconds", f"{st_} {h.get('Content-Type')} after {took}s {body_[:160]}", ok and took < 30, qa.evidence(f"bug14-outage{SUFFIX}.json", {"status": st_, "headers": h, "body": body_}), "", "BUG-14")
    finally:
        subprocess.run(["docker", "start", "travelos-travel-core-1"], capture_output=True, text=True, timeout=120)
        for _ in range(90):
            if subprocess.run(["docker", "inspect", "--format", "{{.State.Health.Status}}", "travelos-travel-core-1"], capture_output=True, text=True).stdout.strip() == "healthy":
                break
            time.sleep(2)
print(qa.summary())
