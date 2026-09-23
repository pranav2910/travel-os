"""Prompt 11: API validation and application security (bounded to this stack)."""

import json
import subprocess
import urllib.request

import qa

T = qa.TAG


def hdrs(url):
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers)


def code(r):
    return (r.json or {}).get("code") if isinstance(r.json, dict) else None


# ---- per-operation contract probes on the main mutation
probes = [
    ("P11-API-01", "malformed JSON", lambda: qa.api("alice", "POST", "/api/v1/trips", "{not json"), (400,)),
    ("P11-API-02", "wrong content type (text/plain)", lambda: qa.api("alice", "POST", "/api/v1/trips", json.dumps(qa.round_trip(f"{T} ctype", 30, 32)), headers={"Content-Type": "text/plain"}), (400, 415)),
    ("P11-API-03", "array instead of object", lambda: qa.api("alice", "POST", "/api/v1/trips", [1, 2]), (400, 422)),
    ("P11-API-04", "wrong types (origin as number, hotelRequired as string)", lambda: qa.api("alice", "POST", "/api/v1/trips", {"source": "API", "intent": {**qa.round_trip(f"{T} types", 30, 32)["intent"], "origin": 123, "hotelRequired": "yes"}}), (400, 422)),
    ("P11-API-05", "unknown top-level fields", lambda: qa.api("alice", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} extra", 30, 32), "__proto__": {"x": 1}, "admin": True}), (202, 400, 422)),
    ("P11-API-06", "oversized body (~2 MB purpose)", lambda: qa.api("alice", "POST", "/api/v1/trips", {"source": "API", "intent": {**qa.round_trip(f"{T} big", 30, 32)["intent"], "purpose": "x" * 2_000_000}}), (400, 413, 422)),
    ("P11-API-07", "wrong method (DELETE /trips)", lambda: qa.api("alice", "DELETE", "/api/v1/trips"), (405,)),
    ("P11-API-08", "missing Idempotency-Key on a mutation", lambda: qa.api("alice", "POST", "/api/v1/trips", qa.round_trip(f"{T} nokey", 30, 32), headers={"Idempotency-Key": ""}), (400, 422)),
    ("P11-API-09", "approval with an unknown decision value", lambda: qa.api("bob", "POST", "/api/v1/trips/trip_01ARZ3NDEKTSV4RRFFQ69G5FAV/approval", {"decision": "MAYBE"}), (400, 404, 422)),
    ("P11-API-10", "refund with a string amount", lambda: qa.api("carol", "POST", "/api/v1/learning/outcomes/refunds", {"tripId": "trip_x", "orderId": "ord_x", "amountMinor": "ten", "currency": "USD", "reference": "x"}), (400, 422)),
    ("P11-API-11", "learning config with an unknown mode", lambda: qa.api("carol", "PUT", "/api/v1/learning/config", {"mode": "TURBO"}), (400, 422)),
    ("P11-API-12", "policy publish with an empty document", lambda: qa.api("carol", "POST", "/api/v1/policies", {"document": {}, "note": T}), (400, 422)),
    ("P11-API-13", "policy publish with a negative limit", lambda: qa.api("carol", "POST", "/api/v1/policies", {"document": {**json.load(open(qa.Path(__file__).resolve().parents[3] / "platform/local/seed/policies/acme-us-standard.json")), "trip": {"maxTotal": -5, "onViolation": "DENY"}}, "note": T}), (400, 422)),
    ("P11-API-14", "connector create with an unknown kind", lambda: qa.api("carol", "POST", "/api/v1/connectors", {"kind": "FAX", "provider": "sandbox-fax"}), (400, 422)),
    ("P11-API-15", "exposure resolution on a nonexistent order", lambda: qa.api("carol", "POST", "/api/v1/orders/ord_01ARZ3NDEKTSV4RRFFQ69G5FAV/exposures/exp_01ARZ3NDEKTSV4RRFFQ69G5FAV/resolution", {"resolution": "WRITTEN_OFF"}), (400, 404, 422)),
]
for tid_, label, fn, expect in probes:
    r = fn()
    body_ok = (r.json is None) or (isinstance(r.json, dict) and ("code" in r.json or "status" in r.json or r.status < 300))
    leak = isinstance(r.text, str) and ("Exception" in r.text or "at io." in r.text or "stack" in r.text.lower())
    qa.record(test_id=tid_, source_requirement=f"Prompt 11 API validation: {label}; checklist 25", priority="P1", test_layer="API", account="alice/bob/carol", input=label, steps="see label",
              expected=f"status in {expect}; problem document without stack traces; no write", actual=f"{r.status} {code(r) or ''} {(r.json or {}).get('detail','')[:80] if isinstance(r.json, dict) else r.text[:80]}; stack-trace-like text={leak}",
              status="PASS" if r.status in expect and not leak else "FAIL", evidence=qa.evidence(f"p11-{tid_}.json", {"status": r.status, "headers": {k: v for k, v in r.headers.items() if k.lower() in ('content-type', 'www-authenticate')}, "body": r.json if r.json is not None else r.text[:500]}))
    if tid_ == "P11-API-05" and r.status == 202:
        t = qa.trip("alice", r.json["tripId"]) or {}
        qa.record(test_id="P11-API-05b", source_requirement="Prompt 11 mass assignment: unknown fields ignored, nothing protected changed", priority="P0", test_layer="API", account="alice", input="admin:true in the body", steps="read back", expected="no effect", actual=f"status={t.get('status')} createdBy={t.get('createdBy')} keys={sorted(t.keys())[:12]}", status="PASS" if t.get("createdBy", "").endswith("alice") else "FAIL", evidence="p11-P11-API-05.json", ids=r.json["tripId"])

# ---- unauthenticated access to every prefix the edge proxies + a non-proxied one
prefixes = ["/api/v1/trips", "/api/v1/policies", "/api/v1/policy-decisions", "/api/v1/orders", "/api/v1/audit/events", "/api/v1/disruptions", "/api/v1/demand", "/api/v1/connectors", "/api/v1/learning/config", "/api/v1/suppliers/sandbox-air/events", "/api/v1/nope", "/actuator/health", "/api/v1/trips/../../healthz"]
un = {p: qa.api(None, "GET", p).status for p in prefixes}
qa.record(test_id="P11-AUTHZ-01", source_requirement="Prompt 11 every reachable operation without auth; edge allowlist", priority="P0", test_layer="API via the edge", account="none", input=str(prefixes), steps="GET without a token",
          expected="401 for proxied API prefixes; 404 for non-proxied paths (suppliers, actuator, unknown); no 200", actual=json.dumps(un), status="PASS" if all(v in (401, 404, 405) for v in un.values()) and un["/api/v1/suppliers/sandbox-air/events"] == 404 and un["/actuator/health"] == 404 else "FAIL", evidence=qa.evidence("p11-unauth.json", un))

# ---- response security headers and CORS on the edge
st, h = hdrs(f"{qa.BASE}/")
sec = {k: h.get(k) for k in ("Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Strict-Transport-Security", "Permissions-Policy", "Cache-Control")}
req = urllib.request.Request(f"{qa.BASE}/api/v1/trips", method="OPTIONS", headers={"Origin": "http://evil.example", "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization"})
try:
    with urllib.request.urlopen(req, timeout=10) as r:
        cors = {"status": r.status, "acao": r.headers.get("Access-Control-Allow-Origin")}
except urllib.error.HTTPError as e:
    cors = {"status": e.code, "acao": e.headers.get("Access-Control-Allow-Origin")}
qa.record(test_id="P11-HDR-01", source_requirement="Prompt 11 security headers, CORS, CSRF for the actual design (bearer in memory, no cookies)", priority="P2", test_layer="HTTP", account="none", input="GET / headers; OPTIONS /api/v1/trips from http://evil.example",
          steps="inspect", expected="no ACAO for a foreign origin (same-origin edge, no wildcard); CSRF not applicable (no cookie auth); recommended headers present (CSP, nosniff, frame-options, referrer-policy)",
          actual=f"headers={sec}; cors={cors}", status="PASS" if cors.get("acao") in (None, "") else "FAIL", evidence=qa.evidence("p11-headers.json", {"headers": sec, "cors": cors}), bug_id="" if sec.get("X-Content-Type-Options") and sec.get("Content-Security-Policy") else "BUG-09 (no CSP / nosniff / frame-options on the edge)")

# ---- reflected/stored XSS payload inert at the API (rendering proven in P10) and IDOR summary
qa.record(test_id="P11-XSS-01", source_requirement="Prompt 11 stored XSS payload (P3-VAL-06) and IDOR (P2-ISO-*)", priority="P0", test_layer="cross-reference", account="—", input="—", steps="—", expected="—",
          actual="stored verbatim as text (P3-VAL-06); rendered as text with no image element and no dialog (P10 states.htmlPurpose); IDOR: 404 everywhere across tenants and peers (P2-ISO-01/02/04/05)", status="PASS", evidence="results.csv")

# ---- redirect allowlist at the IdP: a foreign redirect_uri must be refused
url = f"{qa.KC}/realms/travelos/protocol/openid-connect/auth?client_id=travelos-web&response_type=code&redirect_uri=http://evil.example/cb&scope=openid&code_challenge=abc&code_challenge_method=S256"
st, h = hdrs(url)
qa.record(test_id="P11-REDIR-01", source_requirement="Prompt 2/11 OIDC redirect allowlist", priority="P0", test_layer="IdP HTTP", account="none", input="auth request with redirect_uri=http://evil.example/cb", steps="GET the authorization endpoint",
          expected="refused (400 'invalid redirect uri' page), never a redirect to evil.example", actual=f"{st} location={h.get('Location')}", status="PASS" if st == 400 or (st in (302, 303) and "evil.example" not in (h.get("Location") or "")) else "FAIL", evidence=qa.evidence("p11-redirect.json", {"status": st, "location": h.get("Location")}))

# ---- bundle and logs: no secrets, no bearer tokens
bundle = subprocess.run(["docker", "exec", "travelos-web-1", "sh", "-c", "grep -rlE 'client_secret|ANTHROPIC_API_KEY|password\\s*[:=]' /usr/share/nginx/html/assets/ 2>/dev/null | head -3; grep -roE 'oidcClientId|travelos-web' /usr/share/nginx/html/assets/*.js | head -2"], capture_output=True, text=True).stdout
logs = subprocess.run(["sh", "-c", "for c in travelos-travel-core-1 travelos-web-1 travelos-order-1 travelos-learning-1; do docker logs --since 3h $c 2>&1 | grep -cE 'Bearer eyJ|access_token\":\"eyJ'; done"], capture_output=True, text=True).stdout.split()
qa.record(test_id="P11-SECRETS-01", source_requirement="Prompt 11 bundles/storage/logs free of secrets and tokens", priority="P0", test_layer="container inspection", account="—", input="grep of the served bundle and 3 h of service logs",
          steps="docker exec grep", expected="no client secret or API key in the bundle (public OIDC client only); no bearer tokens in logs; tokens not in browser storage (P2-SES-03)", actual=f"bundle secret-like files: '{bundle.strip()[:120]}'; log lines containing bearer tokens per service (core, web, order, learning) = {logs}",
          status="PASS" if not bundle.strip().startswith("/") and all(x == "0" for x in logs) else "FAIL", evidence=qa.evidence("p11-secrets.json", {"bundle": bundle, "token_log_lines": logs}))

# ---- tampered/expired token replay: P2-TOK-01 and P2-TOK-03
qa.record(test_id="P11-TOKEN-01", source_requirement="Prompt 11 tampered/expired token replay", priority="P0", test_layer="cross-reference", account="—", input="—", steps="—", expected="—", actual="see P2-TOK-01 (tampered/forged/wrong issuer/audience: 401) and P2-TOK-03 (expired: 401 after 900 s)", status="PASS", evidence="results.csv")
print(qa.summary())
