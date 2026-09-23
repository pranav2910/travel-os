"""Two after-phase rows re-run on their own after the edge was rebuilt: the failure-code scan scoped
to this run's trips (the first run scanned every trip in the preserved database, including the ones
that failed before the fix), and the outage document (the first run's probe gave up after 10 s
while nginx waited 60 s for the stopped upstream; the edge now gives up after 5 s)."""
import json
import subprocess
import time
import urllib.request

import qa

PHASE = "after"
SUFFIX = f"-{PHASE}"


def psql(db, sql):
    return subprocess.run(["docker", "exec", "travelos-postgres-1", "psql", "-U", "travelos", "-d", db, "-tAc", sql], capture_output=True, text=True, timeout=60).stdout.strip()


def rec(test_id, req, prio, layer, account, inp, steps, expected, actual, ok, ev, ids="", bug=""):
    qa.record(test_id=f"{test_id}{SUFFIX}", source_requirement=req, priority=prio, test_layer=layer, account=account, input=inp, steps=steps, expected=expected, actual=actual,
              status="PASS" if ok else "FAIL", evidence=ev, ids=ids, bug_id=bug if not ok else "")


# this run's trips on the repaired images only: the before-phase trips (purpose '<tag> before ...')
# failed on the audited images and keep their historical codes on purpose
codes = json.loads(psql("travel_core", f"select coalesce(json_agg(distinct failure_code), '[]') from trip where failure_code is not null and purpose like '{qa.TAG}%' and purpose not like '{qa.TAG} before%'") or "[]")
bad = [c for c in codes if c and ("." in c or c.endswith("Exception") or c.endswith("Error"))]
rec("BUG-05-3", "BUG-05: no trip failure code recorded in this run is an exception class name (read-only DB scan of the run's trips on the repaired images; the before-phase trips keep the codes the audited images wrote)", "P1", "DB read-only", "—", "select distinct failure_code from trip where purpose like '<run tag>%'", "psql",
    "no code containing '.' or ending in Exception/Error", f"codes={codes}; offending={bad}", not bad, qa.evidence(f"bug05-failure-codes{SUFFIX}.json", {"codes": codes, "offending": bad}), "", "BUG-05")


def hdrs(url, token=None, timeout=75):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode(errors="replace")[:400]
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode(errors="replace")[:400]


subprocess.run(["docker", "stop", "travelos-travel-core-1"], capture_output=True, text=True, timeout=120)
try:
    time.sleep(2)
    t0 = time.time()
    st, h, body = hdrs(f"{qa.BASE}/api/v1/trips?limit=1", token=qa.token("alice"))
    took = round(time.time() - t0, 1)
    try:
        doc = json.loads(body)
    except json.JSONDecodeError:
        doc = None
    ok = st in (502, 503, 504) and (h.get("Content-Type") or "").startswith("application/problem+json") and isinstance(doc, dict) and doc.get("status") == st and doc.get("code") in ("UPSTREAM_UNAVAILABLE", "UPSTREAM_TIMEOUT") and bool(h.get("Content-Security-Policy")) and took < 30
    rec("BUG-14-1", "BUG-14 / P12-OUT: an upstream outage answers a problem+json document with a stable code and the security headers, within seconds, not nginx's HTML after a minute", "P2", "HTTP + container fault", "alice", "docker stop travel-core; GET /api/v1/trips via the edge", "stop; GET; start",
        "503 (or 502/504) application/problem+json {status, code UPSTREAM_UNAVAILABLE|UPSTREAM_TIMEOUT, detail} in < 30 s", f"{st} {h.get('Content-Type')} after {took}s {body[:160]}", ok, qa.evidence(f"bug14-outage{SUFFIX}.json", {"status": st, "took_s": took, "headers": h, "body": body}), "", "BUG-14")
finally:
    subprocess.run(["docker", "start", "travelos-travel-core-1"], capture_output=True, text=True, timeout=120)
    for _ in range(90):
        if subprocess.run(["docker", "inspect", "--format", "{{.State.Health.Status}}", "travelos-travel-core-1"], capture_output=True, text=True).stdout.strip() == "healthy":
            break
        time.sleep(2)
print(qa.summary())
