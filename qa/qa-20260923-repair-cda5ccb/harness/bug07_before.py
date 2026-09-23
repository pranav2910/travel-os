"""BUG-07 before-phase reproduction, re-run with a well-formed key (the first attempt sent a key
with spaces and was refused as IDEMPOTENCY_KEY_MALFORMED, which reproduces nothing)."""
import concurrent.futures as cf, uuid, qa
key = f"{qa.TAG}-before-idem-conc-{uuid.uuid4()}"
body = qa.round_trip(f"{qa.TAG} before BUG-07 concurrent same key (retry)", 38, 40)
with cf.ThreadPoolExecutor(5) as ex:
    rs = list(ex.map(lambda _: qa.api("alice", "POST", "/api/v1/trips", body, idem=key), range(5)))
ids = {(r.json or {}).get("tripId") for r in rs if r.status in (200, 202)}
ok = len(ids) == 1 and all(r.status in (200, 202) for r in rs)
qa.record(test_id="BUG-07-1-before", source_requirement="BUG-07 / P3-MUT-02: five concurrent identical submissions answer with one logical trip and a documented success/replay status, never 500 (re-run of the before row with a well-formed key)", priority="P1", test_layer="API (5 threads)", account="alice",
          input="one key, five simultaneous POSTs", steps="ThreadPoolExecutor(5)", expected="every answer 202 (or 200) with the same trip id; one row",
          actual=f"statuses={[r.status for r in rs]} codes={[(r.json or {}).get('code') if isinstance(r.json, dict) else None for r in rs]} distinct ids={len(ids)}", status="PASS" if ok else "FAIL",
          evidence=qa.evidence("bug07-concurrent-before.json", [{"status": r.status, "body": r.json} for r in rs]), ids=" ".join(sorted(i for i in ids if i)), bug_id="" if ok else "BUG-07")
