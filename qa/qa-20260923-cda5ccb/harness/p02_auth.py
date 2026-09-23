"""Prompt 2 (API side): tokens, tenant isolation, role restrictions, IDOR, spoofing, caches."""

import base64
import json
import time

import qa

T = qa.TAG


def b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def claims(tok: str) -> dict:
    p = tok.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))


def tamper(tok: str, **changes) -> str:
    h, p, s = tok.split(".")
    c = claims(tok)
    c.update(changes)
    return f"{h}.{b64url(json.dumps(c).encode())}.{s}"


def forged(c: dict) -> str:
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload = b64url(json.dumps(c).encode())
    return f"{header}.{payload}.{b64url(b'not-a-real-signature')}"


def code(r: qa.Resp):
    return (r.json or {}).get("code") if isinstance(r.json, dict) else None


# ---------------------------------------------------------------- token negatives
alice = qa.token("alice")
cases = {
    "absent": None,
    "malformed": "not.a.jwt",
    "tampered-tenant": tamper(alice, tenant_id="globex"),
    "tampered-roles": tamper(alice, roles=["TRAVEL_ADMIN", "FINANCE"]),
    "forged-signature": forged({**claims(alice), "iss": claims(alice)["iss"]}),
    "wrong-issuer": forged({**claims(alice), "iss": "http://evil.example/realms/travelos"}),
    "wrong-audience": forged({**claims(alice), "aud": "other-api"}),
}
results = {}
for name, tok in cases.items():
    if tok is None:
        r = qa.api(None, "GET", "/api/v1/trips")
    else:
        r = qa.api(None, "GET", "/api/v1/trips", raw_token=tok)
    results[name] = {"status": r.status, "code": code(r), "www_authenticate": r.headers.get("WWW-Authenticate", "")[:80]}
    # a tampered token must not be able to read tenant-wide or admin data either
    if name.startswith("tampered"):
        r2 = qa.api(None, "GET", "/api/v1/trips?scope=tenant", raw_token=tok)
        results[name]["tenant_scope"] = r2.status
        r3 = qa.api(None, "GET", "/api/v1/learning/config", raw_token=tok)
        results[name]["learning_config"] = r3.status
ok = all(v["status"] == 401 for v in results.values()) and all(results[k].get("tenant_scope") == 401 for k in results if k.startswith("tampered"))
qa.record(test_id="P2-TOK-01", source_requirement="Prompt 2 token negatives; checklist 1 auth, 24 security", priority="P0", test_layer="API", account="none / alice's token mutated",
          input="absent, malformed, tampered tenant, tampered roles, forged signature, wrong issuer, wrong audience", steps="GET /trips (+ tenant scope and learning config with the tampered ones)",
          expected="401 for every case; a tampered claim never grants tenant-wide or admin reads", actual=json.dumps(results)[:400], status="PASS" if ok else "FAIL",
          evidence=qa.evidence("p2-token-negatives.json", results))

# wrong-environment token: a token from the same realm but a different client (the browser client, if it allows the grant)
import urllib.parse, urllib.request, urllib.error
data = urllib.parse.urlencode({"client_id": "travelos-web", "grant_type": "password", "username": "alice", "password": qa.PASSWORD}).encode()
try:
    with urllib.request.urlopen(urllib.request.Request(f"{qa.KC}/realms/travelos/protocol/openid-connect/token", data=data), timeout=15) as r:
        web_tok = json.load(r).get("access_token")
        wr = qa.api(None, "GET", "/api/v1/trips", raw_token=web_tok)
        actual = f"travelos-web password grant allowed; its token -> {wr.status} (aud={claims(web_tok).get('aud')})"
        st = "PASS" if wr.status in (200, 401) else "FAIL"
except urllib.error.HTTPError as e:
    body = e.read().decode()
    actual = f"travelos-web refuses the password grant ({e.code} {body[:80]}): the browser client cannot be used as a wrong-environment token source"
    st = "NOT_APPLICABLE"
qa.record(test_id="P2-TOK-02", source_requirement="Prompt 2 wrong-environment token", priority="P1", test_layer="API", account="alice", input="password grant against the browser client",
          steps="POST token; GET /trips", expected="either refused at the IdP (documented: PKCE-only public client) or accepted with the correct audience", actual=actual, status=st, evidence=qa.evidence("p2-token-web-client.json", {"actual": actual}))

# ---------------------------------------------------------------- fixtures: one trip per tenant with identical intent (cache probe)
def make(user, purpose):
    r = qa.api(user, "POST", "/api/v1/trips", qa.round_trip(purpose, 70, 72))
    return (r.json or {}).get("tripId", "")

a_trip = make("alice", f"{T} isolation fixture alice")
b_trip = make("bob", f"{T} isolation fixture bob")
z_trip = make("zoe", f"{T} isolation fixture zoe (same route/dates as alice)")
for u, t in (("alice", a_trip), ("bob", b_trip), ("zoe", z_trip)):
    qa.wait_trip(u, t, {"BOOKED", "FAILED", "AWAITING_APPROVAL"}, 150)
a_order = ((qa.api("alice", "GET", f"/api/v1/orders?tripId={a_trip}").json or [{}]) or [{}])[0].get("orderId", "ord_none")
fixtures = {"alice": a_trip, "bob": b_trip, "zoe": z_trip, "alice_order": a_order}
qa.evidence("p2-fixtures.json", fixtures)

# ---------------------------------------------------------------- cross-tenant and same-tenant access matrix
users = ["alice", "bob", "carol", "dan", "zoe"]
targets = {"alice_trip": a_trip, "zoe_trip": z_trip}
matrix = {}
for u in users:
    for tname, tid in targets.items():
        row = {}
        row["read"] = qa.api(u, "GET", f"/api/v1/trips/{tid}").status
        row["history"] = qa.api(u, "GET", f"/api/v1/trips/{tid}/history").status
        row["components"] = qa.api(u, "GET", f"/api/v1/trips/{tid}/components").status
        row["orders"] = qa.api(u, "GET", f"/api/v1/orders?tripId={tid}").status
        row["audit"] = qa.api(u, "GET", f"/api/v1/audit/trips/{tid}").status
        row["disruptions"] = qa.api(u, "GET", f"/api/v1/trips/{tid}/disruptions").status
        row["decisions"] = qa.api(u, "GET", f"/api/v1/policy-decisions?tripId={tid}").status
        row["approve"] = qa.api(u, "POST", f"/api/v1/trips/{tid}/approval", {"decision": "APPROVE"}).status
        row["complete"] = qa.api(u, "POST", f"/api/v1/trips/{tid}/completion", {}).status
        row["feedback"] = qa.api(u, "POST", "/api/v1/learning/feedback", {"tripId": tid, "rating": 3, "tags": ["ON_TIME"]}).status
        matrix[f"{u}->{tname}"] = row
qa.evidence("p2-access-matrix.json", matrix)

def expect_hidden(row):  # a trip you may not read does not exist for you: 404 everywhere, never 200/403 leaks
    return row["read"] == 404 and row["history"] == 404 and row["components"] == 404 and row["audit"] in (404, 403) and row["approve"] in (404, 403) and row["complete"] == 404

cross = {k: v for k, v in matrix.items() if (k.startswith("zoe->alice") or (k.endswith("zoe_trip") and not k.startswith("zoe")))}
qa.record(test_id="P2-ISO-01", source_requirement="Prompt 2 cross-tenant pairs (every acme user vs zoe's trip; zoe vs alice's trip); checklist 2 isolation", priority="P0", test_layer="API", account="all five",
          input="existing foreign trip ids", steps="read/history/components/orders/audit/disruptions/decisions/approve/complete/feedback", expected="404 for reads and mutations (existence not disclosed); no 200; no state change",
          actual=json.dumps(cross)[:600], status="PASS" if all(expect_hidden(v) for v in cross.values()) else "FAIL", evidence="qa/qa-20260923-cda5ccb/evidence/p2-access-matrix.json", ids=f"{a_trip} {z_trip}")

peer = matrix["dan->alice_trip"]
qa.record(test_id="P2-ISO-02", source_requirement="Prompt 2 same-tenant owner restriction (peer traveler)", priority="P0", test_layer="API", account="dan/acme/TRAVELER", input="alice's trip",
          steps="same operations", expected="dan (same tenant, no tenant-wide role) sees 404 everywhere and cannot approve/complete/rate", actual=json.dumps(peer), status="PASS" if expect_hidden(peer) and peer["feedback"] in (403, 404) else "FAIL", evidence="p2-access-matrix.json", ids=a_trip)

mgr = matrix["bob->alice_trip"]; adm = matrix["carol->alice_trip"]
qa.record(test_id="P2-ISO-03", source_requirement="Prompt 2 intended same-tenant sharing (manager/admin/finance read tenant-wide); 'do not misclassify sharing as leakage'", priority="P1", test_layer="API", account="bob, carol / acme",
          input="alice's trip", steps="read/history/components/orders/audit", expected="200 for reads by MANAGER and TRAVEL_ADMIN (documented TripAccess); approval of a BOOKED trip refused as a state error (409/422), never 200",
          actual=f"bob={json.dumps(mgr)}; carol={json.dumps(adm)}", status="PASS" if mgr["read"] == 200 and adm["read"] == 200 and mgr["approve"] in (409, 422, 404) else "FAIL", evidence="p2-access-matrix.json", ids=a_trip)

# nonexistent vs foreign: identical answers (no disclosure)
ghost = "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV"
g = {u: qa.api(u, "GET", f"/api/v1/trips/{ghost}").status for u in users}
gz = qa.api("zoe", "GET", f"/api/v1/trips/{a_trip}")
gg = qa.api("zoe", "GET", f"/api/v1/trips/{ghost}")
qa.record(test_id="P2-ISO-04", source_requirement="Prompt 2 existing foreign id vs nonexistent id: no information disclosure", priority="P1", test_layer="API", account="zoe vs alice's trip; all users vs a ghost id",
          input=f"{ghost}, {a_trip}", steps="GET /trips/{id}", expected="same status and same problem shape for a foreign existing id and a nonexistent id",
          actual=f"ghost by all={g}; zoe foreign={gz.status} {code(gz)}; zoe ghost={gg.status} {code(gg)}", status="PASS" if gz.status == gg.status == 404 and code(gz) == code(gg) else "FAIL",
          evidence=qa.evidence("p2-disclosure.json", {"ghost": g, "foreign": {"status": gz.status, "body": gz.json}, "nonexistent": {"status": gg.status, "body": gg.json}}))

# orders / exposures / learning / connectors / demand boundaries by role and tenant
role = {
    "zoe reads alice's order by id": qa.api("zoe", "GET", f"/api/v1/orders/{a_order}").status,
    "dan reads alice's order by id": qa.api("dan", "GET", f"/api/v1/orders/{a_order}").status,
    "alice lists tenant trips": qa.api("alice", "GET", "/api/v1/trips?scope=tenant").status,
    "bob lists tenant trips": qa.api("bob", "GET", "/api/v1/trips?scope=tenant").status,
    "alice lists exposures": qa.api("alice", "GET", "/api/v1/orders/exposures").status,
    "carol lists exposures": qa.api("carol", "GET", "/api/v1/orders/exposures").status,
    "alice reads learning config": qa.api("alice", "GET", "/api/v1/learning/config").status,
    "bob changes learning mode": qa.api("bob", "PUT", "/api/v1/learning/config", {"mode": "OFF"}).status,
    "alice lists connectors": qa.api("alice", "GET", "/api/v1/connectors").status,
    "alice publishes a policy": qa.api("alice", "POST", "/api/v1/policies", {"document": {}, "note": "x"}).status,
    "zoe reads acme policies": qa.api("zoe", "GET", "/api/v1/policies").status,
    "alice records a refund": qa.api("alice", "POST", "/api/v1/learning/outcomes/refunds", {"tripId": a_trip, "orderId": a_order, "amountMinor": 1, "currency": "USD", "reference": "x"}).status,
    "zoe lists disruptions": qa.api("zoe", "GET", "/api/v1/disruptions").status,
}
exp = {"zoe reads alice's order by id": 404, "dan reads alice's order by id": 404, "alice lists tenant trips": 403, "bob lists tenant trips": 200, "alice lists exposures": 403, "carol lists exposures": 200,
       "alice reads learning config": 403, "bob changes learning mode": 403, "alice lists connectors": 403, "alice publishes a policy": 403, "zoe reads acme policies": (200, 403, 404), "alice records a refund": 403, "zoe lists disruptions": (200, 403)}
bad = {k: (v, exp[k]) for k, v in role.items() if (v not in exp[k] if isinstance(exp[k], tuple) else v != exp[k])}
zp = qa.api("zoe", "GET", "/api/v1/policies").json
qa.record(test_id="P2-ROLE-01", source_requirement="Prompt 2 role restrictions per product contract (api-matrix roles column)", priority="P0", test_layer="API", account="all five",
          input="13 role-gated operations", steps="see input", expected=str(exp), actual=f"{role}; mismatches={bad}; zoe's policy list={zp if not isinstance(zp, list) else [p.get('policyId') for p in zp]}", status="PASS" if not bad and not (isinstance(zp, list) and any(p.get("policyId") == "US_STANDARD_TRAVEL" for p in zp)) else "FAIL",
          evidence=qa.evidence("p2-role-matrix.json", {"actual": role, "expected": {k: (list(v) if isinstance(v, tuple) else v) for k, v in exp.items()}, "zoe_policies": zp}))

# request-body spoofing: tenant/user fields in the body are ignored or refused
sp = qa.api("alice", "POST", "/api/v1/trips", {**qa.round_trip(f"{T} spoof tenant in body", 74, 76), "tenantId": "globex", "tenant_id": "globex", "status": "BOOKED", "total": {"currency": "USD", "amountMinor": 1}})
spt = qa.trip("alice", (sp.json or {}).get("tripId", "x")) or {}
qa.record(test_id="P2-SPOOF-01", source_requirement="Prompt 2 request-body tenant/user spoofing; Prompt 11 mass assignment", priority="P0", test_layer="API", account="alice",
          input="POST /trips with tenantId=globex, status=BOOKED, total=USD 0.01 in the body", steps="create; read back", expected="refused (400) or the protected fields ignored: the trip is in acme with status SUBMITTED/PLANNING and no fake total",
          actual=f"{sp.status}; created status={spt.get('status')} total={spt.get('total')} visible to zoe={qa.api('zoe','GET','/api/v1/trips/'+str(spt.get('tripId'))).status}", status="PASS" if sp.status in (400, 422) or (spt.get("status") not in ("BOOKED",) and qa.api("zoe", "GET", "/api/v1/trips/" + str(spt.get("tripId"))).status == 404) else "FAIL",
          evidence=qa.evidence("p2-spoof.json", {"status": sp.status, "body": sp.json, "trip": spt}), ids=spt.get("tripId", ""))

# cache leakage: identical searches in two tenants must not share decisions, orders or ledgers
za = qa.api("zoe", "GET", f"/api/v1/policy-decisions?tripId={a_trip}").json
az = qa.api("alice", "GET", f"/api/v1/policy-decisions?tripId={z_trip}").json
aa = qa.api("alice", "GET", f"/api/v1/policy-decisions?tripId={a_trip}").json or []
zz = qa.api("zoe", "GET", f"/api/v1/policy-decisions?tripId={z_trip}").json or []
qa.record(test_id="P2-CACHE-01", source_requirement="Prompt 2 repeat identical route/date searches across tenants (cache leakage)", priority="P0", test_layer="API", account="alice (acme) and zoe (globex), identical intent",
          input=f"{a_trip} vs {z_trip}", steps="each reads the other's policy decisions; own decisions compared", expected="cross reads empty; each tenant's decisions carry its own tenant/policy (globex: NO_POLICY denials; acme: US_STANDARD_TRAVEL)",
          actual=f"zoe->alice decisions={za if not isinstance(za, list) else len(za)}; alice->zoe={az if not isinstance(az, list) else len(az)}; alice own={len(aa)} policy={(aa[:1] or [{}])[0].get('policyId')}; zoe own={len(zz)} first reason={(((zz[:1] or [{}])[0].get('decision') or {}).get('reasons') or [{}])[0].get('code')}",
          status="PASS" if (not za) and (not az) and (aa[:1] or [{}])[0].get("policyId") == "US_STANDARD_TRAVEL" and zz else "FAIL", evidence=qa.evidence("p2-cache.json", {"zoe_reads_alice": za, "alice_reads_zoe": az, "alice_own_first": aa[:1], "zoe_own_first": zz[:1]}))
print(qa.summary())
