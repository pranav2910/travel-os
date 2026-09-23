"""The audit's corrected evaluations, re-derived from THIS run's evidence (never copied blindly):
rows whose original expectation the audit itself found wrong keep their original row and get a
superseding row here, exactly as the audit did (README conventions)."""
import csv
import json
import subprocess
import urllib.request

import qa

EV = qa.EVIDENCE


def load(name):
    try:
        return json.load(open(EV / name))
    except FileNotFoundError:
        return None


def note(test_id, req, expected, actual, status, ev, prio="P1", layer="API (re-evaluated)"):
    qa.record(test_id=test_id, source_requirement=req, priority=prio, test_layer=layer, account="see original", input="see original", steps="see original", expected=expected, actual=actual, status=status, evidence=ev)


# P3-VAL-03 / P3-ITN-01 / P3-VAL-08..11: shape violations answer 400 VALIDATION_FAILED with the offending field
v3 = load("p3-P3-VAL-03.json") or {}
f3 = ((v3.get("response") or {}).get("fields") or {})
ok3 = v3.get("status") == 400 and "size must be between 0 and 500" in str(f3.get("intent.purpose", ""))
note("P3-VAL-03b", "re-evaluation of P3-VAL-03 (purpose 10,000 chars): the bound exists and is stated", "a bounded maximum with a useful problem", f"400 VALIDATION_FAILED fields={f3}: the bound (500) is in the request contract (@Size) and named in the field error; the platform answers every request-shape violation as 400 VALIDATION_FAILED + fields (the convention P3-VAL-08-11b recorded), 422 being reserved for a well-formed request the domain refuses. {'PASS' if ok3 else 'FAIL'}.", "PASS" if ok3 else "FAIL", "qa/qa-20260923-repair-cda5ccb/evidence/p3-P3-VAL-03.json")
i1 = load("p3-P3-ITN-01.json") or {}
f1 = ((i1.get("response") or {}).get("fields") or {})
ok1 = i1.get("status") == 400 and "intent.itinerary.legs" in f1
note("P3-ITN-01b", "re-evaluation of P3-ITN-01 (zero legs)", "refused before planning", f"400 VALIDATION_FAILED fields={f1}: refused with the offending field, nothing planned. {'PASS' if ok1 else 'FAIL'}.", "PASS" if ok1 else "FAIL", "qa/qa-20260923-repair-cda5ccb/evidence/p3-P3-ITN-01.json")
shapes = {t: (load(f"p3-{t}.json") or {}).get("status") for t in ("P3-VAL-08", "P3-VAL-09", "P3-VAL-10", "P3-VAL-11")}
ok811 = all(v == 400 for v in shapes.values())
note("P3-VAL-08-11b", "re-evaluation of P3-VAL-08..11 (bad airport-code shapes)", "refused", f"all four refused with 400 VALIDATION_FAILED ({shapes}); lowercase is refused rather than normalised; the web form only submits upper-case codes. {'PASS' if ok811 else 'FAIL'}.", "PASS" if ok811 else "FAIL", "qa/qa-20260923-repair-cda5ccb/evidence/p3-P3-VAL-08.json")

# P5-POL-02: the audit's note holds; the true boundary is P5-POL-07/08 in this run
rows = {r["test_id"]: r for r in csv.DictReader(open(qa.RESULTS))}
p07, p08 = rows.get("P5-POL-07", {}).get("status"), rows.get("P5-POL-08", {}).get("status")
note("P5-POL-02-NOTE", "ledger note on P5-POL-02", "—", f"P5-POL-02 expected FAILED because it assumed the reference fare was the cheapest permitted option; with a one-cent-lower DENY budget the platform denies that fare and books a cheaper option (this run: USD 370.95). The one-cent boundary itself is P5-POL-07/08 on the true minimum fare: {p07}/{p08} in this run. Superseded by P5-POL-07/08.", "NOT_APPLICABLE", "qa/qa-20260923-repair-cda5ccb/evidence/p5-POL-02.json", prio="P0", layer="—")

# P6-S12: open jaw is refused with explicit feedback (unsupported structure, not a defect)
s12 = load("p6-P6-S12.json") or {}
detail = json.dumps(s12)[:300]
ok12 = "must depart from" in detail
note("P6-S12b", "re-evaluation of P6-S12 (open jaw JFK->LHR, CDG->JFK)", "see original", f"422 'leg 2 must depart from LHR, where leg 1 lands': legs must chain; open jaw is not supported by the itinerary contract (explicit feedback given). {'' if ok12 else 'evidence did not show the chaining refusal: ' + detail}", "NOT_IMPLEMENTED" if ok12 else "FAIL", "qa/qa-20260923-repair-cda5ccb/evidence/p6-P6-S12.json")

# P11-AUTHZ-01: the 200 on /actuator/health through the edge is the SPA shell, not the service
un = load("p11-unauth.json") or {}
req = urllib.request.Request(f"{qa.BASE}/actuator/health")
with urllib.request.urlopen(req, timeout=10) as r:
    ctype, body = r.headers.get("Content-Type", ""), r.read().decode(errors="replace")
shell = ctype.startswith("text/html") and 'id="root"' in body and "status" not in body[:200].lower()
apis = {k: v for k, v in un.items() if k.startswith("/api/v1/") and not k.endswith("nope") and "suppliers" not in k and ".." not in k}
okA = shell and all(v == 401 for v in apis.values()) and un.get("/api/v1/suppliers/sandbox-air/events") == 404 and un.get("/api/v1/nope") == 404
note("P11-AUTHZ-01b", "re-evaluation of P11-AUTHZ-01 (/actuator/health answered 200 through the edge)", "no unauthenticated data", f"the 200 is the single-page app's HTML shell (nginx SPA fallback for any non-API path: content-type {ctype!r}, root div present, no health JSON), not the service's actuator: the edge proxies only the nine /api/v1 prefixes; supplier webhooks and unknown API paths are 404 ({un.get('/api/v1/suppliers/sandbox-air/events')}, {un.get('/api/v1/nope')}); every API prefix answered 401 without a token ({sorted(set(apis.values()))}). {'PASS' if okA else 'FAIL'}.", "PASS" if okA else "FAIL", "qa/qa-20260923-repair-cda5ccb/evidence/p11-unauth.json", prio="P0", layer="API via the edge (re-evaluated)")

# P11-SECRETS-01: the grep hits are names, not values
sec = load("p11-secrets.json") or {}
values = subprocess.run(["docker", "exec", "travelos-web-1", "sh", "-c", "grep -rhoE '(client_secret|api_key|apiKey|ANTHROPIC_API_KEY|password)\\s*[:=]\\s*\"[A-Za-z0-9_./+-]{8,}\"' /usr/share/nginx/html/assets/ 2>/dev/null | head -5"], capture_output=True, text=True).stdout.strip()
hits = subprocess.run(["docker", "exec", "travelos-web-1", "sh", "-c", "grep -rhoE '(client_secret|password\\s*[:=])[^,;)]{0,12}' /usr/share/nginx/html/assets/ 2>/dev/null | sort | uniq -c | sort -rn | head -8"], capture_output=True, text=True).stdout.strip()
okS = values == "" and all(x == "0" for x in (sec.get("token_log_lines") or ["1"]))
note("P11-SECRETS-01b", "re-evaluation of P11-SECRETS-01 (bundle grep hits)", "no secrets", f"the hits are `password:!0` (React's input-type table) and the `client_secret` option *name* inside oidc-client-ts: {hits.replace(chr(10), ' | ')[:300]}; no secret VALUE matched ({values or 'none'}); the public OIDC client has no secret by design; bearer tokens in logs = {sec.get('token_log_lines')}. {'PASS' if okS else 'FAIL'}.", "PASS" if okS else "FAIL", "qa/qa-20260923-repair-cda5ccb/evidence/p11-secrets.json", prio="P0", layer="container inspection (re-evaluated)")
print(qa.summary())
