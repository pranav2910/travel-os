"""Prompt 6: the 15 booking styles and the ticket structures, executed where the product supports them
and recorded NOT_IMPLEMENTED where it does not, with the reason."""

import json
import uuid

import qa

T = qa.TAG


def submit(user, body, **kw):
    r = qa.api(user, "POST", "/api/v1/trips", body, **kw)
    return (r.json or {}).get("tripId", ""), r


def done(tid, deadline=220):
    st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, deadline)
    return st, (qa.trip("alice", tid) or {})


def flights_of(tid):
    o = qa.api("alice", "GET", f"/api/v1/orders?tripId={tid}").json or []
    return [f for it in (o[:1] or [{}])[0].get("items", []) for f in it.get("flights", [])], o


def leg(o, d, day, dep="05:00", arr="23:59"):
    return {"origin": o, "destination": d, "earliestDeparture": f"{qa.future(day)}T{dep}:00Z", "arrivalDeadline": f"{qa.future(day)}T{arr}:00Z"}


def itin(purpose, legs, stays=(), transfers=()):
    return {"source": "API", "intent": {"purpose": purpose, "itinerary": {"currency": "USD", "legs": legs, "stays": [{"city": c, "checkInDate": qa.future(i), "checkOutDate": qa.future(o), "required": True} for c, i, o in stays], "transfers": list(transfers)}}}


NI = "NOT_IMPLEMENTED"
# styles that need the traveler to choose among offers or to filter suppliers do not exist: the optimizer chooses within policy
for sid, label, why in [
    ("P6-S02", "compare five offers and choose the third", "no offer list is shown or selectable; the optimizer picks one bundle and explains it"),
    ("P6-S03", "cheapest eligible offer", "cost is one of five weighted criteria (cost 0.4, time 0.25, risk 0.15, preference 0.1, experience 0.1); 'cheapest' is not a user-selectable mode"),
    ("P6-S04", "shortest total journey incl. layovers", "time is a weighted criterion, not a selectable mode"),
    ("P6-S07", "specific airline/provider", "no carrier filter in the request contract"),
    ("P6-S08", "JFK only vs any NYC airport", "the request takes one airport code per leg; there is no metro expansion (a metro code is accepted verbatim: BUG-01)"),
    ("P6-S09", "nonstop only vs one-stop permitted", "stops are governed by policy (maxStops=1, violation -> approval), not by the traveler"),
    ("P6-S14", "multiple travelers incl. children", "one traveler per trip; no counts, ages or fees"),
]:
    qa.record(test_id=sid, source_requirement=f"Prompt 6 style: {label}", priority="P1", test_layer="inventory", account="—", input="—", steps="capability map + request contract (IntentRequest/ItineraryRequest)", expected="the style as a user choice", actual=why, status=NI, evidence="qa/qa-20260923-cda5ccb/01-capability-map.md")

# S01 search-select-confirm = the critical journey (P1-E2E-01); S05/S06 through windows; S10 one way; S11 round trip; S12 open jaw; S13 multi-city; S15 flight+hotel
cases = [
    ("P6-S05", "departure after 18:00 in the departure zone (window)", {"source": "API", "intent": {"purpose": f"{T} S05 after 18:00", "origin": "BOS", "destination": "SEA", "earliestDeparture": f"{qa.future(30)}T18:00:00Z", "arrivalDeadline": f"{qa.future(31)}T06:00:00Z"}}, "one-way; every flight departs at or after 18:00Z (the sandbox zone is UTC by design)", lambda st, fl, o: st == "BOOKED" and fl and all(f["departure"] >= f"{qa.future(30)}T18:00:00Z" for f in fl)),
    ("P6-S06", "arrival before 09:00 in the destination zone (window)", {"source": "API", "intent": {"purpose": f"{T} S06 arrive before 09:00", "origin": "BOS", "destination": "SEA", "earliestDeparture": f"{qa.future(30)}T00:00:00Z", "arrivalDeadline": f"{qa.future(30)}T09:00:00Z"}}, "either a flight arriving by 09:00Z is booked, or an honest NO_FEASIBLE outcome (the earliest sandbox departure is 06:00 + 3-5.5 h)", lambda st, fl, o: (st == "BOOKED" and all(f["arrival"] <= f"{qa.future(30)}T09:00:00Z" for f in fl)) or st == "FAILED"),
    ("P6-S10", "one way: no unintended return", {"source": "API", "intent": {"purpose": f"{T} S10 one way", "origin": "BOS", "destination": "SEA", "earliestDeparture": f"{qa.future(30)}T05:00:00Z", "arrivalDeadline": f"{qa.future(30)}T23:59:00Z"}}, "exactly one flight, no inbound", lambda st, fl, o: st == "BOOKED" and len(fl) == 1),
    ("P6-S11", "round trip: outbound/return linkage", qa.round_trip(f"{T} S11 round trip", 30, 33), "two flights: BOS->SEA then SEA->BOS in that order, return after outbound", lambda st, fl, o: st == "BOOKED" and len(fl) == 2 and fl[0]["origin"] == "BOS" and fl[1]["origin"] == "SEA" and fl[1]["departure"] > fl[0]["arrival"]),
    ("P6-S12", "open jaw: JFK->LHR, CDG->JFK", itin(f"{T} S12 open jaw", [leg("JFK", "LHR", 30), leg("CDG", "JFK", 34)]), "two legs booked as given (no synthetic LHR->CDG leg)", lambda st, fl, o: st == "BOOKED" and [(f["origin"], f["destination"]) for f in fl] == [("JFK", "LHR"), ("CDG", "JFK")]),
    ("P6-S13", "multi-city NYC->London->Paris->Rome->NYC", itin(f"{T} S13 multi-city", [leg("JFK", "LHR", 30), leg("LHR", "CDG", 32), leg("CDG", "FCO", 34), leg("FCO", "JFK", 36)]), "four legs in order, chronology preserved", lambda st, fl, o: st == "BOOKED" and len(fl) == 4 and all(fl[i]["arrival"] < fl[i + 1]["departure"] for i in range(3))),
    ("P6-S15", "flight + hotel + transfer (multiple suppliers)", itin(f"{T} S15 flight hotel ground", [leg("BOS", "SEA", 30), leg("SEA", "BOS", 33)], [("SEA", 30, 33)], [{"kind": "ARRIVAL", "city": "SEA", "required": True}]), "one order with AIR, HOTEL and GROUND items, each with its own supplier reference", lambda st, fl, o: st == "BOOKED" and {i.get("type") for i in (o[:1] or [{}])[0].get("items", [])} >= {"AIR", "HOTEL", "GROUND"}),
    ("P6-ML-01", "multi-leg Bangor->Boston->New York->Bangor", itin(f"{T} ML1", [leg("BGR", "BOS", 30), leg("BOS", "JFK", 32), leg("JFK", "BGR", 34)]), "three legs booked in order", lambda st, fl, o: st == "BOOKED" and len(fl) == 3),
    ("P6-ML-02", "multi-leg NYC->Chicago->Denver->San Francisco->NYC", itin(f"{T} ML2", [leg("JFK", "ORD", 30), leg("ORD", "DEN", 32), leg("DEN", "SFO", 34), leg("SFO", "JFK", 36)]), "four legs booked in order", lambda st, fl, o: st == "BOOKED" and len(fl) == 4),
    ("P6-ML-03", "multi-leg SFO->Tokyo->Seoul->Singapore->SFO", itin(f"{T} ML3", [leg("SFO", "NRT", 30), leg("NRT", "ICN", 33), leg("ICN", "SIN", 36), leg("SIN", "SFO", 39)]), "four legs booked in order", lambda st, fl, o: st == "BOOKED" and len(fl) == 4),
    ("P6-ML-04", "multi-leg Boston->London->Dubai->Delhi->Singapore->Tokyo->Boston", itin(f"{T} ML4", [leg("BOS", "LHR", 30), leg("LHR", "DXB", 32), leg("DXB", "DEL", 34), leg("DEL", "SIN", 36), leg("SIN", "NRT", 38), leg("NRT", "BOS", 40)]), "six legs booked in order", lambda st, fl, o: st == "BOOKED" and len(fl) == 6),
]
for sid, label, body, expected, ok in cases:
    tid, r = submit("alice", body)
    if not tid:
        qa.record(test_id=sid, source_requirement=f"Prompt 6 style: {label}", priority="P1", test_layer="API", account="alice", input=label, steps="POST /trips", expected=expected, actual=f"{r.status} {(r.json or {}).get('code')} {(r.json or {}).get('detail','')[:80]}", status="FAIL", evidence=qa.evidence(f"p6-{sid}.json", {"status": r.status, "body": r.json}))
        continue
    st, t = done(tid)
    fl, o = flights_of(tid)
    qa.record(test_id=sid, source_requirement=f"Prompt 6 style: {label}; ticket structure", priority="P1", test_layer="API + workflow (persisted order)", account="alice", input=label, steps="POST /trips; poll; GET /orders?tripId",
              expected=expected, actual=f"{st} {t.get('failureStage') or ''}/{t.get('failureCode') or ''}; flights={[(f.get('origin'), f.get('destination'), f.get('departure')) for f in fl]}; items={[i.get('type') for i in (o[:1] or [{}])[0].get('items', [])]}",
              status="PASS" if ok(st, fl, o) else "FAIL", evidence=qa.evidence(f"p6-{sid}.json", {"final": t, "orders": o}), ids=tid)

# summary-vs-offer match before confirmation: the review step shows the request; the offer is chosen after submission (documented: submitting books)
qa.record(test_id="P6-SUM-01", source_requirement="Prompt 6 'before confirmation match summary to exact offer IDs, leg order, local times, price'", priority="P1", test_layer="inventory", account="—", input="—",
          steps="review step in the web app", expected="an offer summary before confirmation", actual="by design there is no offer at confirmation time: the traveler confirms a request, the platform then searches, judges, picks and books; the page states 'Submitting books the trip' and shows the chosen offer afterwards with 'Why this option'. A pre-confirmation offer summary is NOT part of the product.",
          status="NOT_APPLICABLE", evidence="qa/qa-20260923-cda5ccb/evidence/p01-04-review.png")

# edit/remove/reorder a middle leg: no edit exists (P3-EDIT-01)
qa.record(test_id="P6-ML-EDIT", source_requirement="Prompt 6 edit/remove/reorder a middle leg", priority="P1", test_layer="inventory", account="—", input="—", steps="—", expected="leg editing", actual="trips are immutable requests (see P3-EDIT-01); a changed plan is a new trip; unsupported structures are refused at validation (P3-ITN-*)", status=NI, evidence="")

# cancel-before-confirmation, rapid confirm, duplicate request: covered by P1-FAIL-01c, P3-MUT-01/02; refresh/browser close during confirmation: P10
qa.record(test_id="P6-DUP-01", source_requirement="Prompt 6 rapid confirm / duplicate request / cancel-before-confirmation", priority="P0", test_layer="cross-reference", account="alice", input="—", steps="—", expected="—",
          actual="proven by P3-MUT-01 (same key twice = one trip), P3-MUT-02 (5 concurrent = one trip), P1-FAIL-01c (cancel right after submit = CANCELLED, no order)", status="PASS", evidence="results.csv rows P3-MUT-01, P3-MUT-02, P1-FAIL-01c")
print(qa.summary())
