"""QA harness for the travel-os sandbox stack: authenticated API calls, bounded polling, and a
results ledger with evidence files. Test-only code; it never touches product code.

Every recorded result lands in results.csv with an evidence file under evidence/. Secrets are
never written: tokens are held in memory and redacted from stored requests/responses.
"""

from __future__ import annotations

import csv
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

RUN = Path(__file__).resolve().parents[1].name
ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "evidence"
EVIDENCE.mkdir(exist_ok=True)
RESULTS = ROOT / "results.csv"
BASE = os.environ.get("APP_URL", "http://localhost:8080")
KC = os.environ.get("KC_URL", "http://localhost:8180")
SUPPLIER = os.environ.get("SUPPLIER_URL", "http://localhost:8084")
CORE = os.environ.get("CORE_URL", "http://localhost:8081")
USERS = ["alice", "bob", "carol", "dan", "zoe"]
PASSWORD = os.environ.get("QA_PASSWORD", "password")  # the realm's seeded dev password
TAG = f"{RUN}"

FIELDS = [
    "test_id", "source_requirement", "priority", "test_layer", "account", "preconditions", "input",
    "steps", "expected", "actual", "status", "timestamp", "evidence", "ids", "cleanup", "bug_id",
]

_tokens: dict[str, tuple[str, float]] = {}


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def redact(text: str) -> str:
    text = re.sub(r"Bearer [A-Za-z0-9._-]+", "Bearer <redacted>", text)
    text = re.sub(r"eyJ[A-Za-z0-9._-]{20,}", "<jwt-redacted>", text)
    return text


def token(user: str, fresh: bool = False) -> str:
    """A password-grant token from the dev CLI client (the realm's supported non-browser flow)."""
    cached = _tokens.get(user)
    if cached and not fresh and time.time() < cached[1] - 60:
        return cached[0]
    data = urllib.parse.urlencode(
        {"client_id": "travelos-dev-cli", "grant_type": "password", "username": user, "password": PASSWORD}
    ).encode()
    req = urllib.request.Request(f"{KC}/realms/travelos/protocol/openid-connect/token", data=data)
    body = None
    for attempt in range(6):  # the IdP rate-limits/locks briefly after failed logins or bursts: bounded retry
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                body = json.load(r)
            break
        except urllib.error.HTTPError as e:
            if attempt == 5:
                raise
            time.sleep(5 * (attempt + 1))
    _tokens[user] = (body["access_token"], time.time() + int(body.get("expires_in", 900)))
    return body["access_token"]


class Resp:
    def __init__(self, status: int, headers: dict, text: str):
        self.status, self.headers, self.text = status, headers, text
        try:
            self.json = json.loads(text) if text else None
        except json.JSONDecodeError:
            self.json = None

    def __repr__(self) -> str:
        return f"Resp({self.status}, {self.text[:200]!r})"


def api(
    user: str | None,
    method: str,
    path: str,
    body=None,
    *,
    base: str = BASE,
    idem: str | None = None,
    headers: dict | None = None,
    raw_token: str | None = None,
    timeout: float = 30,
) -> Resp:
    h = {"Accept": "application/json"}
    if user:
        h["Authorization"] = f"Bearer {token(user)}"
    if raw_token is not None:
        h["Authorization"] = f"Bearer {raw_token}"
    if headers:
        h.update(headers)
    data = None
    if body is not None:
        if isinstance(body, (bytes, str)):
            data = body.encode() if isinstance(body, str) else body
        else:
            data = json.dumps(body).encode()
        h.setdefault("Content-Type", "application/json")
    if method in ("POST", "PUT", "PATCH", "DELETE") and idem is None and "Idempotency-Key" not in h:
        idem = f"{TAG}-{uuid.uuid4()}"
    if idem:
        h["Idempotency-Key"] = idem
    req = urllib.request.Request(base + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return Resp(r.status, dict(r.headers), r.read().decode(errors="replace"))
    except urllib.error.HTTPError as e:
        return Resp(e.code, dict(e.headers), e.read().decode(errors="replace"))


def poll(fn, ok, deadline_s: float, every: float = 1.5):
    """Bounded, condition-based wait. Returns the last value; `ok(value)` says when to stop."""
    until = time.time() + deadline_s
    last = None
    while time.time() < until:
        last = fn()
        if ok(last):
            return last
        time.sleep(every)
    return last


def trip(user: str, trip_id: str) -> dict | None:
    r = api(user, "GET", f"/api/v1/trips/{trip_id}")
    return r.json if r.status == 200 else None


def wait_trip(user: str, trip_id: str, statuses: set[str], deadline_s: float = 180) -> str:
    t = poll(lambda: trip(user, trip_id), lambda t: t is not None and t.get("status") in statuses, deadline_s)
    return (t or {}).get("status", "MISSING")


def future(days: int) -> str:
    return (dt.date.today() + dt.timedelta(days=days)).isoformat()


def round_trip(purpose: str, out_day: int, back_day: int, origin="BOS", dest="SEA", hotel=False):
    d0, d1 = future(out_day), future(back_day)
    return {
        "source": "API",
        "intent": {
            "purpose": purpose,
            "origin": origin,
            "destination": dest,
            "earliestDeparture": f"{d0}T05:00:00Z",
            "arrivalDeadline": f"{d0}T23:59:00Z",
            "returnAfter": f"{d1}T10:00:00Z",
            "latestReturn": f"{d1}T23:00:00Z",
            "hotelRequired": hotel,
        },
    }


def itinerary(purpose: str, legs: list[tuple[str, str, int]], stays=(), currency="USD"):
    """legs: (origin, destination, day offset); stays: (city, in offset, out offset)."""
    return {
        "source": "API",
        "intent": {
            "purpose": purpose,
            "itinerary": {
                "currency": currency,
                "legs": [
                    {
                        "origin": o,
                        "destination": d,
                        "earliestDeparture": f"{future(day)}T05:00:00Z",
                        "arrivalDeadline": f"{future(day)}T23:59:00Z",
                    }
                    for o, d, day in legs
                ],
                "stays": [
                    {"city": c, "checkInDate": future(i), "checkOutDate": future(o), "required": True}
                    for c, i, o in stays
                ],
                "transfers": [],
            },
        },
    }


def seed_policy(mutate=None, note: str = "qa") -> Resp:
    doc = json.load(open(Path(__file__).resolve().parents[3] / "platform/local/seed/policies/acme-us-standard.json"))
    if mutate:
        mutate(doc)
    return api("carol", "POST", "/api/v1/policies", {"document": doc, "note": f"{TAG} {note}"})


def evidence(name: str, content) -> str:
    """Write an evidence file (JSON or text) and return its repo-relative path."""
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", name)[:120]
    path = EVIDENCE / safe
    if isinstance(content, (dict, list)):
        text = json.dumps(content, indent=1, default=str)
    else:
        text = str(content)
    path.write_text(redact(text))
    return str(path.relative_to(ROOT.parent.parent))


def record(**kw) -> None:
    row = {k: "" for k in FIELDS}
    row.update({k: (json.dumps(v) if isinstance(v, (dict, list)) else str(v)) for k, v in kw.items()})
    row.setdefault("timestamp", now())
    if not row["timestamp"]:
        row["timestamp"] = now()
    new = not RESULTS.exists()
    with RESULTS.open("a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        if new:
            w.writeheader()
        w.writerow(row)
    mark = {"PASS": "PASS", "FAIL": "FAIL", "BLOCKED": "BLOCKED", "NOT_IMPLEMENTED": "N/I", "NOT_APPLICABLE": "N/A"}.get(row["status"], row["status"])
    print(f"[{mark:7}] {row['test_id']}: {row['actual'][:110]}", flush=True)


def summary() -> dict:
    counts: dict[str, int] = {}
    with RESULTS.open() as f:
        for r in csv.DictReader(f):
            counts[r["status"]] = counts.get(r["status"], 0) + 1
    return counts


if __name__ == "__main__":
    print(RUN, BASE, summary() if RESULTS.exists() else "no results yet")
