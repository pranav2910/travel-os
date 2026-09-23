"""Turns the browser suites' evidence (p10-frontend.json, p10b-keyboard.json) into ledger rows, the
same ten rows the audit recorded, judged by the same rules."""
import json

import qa

f = json.load(open(qa.EVIDENCE / "p10-frontend.json"))
k = json.load(open(qa.EVIDENCE / "p10b-keyboard.json"))
a11y = f.get("a11y", {})
viol = {p: v.get("violations", []) for p, v in a11y.items()}
bad = {p: v for p, v in viol.items() if v}
qa.record(test_id="P10-A11Y-01", source_requirement="Prompt 10 accessibility (axe wcag2a/aa on every route incl. a trip page); checklist 22", priority="P1", test_layer="browser (Chromium + axe)", account="alice", input=", ".join(sorted(viol)), steps="axe analyze per route",
          expected="no violations (BUG-13 was the trip page's 4.26:1 pending step)", actual=f"routes scanned={len(viol)}; violations={bad or 'none'}", status="PASS" if viol and not bad and "/trips/:id" in viol else ("FAIL" if bad else "BLOCKED"), evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10-frontend.json", bug_id="" if not bad else "BUG-13")
st = f.get("states", {})
down = st.get("tripsApiDown", {})
qa.record(test_id="P10-STATE-01", source_requirement="Prompt 10 states: API outage shown as an error, never as an empty list", priority="P1", test_layer="browser", account="alice", input="route /api/v1/trips -> 503", steps="open /trips", expected="an error message, no empty-list success",
          actual=f"text={down.get('text', '')[:120]!r}; showsEmptyListAsSuccess={down.get('showsEmptyListAsSuccess')}", status="PASS" if down and down.get("showsEmptyListAsSuccess") is False else "FAIL", evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10-trips-api-down.png")
rec = f.get("recovery", {})
off = rec.get("offlineSubmit", {})
qa.record(test_id="P10-NET-01", source_requirement="Prompt 10 network: offline during submit, then retry", priority="P0", test_layer="browser", account="alice", input="setOffline(true) at Confirm", steps="confirm offline; back online; confirm again",
          expected="no phantom success; the form is retained; the retry lands on the trip", actual=f"stillOnForm={off.get('stillOnForm')} phantomSuccess={off.get('phantomSuccess')}; retry={rec.get('retryAfterOnline')}", status="PASS" if off.get("phantomSuccess") is False and off.get("stillOnForm") and (rec.get("retryAfterOnline") or {}).get("url", "").startswith("/trips/trip_") else "FAIL", evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10-offline-submit.png")
qa.record(test_id="P10-LOAD-01", source_requirement="Prompt 10 loading state and rapid confirm", priority="P1", test_layer="browser", account="alice", input="2.5 s delayed trip API; triple-click Confirm", steps="reload; click x3",
          expected="a loading state is visible; one trip from three clicks", actual=f"slowLoading={rec.get('slowLoading')}; rapidConfirm={rec.get('rapidConfirm')}", status="PASS" if (rec.get("slowLoading") or {}).get("loadingVisible") and (rec.get("rapidConfirm") or {}).get("tripUrl", "").startswith("/trips/trip_") else "FAIL", evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10-frontend.json")
resp = f.get("responsive", {})
overflow = {w: [p for p, v in pages.items() if isinstance(v, dict) and v.get("overflow")] for w, pages in resp.items() if isinstance(pages, dict)}
qa.record(test_id="P10-RESP-01", source_requirement="Prompt 10 responsive widths 320..1920, tablet landscape, 200 % zoom; checklist 21", priority="P1", test_layer="browser", account="alice", input=", ".join(sorted(resp)), steps="set viewport; measure document width",
          expected="no horizontal overflow at any width", actual=f"overflowing routes per width={ {w: v for w, v in overflow.items() if v} or 'none'}", status="PASS" if resp and not any(overflow.values()) else "FAIL", evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10-frontend.json")
qa.record(test_id="P10-UX-01", source_requirement="Prompt 10 first-time user confusion (human observation)", priority="P2", test_layer="browser (heuristic)", account="—", input="—", steps="—", expected="a moderated session", actual="not run: no human participant; the guided journey screenshots (p01-*.png) stand in", status="BLOCKED", evidence="qa/qa-20260923-repair-cda5ccb/evidence/p01-*.png")
kb = k.get("keyboard", {})
qa.record(test_id="P10-KEY-01", source_requirement="Prompt 10 keyboard-only journey, focus ring, dialog focus, field errors; checklist 22", priority="P1", test_layer="browser", account="alice", input="Tab/Enter only", steps="sign in, New trip, fill, submit, cancel dialog, form errors",
          expected="reachable by keyboard; visible focus; dialog traps and restores focus; errors associated and announced", actual=json.dumps({x: kb.get(x) for x in ('signInReachable', 'newTripLinkReachable', 'submitted', 'focusRing', 'dialog', 'errors')})[:400],
          status="PASS" if kb.get("signInReachable") and kb.get("newTripLinkReachable") and kb.get("submitted") and (kb.get("dialog") or {}).get("focusInsideOnOpen") and (kb.get("errors") or {}).get("withDescribedBy", 0) > 0 else "FAIL", evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10b-keyboard.json; p10-form-errors.png")
xss = k.get("xss", {})
qa.record(test_id="P10-XSS-01", source_requirement="Prompt 10/11 stored HTML purpose renders as text", priority="P0", test_layer="browser", account="alice", input="trip whose purpose is '<img src=x onerror=alert(1)>'", steps="open list and detail", expected="no image element, no dialog",
          actual=json.dumps(xss)[:300], status="PASS" if xss.get("rowVisible") and xss.get("injectedImg") == 0 and xss.get("dialogs", 0) == 0 else ("BLOCKED" if not xss.get("rowVisible") else "FAIL"), evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10-xss-detail.png")
contrast = k.get("contrast", [])
qa.record(test_id="P10-A11Y-02", source_requirement="Prompt 10 contrast on the trip page (BUG-13: the pending step read at 4.26:1)", priority="P2", test_layer="browser (axe)", account="alice", input="/trips/:id after a keyboard-only submit", steps="axe wcag2aa", expected="no color-contrast violation",
          actual=f"violations={contrast or 'none'}", status="PASS" if not contrast else "FAIL", evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10b-keyboard.json", bug_id="" if not contrast else "BUG-13")
eng = k.get("engines", {})
qa.record(test_id="P10-ENG-01", source_requirement="Prompt 10 browsers/devices; checklist 20", priority="P2", test_layer="browser engines", account="alice", input="Chromium, WebKit; Firefox not installed", steps="smoke per engine",
          expected="Chromium full, WebKit smoke; others BLOCKED", actual=json.dumps(eng)[:300], status="PASS" if (eng.get("webkitSmoke") or {}).get("tripsHeading") else "FAIL", evidence="qa/qa-20260923-repair-cda5ccb/evidence/p10-webkit-trips.png")
print(qa.summary())
