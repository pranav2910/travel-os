#!/usr/bin/env python3
"""Phase 10: latency, end-to-end booking time and concurrency against the local Docker stack.

Standard library only. Tokens come from the dev realm's password grant (the seeded dev password);
nothing here is a real credential. Results land in docs/program/performance/<label>.{json,md}.
"""

import argparse
import concurrent.futures as cf
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

APP = os.environ.get("APP_URL", "http://localhost:8080")
KC = os.environ.get("KC_URL", "http://localhost:8180")
POLICY = os.environ.get("POLICY_URL", "http://localhost:8082")
PASSWORD = os.environ.get("QA_PASSWORD", "password")
ROOT = Path(__file__).resolve().parent.parent
TOKENS: dict[str, str] = {}


def token(user: str, fresh: bool = False) -> str:
    if not fresh and user in TOKENS:
        return TOKENS[user]
    data = urllib.parse.urlencode({"client_id": "travelos-dev-cli", "grant_type": "password", "username": user, "password": PASSWORD}).encode()
    with urllib.request.urlopen(urllib.request.Request(f"{KC}/realms/travelos/protocol/openid-connect/token", data=data), timeout=30) as r:
        TOKENS[user] = json.load(r)["access_token"]
    return TOKENS[user]


class Response:
    def __init__(self, status: int, text: str):
        self.status = status
        self.text = text
        try:
            self.json = json.loads(text) if text else None
        except ValueError:
            self.json = None


def api(user: str, method: str, path: str, body=None, base: str = APP) -> Response:
    headers = {"Authorization": f"Bearer {token(user)}", "Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        headers["Idempotency-Key"] = str(uuid.uuid4())
        data = json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return Response(r.status, r.read().decode())
    except urllib.error.HTTPError as e:
        return Response(e.code, e.read().decode())


def round_trip(purpose: str, depart_in_days: int) -> dict:
    d = date.today() + timedelta(days=depart_in_days)
    r = d + timedelta(days=2)
    return {
        "intent": {
            "origin": "BOS",
            "destination": "SEA",
            "earliestDeparture": f"{d}T10:00:00Z",
            "arrivalDeadline": f"{d}T23:00:00Z",
            "returnAfter": f"{r}T20:00:00Z",
            "latestReturn": f"{r + timedelta(days=1)}T06:00:00Z",
            "purpose": purpose,
            "hotelRequired": False,
        }
    }


def wait_trip(user: str, trip_id: str, finals: set[str], timeout_s: float, poll_s: float = 0.5) -> str:
    end = time.time() + timeout_s
    status = "UNKNOWN"
    while time.time() < end:
        r = api(user, "GET", f"/api/v1/trips/{trip_id}")
        status = (r.json or {}).get("status", status)
        if status in finals:
            return status
        time.sleep(poll_s)
    return status


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * len(xs) + 0.5)) - 1)] if xs else None


def timed(fn):
    t0 = time.perf_counter()
    r = fn()
    return (time.perf_counter() - t0) * 1000, r


def summary(samples: dict) -> dict:
    out = {}
    for k, v in samples.items():
        if not v:
            continue
        out[k] = {"n": len(v), "p50": round(pct(v, 50), 1), "p95": round(pct(v, 95), 1), "p99": round(pct(v, 99), 1), "max": round(max(v), 1), "mean": round(statistics.fmean(v), 1)}
    return out


def seed_policy():
    doc = json.loads((ROOT / "platform/local/seed/policies/acme-us-standard.json").read_text())
    r = api("carol", "POST", "/api/v1/policies", {"document": doc, "note": "perf seed"}, base=POLICY)
    if r.status not in (200, 201):
        raise SystemExit(f"could not seed the policy: {r.status} {r.text[:200]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--samples", type=int, default=20)
    ap.add_argument("--concurrency", type=int, default=10)
    ap.add_argument("--burst", type=int, default=25)
    args = ap.parse_args()
    seed_policy()
    for u in ("alice", "bob", "carol", "dan"):
        token(u)
    # warm-up: one of everything, not measured
    r = api("alice", "POST", "/api/v1/trips", round_trip("perf warm-up", 40))
    if r.status != 202:
        raise SystemExit(f"warm-up create failed: {r.status} {r.text[:200]}")
    wait_trip("alice", r.json["tripId"], {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 120)
    api("carol", "GET", "/api/v1/cases")
    api("alice", "GET", "/api/v1/notifications")
    api("carol", "GET", "/api/v1/reports/spend?groupBy=month")

    lat = {"login_ms": [], "list_ms": [], "create_ms": [], "detail_ms": [], "itinerary_ics_ms": [], "cases_ms": [], "inbox_ms": [], "report_spend_ms": [], "e2e_book_s": []}
    e2e_outcomes = []
    for i in range(args.samples):
        ms, _ = timed(lambda: token("alice", fresh=True)); lat["login_ms"].append(ms)
        ms, _ = timed(lambda: api("alice", "GET", "/api/v1/trips?limit=20")); lat["list_ms"].append(ms)
        t0 = time.time()
        ms, r = timed(lambda: api("alice", "POST", "/api/v1/trips", round_trip(f"perf sample {i}", 30 + (i % 20)))); lat["create_ms"].append(ms)
        tid = (r.json or {}).get("tripId", "")
        ms, _ = timed(lambda: api("alice", "GET", f"/api/v1/trips/{tid}")); lat["detail_ms"].append(ms)
        ms, _ = timed(lambda: api("alice", "GET", f"/api/v1/trips/{tid}/itinerary.ics")); lat["itinerary_ics_ms"].append(ms)
        ms, _ = timed(lambda: api("carol", "GET", "/api/v1/cases?limit=50")); lat["cases_ms"].append(ms)
        ms, _ = timed(lambda: api("alice", "GET", "/api/v1/notifications?limit=50")); lat["inbox_ms"].append(ms)
        ms, _ = timed(lambda: api("carol", "GET", "/api/v1/reports/spend?groupBy=month")); lat["report_spend_ms"].append(ms)
        st = wait_trip("alice", tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL", "CANCELLED"}, 180)
        e2e_outcomes.append(st)
        lat["e2e_book_s"].append(time.time() - t0)
        print(f"sample {i + 1}/{args.samples}: create {lat['create_ms'][-1]:.0f} ms, e2e {lat['e2e_book_s'][-1]:.1f} s -> {st}", file=sys.stderr)

    # concurrency: N users each submitting a trip and waiting for it
    users = ["alice", "bob", "carol", "dan"]

    def one(i):
        u = users[i % len(users)]
        t0 = time.time()
        ms, r = timed(lambda: api(u, "POST", "/api/v1/trips", round_trip(f"perf concurrent {i}", 30 + (i % 20))))
        tid = (r.json or {}).get("tripId", "")
        st = wait_trip(u, tid, {"BOOKED", "FAILED", "AWAITING_APPROVAL", "CANCELLED"}, 240) if tid else "REJECTED"
        return {"user": u, "status": r.status, "create_ms": ms, "final": st, "seconds": time.time() - t0}

    t0 = time.time()
    with cf.ThreadPoolExecutor(args.concurrency) as ex:
        conc = list(ex.map(one, range(args.concurrency)))
    conc_wall = time.time() - t0

    # burst: create as fast as possible, measure acceptance throughput only
    t0 = time.time()
    with cf.ThreadPoolExecutor(args.concurrency) as ex:
        burst = list(ex.map(lambda i: timed(lambda: api(users[i % 4], "POST", "/api/v1/trips", round_trip(f"perf burst {i}", 30 + (i % 20)))), range(args.burst)))
    burst_wall = time.time() - t0
    burst_ms = [b[0] for b in burst]
    burst_ok = sum(1 for b in burst if b[1].status == 202)

    result = {
        "label": args.label,
        "at": datetime.now(timezone.utc).isoformat(),
        "stack": "local docker compose, SIMULATED suppliers and payments, 8 GB Docker VM",
        "samples": args.samples,
        "summary": summary(lat),
        "e2e_outcomes": {s: e2e_outcomes.count(s) for s in set(e2e_outcomes)},
        "concurrency": {"users": args.concurrency, "wall_s": round(conc_wall, 1), "create_ms": summary({"create_ms": [c["create_ms"] for c in conc]})["create_ms"], "e2e_s": summary({"e2e_s": [c["seconds"] * 1000 for c in conc]})["e2e_s"], "finals": {s: sum(1 for c in conc if c["final"] == s) for s in set(c["final"] for c in conc)}, "statuses": sorted(set(c["status"] for c in conc))},
        "burst": {"requests": args.burst, "accepted": burst_ok, "wall_s": round(burst_wall, 2), "requests_per_s": round(args.burst / burst_wall, 1), "create_ms": summary({"create_ms": burst_ms})["create_ms"]},
        "raw": lat,
    }
    out = ROOT / "docs/program/performance"
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{args.label}.json").write_text(json.dumps(result, indent=1))
    lines = [f"# Performance run `{args.label}`", "", f"{result['at']} on {result['stack']}; {args.samples} warm sequential samples per scenario.", "", "| Scenario | n | p50 | p95 | p99 | max |", "|---|---|---|---|---|---|"]
    for k, v in result["summary"].items():
        unit = "s" if k.endswith("_s") else "ms"
        lines.append(f"| {k} | {v['n']} | {v['p50']} {unit} | {v['p95']} {unit} | {v['p99']} {unit} | {v['max']} {unit} |")
    c = result["concurrency"]
    lines += ["", f"End-to-end outcomes: {result['e2e_outcomes']}.", "", f"Concurrency: {c['users']} users at once; wall {c['wall_s']} s; create p95 {c['create_ms']['p95']} ms; e2e p95 {c['e2e_s']['p95'] / 1000:.1f} s; finals {c['finals']}.", "", f"Burst: {result['burst']['accepted']}/{result['burst']['requests']} accepted in {result['burst']['wall_s']} s ({result['burst']['requests_per_s']} req/s); create p95 {result['burst']['create_ms']['p95']} ms."]
    (out / f"{args.label}.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
