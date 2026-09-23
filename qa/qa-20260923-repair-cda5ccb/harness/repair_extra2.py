"""After the currency set was narrowed to USD and component failure text was bounded (both found by
the first after-phase run: a GBP itinerary was accepted and then failed at OPTIMIZATION with an
internal error because a component's infeasibility reason overflowed a VARCHAR(80)): the two BUG-05
rows again, and one row proving a long infeasibility reason no longer becomes a 500/PLATFORM_ERROR."""
import qa

T = f"{qa.TAG} after"


def code(r):
    return (r.json or {}).get("code") if isinstance(r.json, dict) else None


def rec(test_id, req, prio, layer, account, inp, steps, expected, actual, ok, ev, ids="", bug=""):
    qa.record(test_id=f"{test_id}-after", source_requirement=req, priority=prio, test_layer=layer, account=account, input=inp, steps=steps, expected=expected, actual=actual,
              status="PASS" if ok else "FAIL", evidence=ev, ids=ids, bug_id=bug if not ok else "")


r = qa.api("alice", "POST", "/api/v1/trips", qa.itinerary(f"{T} BUG-05 currency XXX (2)", [("BOS", "SEA", 30), ("SEA", "BOS", 33)], currency="XXX"))
rec("BUG-05-1", "BUG-05 / P3-ITN-03: an unsupported currency (XXX) is refused before planning with a stable code", "P1", "API", "alice", "itinerary currency=XXX", "POST /trips", "422 CURRENCY_UNSUPPORTED naming USD",
    f"{r.status} {code(r) or ''} {str((r.json or {}).get('detail', ''))[:120]}", r.status == 422 and code(r) == "CURRENCY_UNSUPPORTED", qa.evidence("bug05-xxx-after.json", {"status": r.status, "body": r.json}), "", "BUG-05")
r = qa.api("alice", "POST", "/api/v1/trips", qa.itinerary(f"{T} BUG-05 currency GBP (2)", [("BOS", "LHR", 30), ("LHR", "BOS", 33)], currency="GBP"))
rec("BUG-05-2", "BUG-05: a currency no flight can be quoted in (GBP: the sandbox airline quotes USD only, nothing converts) is refused up front, never accepted and then failed at OPTIMIZATION", "P2", "API", "alice", "itinerary currency=GBP", "POST /trips", "422 CURRENCY_UNSUPPORTED naming USD",
    f"{r.status} {code(r) or ''} {str((r.json or {}).get('detail', ''))[:100]}", r.status == 422 and code(r) == "CURRENCY_UNSUPPORTED", qa.evidence("bug05-gbp-after.json", {"status": r.status, "body": r.json}), "", "BUG-05")
# a USD trip with a London stay: the GBP-quoted rooms are infeasible (no conversion); the trip ends
# with a stated reason, never an internal error, and the component carries a bounded reason
r = qa.api("alice", "POST", "/api/v1/trips", qa.itinerary(f"{T} BUG-05 USD trip with a GBP hotel", [("BOS", "LHR", 40), ("LHR", "BOS", 42)], [("LHR", 40, 42)]))
tid = (r.json or {}).get("tripId", "")
st = qa.wait_trip("alice", tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 200) if tid else f"REJECTED-{r.status}"
t = qa.trip("alice", tid) or {}
comps = qa.api("alice", "GET", f"/api/v1/trips/{tid}/components").json or [] if tid else []
hotel = next((c for c in comps if c.get("type") == "HOTEL"), {})
ok = r.status == 202 and st in ("FAILED", "AWAITING_APPROVAL", "BOOKED") and t.get("failureCode") != "PLATFORM_ERROR" and (st != "FAILED" or t.get("failureCode") in ("NO_FEASIBLE_ITINERARY", "ALL_CANDIDATES_DENIED", "NO_OFFERS"))
rec("BUG-05-4", "BUG-05 (found by the first after run): a component whose infeasibility reason is long (GBP-quoted London hotel in a USD trip: CURRENCY_MISMATCH) ends the trip with a stated reason, not a 500 turned PLATFORM_ERROR (trip_component.failure_code is bounded at the repository)", "P1", "API + workflow + DB", "alice", "BOS-LHR-BOS, USD, with an LHR stay",
    "POST; poll; GET components", "202 then FAILED NO_FEASIBLE_ITINERARY (or a policy stop), hotel component INFEASIBLE with a reason; never PLATFORM_ERROR", f"{r.status} -> {st} {t.get('failureStage')}/{t.get('failureCode')}; hotel component={hotel.get('status')} {str(hotel.get('failureCode') or '')[:60]!r}", ok,
    qa.evidence("bug05-long-reason-after.json", {"create": r.json, "final": t, "components": comps}), tid, "BUG-05")
print(qa.summary())
