"""Prompt 4: the route/date matrix (R01–R30 plus the extra date categories) through the persisted
booking, with the outcome read from the API. Dates resolve from the execution clock in
America/New_York; the sandbox airline schedules in UTC by documented design."""

import concurrent.futures as cf
import datetime as dt
import json
import zoneinfo

import qa

T = qa.TAG
NY = zoneinfo.ZoneInfo("America/New_York")
today = dt.datetime.now(NY).date()  # 2026-09-22 local at execution


def d(date) -> str:
    """ISO date from a date or a day offset from today."""
    if isinstance(date, int):
        date = today + dt.timedelta(days=date)
    return date.isoformat()


def plus(days: int) -> dt.date:
    return today + dt.timedelta(days=days)


def rt(purpose, o, de, out: dt.date, back: dt.date | None, **intent):
    body = {"source": "API", "intent": {"purpose": purpose, "origin": o, "destination": de,
            "earliestDeparture": f"{d(out)}T05:00:00Z", "arrivalDeadline": f"{d(out)}T23:59:00Z"}}
    if back:
        body["intent"]["returnAfter"] = f"{d(back)}T10:00:00Z"
        body["intent"]["latestReturn"] = f"{d(back)}T23:00:00Z"
    # a case's explicit windows win over the defaults (the audit's R04 lost its 15:00 return to the
    # default 10:00 and was refused as return-before-arrival: a harness defect, fixed here)
    body["intent"].update(intent)
    return body


def itin(purpose, legs, stays=(), currency="USD"):
    return {"source": "API", "intent": {"purpose": purpose, "itinerary": {"currency": currency,
            "legs": [{"origin": o, "destination": de, "earliestDeparture": f"{d(day)}T05:00:00Z", "arrivalDeadline": f"{d(day)}T23:59:00Z"} for o, de, day in legs],
            "stays": [{"city": c, "checkInDate": d(i), "checkOutDate": d(o), "required": True} for c, i, o in stays], "transfers": []}}}


next_friday = today + dt.timedelta(days=(4 - today.weekday()) % 7 or 7)
next_month_first = (today.replace(day=1) + dt.timedelta(days=32)).replace(day=1)
dec20, jan3 = dt.date(2026, 12, 20), dt.date(2027, 1, 3)
CASES = [
    # id, label, body, expectation text, evaluator(status, final trip, components) -> bool, note
    ("R01", "Bangor -> Boston, four days", rt(f"{T} R01", "BGR", "BOS", plus(20), plus(24)), "BOOKED (sandbox generates BGR inventory)", lambda s, t, c: s == "BOOKED"),
    ("R02", "New York -> Los Angeles, seven days", rt(f"{T} R02", "JFK", "LAX", plus(21), plus(28)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R03", "San Jose -> Seattle, one night with a stay", itin(f"{T} R03", [("SJC", "SEA", 22), ("SEA", "SJC", 23)], [("SEA", 22, 23)]), "BOOKED with a 1-night hotel", lambda s, t, c: s == "BOOKED" and any(x.get("type") == "HOTEL" and x.get("status") == "CONFIRMED" for x in c)),
    ("R04", "Boston -> New York, same day", rt(f"{T} R04", "BOS", "JFK", plus(23), plus(23), arrivalDeadline=f"{d(plus(23))}T12:00:00Z", returnAfter=f"{d(plus(23))}T15:00:00Z", latestReturn=f"{d(plus(23))}T23:59:00Z"), "BOOKED (return after the outbound on the same day)", lambda s, t, c: s == "BOOKED"),
    ("R05", "Miami -> Chicago, Dec 20 2026 -> Jan 3 2027", rt(f"{T} R05", "MIA", "ORD", dec20, jan3), "BOOKED; both years persisted", lambda s, t, c: s == "BOOKED" and t.get("intent", {}).get("latestReturn", "").startswith("2027-01-03")),
    ("R06", "Los Angeles -> Tokyo, ten days", rt(f"{T} R06", "LAX", "NRT", plus(30), plus(40)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R07", "New York -> London, seven days", rt(f"{T} R07", "JFK", "LHR", plus(31), plus(38)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R08", "San Francisco -> Delhi, fifteen days", rt(f"{T} R08", "SFO", "DEL", plus(32), plus(47)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R09", "Boston -> Paris with a Paris stay (currency display)", itin(f"{T} R09", [("BOS", "CDG", 33), ("CDG", "BOS", 36)], [("CDG", 33, 36)]), "BOOKED in USD (sandbox catalog default); currency explicit on the total", lambda s, t, c: s == "BOOKED" and (t.get("total") or {}).get("currency") == "USD"),
    ("R09b", "Boston -> London with a London stay (GBP catalog)", itin(f"{T} R09b", [("BOS", "LHR", 33), ("LHR", "BOS", 36)], [("LHR", 33, 36)]), "documented: mixed currencies are denied explicitly (FAILED with a currency reason), never summed", lambda s, t, c: s == "FAILED" and bool(t.get("failureCode"))),
    ("R10", "Seattle -> Sydney, Date Line", rt(f"{T} R10", "SEA", "SYD", plus(34), plus(41)), "BOOKED; arrival instant after departure instant (checked below)", lambda s, t, c: s == "BOOKED"),
    ("R11", "Bangor -> Portland ME, tomorrow -> two days later (America/New_York)", rt(f"{T} R11", "BGR", "PWM", plus(1), plus(3)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R12", "Bangor -> Orlando, six months ahead", rt(f"{T} R12", "BGR", "MCO", plus(182), plus(186)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R13", "New York -> Tokyo, eleven months ahead (horizon)", rt(f"{T} R13", "JFK", "NRT", plus(335), plus(345)), "no documented horizon: either BOOKED or a documented refusal; recorded", lambda s, t, c: s in ("BOOKED", "FAILED")),
    ("R14", "Boston -> London, Feb 28 -> Mar 2 2027 (non-leap)", rt(f"{T} R14", "BOS", "LHR", dt.date(2027, 2, 28), dt.date(2027, 3, 2)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R15", "New York -> Paris, Feb 28 -> Mar 1 2028 with a stay (includes Feb 29)", itin(f"{T} R15", [("JFK", "CDG", (dt.date(2028, 2, 28) - today).days), ("CDG", "JFK", (dt.date(2028, 3, 1) - today).days)], [("CDG", (dt.date(2028, 2, 28) - today).days, (dt.date(2028, 3, 1) - today).days)]), "BOOKED; the stay is 2 nights (Feb 28, Feb 29)", lambda s, t, c: s == "BOOKED" and any("2 night" in (x.get("summary") or "") for x in c)),
    ("R16", "New York -> Chicago across US DST start (Mar 14 2027) with a stay", itin(f"{T} R16", [("JFK", "ORD", (dt.date(2027, 3, 13) - today).days), ("ORD", "JFK", (dt.date(2027, 3, 15) - today).days)], [("ORD", (dt.date(2027, 3, 13) - today).days, (dt.date(2027, 3, 15) - today).days)]), "BOOKED; hotel local dates preserved across the DST change (America/Chicago)", lambda s, t, c: s == "BOOKED" and any(x.get("type") == "HOTEL" and "2027-03-13" in (x.get("summary") or "") for x in c)),
    ("R17", "New York -> Los Angeles across US DST end (Nov 1 2026) with a stay", itin(f"{T} R17", [("JFK", "LAX", (dt.date(2026, 10, 31) - today).days), ("LAX", "JFK", (dt.date(2026, 11, 2) - today).days)], [("LAX", (dt.date(2026, 10, 31) - today).days, (dt.date(2026, 11, 2) - today).days)]), "BOOKED; LAX cheapest hotel refuses cancellation only on cancel (fixture), booking fine", lambda s, t, c: s == "BOOKED"),
    ("R18", "Bangor -> Bangor (same origin/destination)", rt(f"{T} R18", "BGR", "BGR", plus(20), plus(22)), "validation error (422)", lambda s, t, c: s == "REJECTED-422"),
    ("R19", "東京 -> 東京 (Unicode city, not a code)", rt(f"{T} R19", "東京", "東京", plus(20), plus(22)), "422 (three-letter codes only; city names are not accepted by the structured API)", lambda s, t, c: s == "REJECTED-422"),
    ("R20", "Invalid origin QQQ -> Boston", rt(f"{T} R20", "QQQ", "BOS", plus(20), plus(22)), "validation error or honest no-results; never a booking", lambda s, t, c: s == "REJECTED-422" or (s == "FAILED")),
    ("R21", "Boston -> invalid destination QQQ", rt(f"{T} R21", "BOS", "QQQ", plus(20), plus(22)), "validation error or honest no-results; never a booking", lambda s, t, c: s == "REJECTED-422" or (s == "FAILED")),
    ("R22", "NYC (metro code) -> LAX", rt(f"{T} R22", "NYC", "LAX", plus(20), plus(24)), "documented: airport codes; a metro code should be refused or mapped, never booked as an airport", lambda s, t, c: s == "REJECTED-422"),
    ("R23", "JFK -> LHR exact airports", rt(f"{T} R23", "JFK", "LHR", plus(25), plus(30)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R24", "New York -> London, return before departure", rt(f"{T} R24", "JFK", "LHR", plus(30), plus(25)), "422", lambda s, t, c: s == "REJECTED-422"),
    ("R25", "New York -> London, missing dates", {"source": "API", "intent": {"purpose": f"{T} R25", "origin": "JFK", "destination": "LHR"}}, "422", lambda s, t, c: s == "REJECTED-422"),
    ("R26", "New York -> London, past dates (2026-01-10)", rt(f"{T} R26", "JFK", "LHR", dt.date(2026, 1, 10), dt.date(2026, 1, 14)), "422 (past); accepting it is a validation gap", lambda s, t, c: s == "REJECTED-422"),
    ("R27", "New York -> London, one-year duration", rt(f"{T} R27", "JFK", "LHR", plus(30), plus(395)), "no documented duration limit: recorded", lambda s, t, c: s in ("BOOKED", "FAILED", "REJECTED-422")),
    ("R28", "Honolulu -> Auckland, Dec 30 2026 -> Jan 5 2027 + Date Line", rt(f"{T} R28", "HNL", "AKL", dt.date(2026, 12, 30), dt.date(2027, 1, 5)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R29", "Anchorage -> Miami", rt(f"{T} R29", "ANC", "MIA", plus(26), plus(31)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("R30", "Dubai -> Singapore (neither in the US)", rt(f"{T} R30", "DXB", "SIN", plus(27), plus(33)), "BOOKED (policy: international cabins)", lambda s, t, c: s == "BOOKED"),
    ("D01", "+2 days", rt(f"{T} D01", "BOS", "SEA", plus(2), plus(4)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("D02", "next Friday", rt(f"{T} D02", "BOS", "SEA", next_friday, next_friday + dt.timedelta(days=2)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("D03", "first of next month (month boundary Sep 30 -> Oct 1)", rt(f"{T} D03", "BOS", "SEA", dt.date(2026, 9, 30), dt.date(2026, 10, 1)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("D04", "30-day trip", rt(f"{T} D04", "BOS", "SEA", plus(40), plus(70)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("D05", "90-day trip", rt(f"{T} D05", "BOS", "SEA", plus(40), plus(130)), "BOOKED", lambda s, t, c: s == "BOOKED"),
    ("D06", "invalid Feb 29 2027", {"source": "API", "intent": {"purpose": f"{T} D06", "origin": "BOS", "destination": "SEA", "earliestDeparture": "2027-02-29T05:00:00Z", "arrivalDeadline": "2027-02-29T23:00:00Z"}}, "400/422 (not a date)", lambda s, t, c: s == "REJECTED-422"),
    ("D07", "five years ahead (beyond any horizon)", rt(f"{T} D07", "BOS", "SEA", plus(1826), plus(1830)), "no documented horizon: recorded", lambda s, t, c: s in ("BOOKED", "FAILED", "REJECTED-422")),
]


def run(case):
    cid, label, body, expected, ok = case
    r = qa.api("alice", "POST", "/api/v1/trips", body)
    if r.status != 202:
        s = "REJECTED-422" if r.status in (400, 422) else f"REJECTED-{r.status}"
        return cid, label, expected, s, r, None, [], ok(s, {}, [])
    tid = r.json["tripId"]
    s = qa.wait_trip("alice", tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 240)
    t = qa.trip("alice", tid) or {}
    c = qa.api("alice", "GET", f"/api/v1/trips/{tid}/components").json or []
    return cid, label, expected, s, r, t, c, ok(s, t, c)


import os
if os.environ.get("P4_ONLY"):
    only = set(os.environ["P4_ONLY"].split(","))
    CASES = [c for c in CASES if c[0] in only]
with cf.ThreadPoolExecutor(4) as ex:
    results = list(ex.map(run, CASES))

for cid, label, expected, s, r, t, c, ok in results:
    tid = (r.json or {}).get("tripId", "") if r.status == 202 else ""
    extra = ""
    if t:
        extra = f" {t.get('failureStage') or ''}/{t.get('failureCode') or ''} total={t.get('total')}"
    body_code = (r.json or {}).get("code") if isinstance(r.json, dict) and r.status != 202 else ""
    status = "PASS" if ok else "FAIL"
    bug = ""
    if cid in ("R18", "R20", "R21", "R22", "R26") and not ok:
        bug = "BUG-01" if cid in ("R20", "R21", "R22") else ("BUG-02" if cid == "R18" else "BUG-03")
    qa.record(test_id=f"P4-{cid}", source_requirement=f"Prompt 4 route/date matrix {cid}: {label}", priority="P1" if cid.startswith("R") else "P2", test_layer="API + workflow (persisted booking)", account="alice/acme",
              input=json.dumps(CASES[[x[0] for x in CASES].index(cid)][2]["intent"])[:160], steps="POST /trips; poll to a terminal state (240 s); GET trip + components",
              expected=expected, actual=f"{r.status} {body_code or ''} -> {s}{extra}", status=status, evidence=qa.evidence(f"p4-{cid}.json", {"request": CASES[[x[0] for x in CASES].index(cid)][2], "create": {"status": r.status, "body": r.json}, "final": t, "components": c}), ids=tid, bug_id=bug)

# ordering by instants for the Date Line and overnight cases
for cid in ("R10", "R28", "R06"):
    if not any(x[0] == cid for x in results):
        continue
    row = next(x for x in results if x[0] == cid)
    t = row[5] or {}
    tid = (row[4].json or {}).get("tripId", "")
    o = (qa.api("alice", "GET", f"/api/v1/orders?tripId={tid}").json or [{}])[0] if tid else {}
    flights = [f for it in o.get("items", []) for f in it.get("flights", [])]
    okk = bool(flights) and all(f.get("departure") < f.get("arrival") for f in flights if f.get("departure") and f.get("arrival"))
    qa.record(test_id=f"P4-{cid}-ORDER", source_requirement="Prompt 4 ordering by instants and zone data (Date Line / overnight)", priority="P1", test_layer="API", account="alice", input=f"{cid} order flights",
              steps="GET /orders?tripId; compare departure/arrival instants", expected="every flight's arrival instant is after its departure instant; local arrival date may differ (UTC schedule documented)",
              actual=f"{len(flights)} flights; instants ordered={okk}; sample={[(f.get('flightNumber'), f.get('departure'), f.get('arrival')) for f in flights[:2]]}", status="PASS" if okk else ("BLOCKED" if not flights else "FAIL"), evidence=qa.evidence(f"p4-{cid}-order.json", o), ids=tid)
print(qa.summary())
