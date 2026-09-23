"""Builds docs/frontend/ui-tour.md and ui-tour.html from one description of the screens, so the
Markdown in the repository and the shareable page never drift. Screenshots come from capture.js."""
import html
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
DOCS = HERE.parent
REL = "ui-tour"  # screenshot folder relative to ui-tour.md

CHAPTERS = [
  ("Signing in", "Every screen sits behind the company identity provider; the sandbox chip is on every page.", [
    ("01-sign-in-page.png", "The front door", "anyone",
     "Before anything else the app shows one button. There is no local password form: the button starts the OIDC (PKCE) sign-in with Keycloak, which stands in for the company's identity provider.",
     [("Sign in", "starts the redirect to the identity provider; nothing else is reachable without it")]),
    ("02-keycloak-login.png", "The identity provider's login", "anyone",
     "Credentials are typed on the identity provider's own page, never in the app. The sandbox realm seeds five users: alice (traveler), bob (manager), carol (travel admin + Finance), dan (traveler), zoe (another tenant).",
     [("Username / Password", "the realm's seeded dev users"), ("Sign in", "returns to the app with an authorization code; tokens stay in memory (no cookies, no local storage)")]),
    ("03-overview-alice.png", "Overview", "every signed-in user",
     "The landing page after sign-in: who you are (name, tenant, roles in the banner), how many trips are in progress or booked, whether detected travel demand is waiting for you, and your most recent trips with their live status and exact totals.",
     [("Sandbox — simulated bookings", "the chip says which environment this is; it never leaves the header"), ("New trip", "the primary action, always one click away"), ("Recent trips", "each row links to the trip; totals show the currency explicitly"), ("Navigation", "shows only the areas your roles allow: a traveler sees My travel and Demand; managers add Approvals; travel admins and Finance add Disruptions, Finance, Connectors and Learning")]),
    ("42-signed-out.png", "Signed out", "anyone",
     "Sign out ends the session at the identity provider too and returns to the front door; cached data belongs to the account that loaded it and is cleared when the account changes.",
     [("Sign in", "a different user can sign in without seeing the previous user's data")]),
  ]),
  ("Your trips", "Everything you asked for, newest first, with the truth about where each one stands.", [
    ("04-trips-list.png", "Trips", "traveler (own trips); managers, travel admins and Finance see the tenant's trips in their inboxes",
     "One row per request: the purpose you typed, the route, the status badge (Submitted, Planning, Awaiting approval, Approved, Booking, Booked, Cancelling, Cancelled, Failed, Completed), the total when there is one, and when it last changed. Other people's trips are simply not here — the server answers 404 for anything you may not see.",
     [("Row link", "opens the trip"), ("Status badge", "the platform's own state, never inferred by the browser"), ("Total", "exact minor units with the currency; a dash means nothing was booked")]),
    ("40-phone-trips-list.png", "Trips on a phone", "everyone",
     "The same list at 390 px: the navigation folds, rows stack, nothing scrolls sideways.",
     [("Navigation", "opens from the header at phone width")]),
  ]),
  ("Asking for a trip", "Tell the platform what you need; submitting books it.", [
    ("05-new-trip-empty.png", "New trip", "traveler; managers and travel admins may arrange for others",
     "Four ways to ask: a round trip, a one-way, a multi-city itinerary with hotels and transfers, or a description in words. The page says what submitting means before you type anything.",
     [("What kind of trip", "switches the form; nothing is lost when you switch back"), ("Purpose of travel", "shown to approvers and kept with the trip"), ("Submitting books the trip", "the honest warning: this is not a preview; the platform searches, applies policy, and books with the simulated suppliers, waiting for an approver only when policy says so")]),
    ("06-new-trip-round-trip-filled.png", "A round trip, filled in", "traveler",
     "Airports are three-letter codes from the platform's catalog (about a hundred airports; a city code such as NYC is refused with the airports it stands for). Windows are given as a date plus earliest/latest times on the UTC clock the sandbox airline schedules on.",
     [("From / To", "airport codes; validated against the catalog when you submit"), ("Outbound / Return", "date and time window; the return must not be before the outbound"), ("A hotel is required", "asks for a stay on the nights between the flights"), ("Review and submit", "validates in the browser first, then shows the review step")]),
    ("07-new-trip-review.png", "Review before it books", "traveler",
     "The review step repeats exactly what will be sent and repeats that confirming books. A double click or a lost answer cannot create two trips: the request carries a fixed idempotency key.",
     [("Confirm and submit", "creates the trip and opens it; the button disables while the answer is pending"), ("Back", "returns to the form with everything kept")]),
    ("43-new-trip-free-text.png", "Describe it in words", "traveler",
     "Free text is understood into a structured intent. In the sandbox the understanding is a deterministic offline extractor: it needs airport codes and YYYY-MM-DD dates (\"Fly BOS to SEA on 2026-10-06, back 2026-10-08, hotel\"). With a model key configured, the real model does this instead. What was concluded is always shown on the trip page, as data.",
     [("Describe the trip", "one message, one intent; there is no back-and-forth conversation (stated in the README's pilot scope)")]),
    ("10-new-trip-multi-city.png", "A multi-city itinerary", "traveler",
     "Ordered legs that must chain (each departs where the previous landed), stays in a city a leg lands in on the property's local dates, and airport transfers hanging off a leg. Every component gets a stable id and is reported on the trip page as it is planned, quoted and booked.",
     [("Add leg / Add stay / Add transfer", "up to eight legs; stays need check-in/check-out local dates; a transfer names its city and kind"), ("Remove …", "each component can be dropped before submitting")]),
    ("37-new-trip-arranging-for-someone.png", "Arranging for someone else", "manager, travel admin",
     "A manager or travel admin may request a trip for another employee. Because the reservation is made in the traveler's name, the form asks for that person's name and email; the platform refuses an anonymous arranged trip.",
     [("Traveler employee id", "who travels; leave empty to travel yourself"), ("Traveler first name / last name / email", "required as soon as an employee id is given; stored with the trip and used for the booking")]),
    ("12-new-trip-validation-errors.png", "Validation before anything leaves the browser", "traveler",
     "Missing or inconsistent fields are marked on the field itself and announced to assistive technology; nothing is sent until they are fixed.",
     [("Field errors", "associated with their inputs (aria-describedby) and read by screen readers")]),
    ("13-new-trip-unknown-airport-refused.png", "The server refuses what the browser cannot know", "traveler",
     "Some rules live only on the server: an airport outside the catalog, a departure window that has already closed, a currency the suppliers do not quote. The refusal comes back as a problem document with a stable code and a sentence you can act on, shown in place; the form keeps your input.",
     [("The request was refused", "the server's code and detail (here UNKNOWN_LOCATION); nothing was created")]),
  ]),
  ("Watching it get booked", "The trip page tells you where it is, what was chosen and why.", [
    ("08-trip-planning.png", "Just submitted", "traveler",
     "Seconds after confirming: the progress strip shows Submitted done and Search, policy, optimizer current. The page polls with backoff until the platform reaches a resting state.",
     [("Progress strip", "Submitted → Search, policy, optimizer → Approval → Booking → Booked; each step is done, current, pending, skipped (not needed) or failed"), ("Cancel trip", "available while nothing is booked yet; stops the planning")]),
    ("09-trip-booked.png", "Booked", "traveler",
     "The whole story on one page: the request as frozen, the outcome (total, whether approval was needed, the order and policy-decision ids), the narration of the choice, the booking with its supplier reference, record locator and each segment, Why this option (policy verdicts, the optimizer's ranking, the order confirmation), the timeline of every status change with who or what caused it, and a feedback form once the trip exists.",
     [("Cancel trip", "releases the reservation at the suppliers first (see the cancellation chapter)"), ("Confirm the trip happened", "completion is attested by a person after the last arrival, never inferred from a timer"), ("Policy decisions (n)", "expands every candidate policy judged, with reason codes"), ("What the model concluded", "for free-text trips: the extraction, its confidence and assumptions"), ("Your feedback", "rating, tags and a comment read by people; it never influences rankings")]),
    ("11-trip-multi-city-booked.png", "A multi-city trip, booked", "traveler",
     "Each leg, stay and transfer is a component with its own status, supplier reference and price; the booking lists the flights in UTC and the hotel and transfer on their local clocks.",
     [("Itinerary components", "one line per component with its supplier and reference"), ("Booking table", "every item of the order, its status, reference and exact total")]),
    ("41-phone-trip-detail.png", "The trip page on a phone", "traveler",
     "The same content stacked in one column; buttons stay reachable at 390 px.",
     []),
  ]),
  ("When policy needs a person", "Policy decides deterministically; a person decides when policy says so.", [
    ("14-trip-awaiting-approval-traveler.png", "Awaiting approval, seen by the traveler", "traveler",
     "The platform found an option, policy said it needs a manager, and the trip waits. The traveler sees the plan and the reason, and cannot approve their own trip — the server refuses self-approval even if the button were forged.",
     [("You cannot approve your own trip", "who decides is stated; the traveler can still cancel")]),
    ("15-approvals-inbox-bob.png", "Approvals inbox", "manager, travel admin",
     "Every trip of the tenant that is waiting for a decision, and every disruption recovery that needs one (next chapter). Managers see this area; travelers do not have it in their navigation and get a refusal if they type the address.",
     [("Trips awaiting approval", "each row opens the trip"), ("Recoveries needing a decision", "disruption recoveries above the autonomy limit")]),
    ("16-trip-awaiting-approval-manager.png", "The manager's decision", "manager, travel admin",
     "The same trip page with the decision controls: the cost, the policy reasons, the optimizer's ranking, and Approve / Reject with an optional comment for the traveler. A stale decision (the trip moved on) is refused with a conflict, never applied twice.",
     [("Comment for the traveler (optional)", "kept with the decision"), ("Approve", "the workflow continues to booking"), ("Reject", "the trip ends as Cancelled with the rejection recorded")]),
    ("17-trip-approved-then-booked.png", "Approved, then booked", "manager",
     "After the decision the page follows the trip: Approved, Booking, Booked, with the approval (who, when, comment) in the outcome and the timeline.",
     []),
    ("24-trip-denied-by-policy.png", "Denied by policy", "traveler",
     "When no candidate is permitted (here a trip budget of USD 1.00 with DENY), the trip ends Failed at the policy step, with the denial reasons policy gave — its own words, per candidate — and nothing booked.",
     [("Not booked", "the failure stage and code, and the distinct reasons policy gave, most frequent first")]),
  ]),
  ("Cancelling", "A booked trip is never shown as cancelled before the supplier has released it.", [
    ("18-cancel-dialog-booked.png", "Cancel a booked trip", "traveler, travel admin",
     "The dialog says what will happen: the reservation is released at the suppliers first, the trip reads Cancelling until every component is released, and Cancelled only then. A refund is not assumed until Finance records it.",
     [("Reason", "required; kept in the timeline"), ("Cancel the trip", "asks the platform to release the reservation"), ("Keep it", "closes the dialog; focus returns to the button")]),
    ("19-trip-cancelling.png", "Cancelling", "traveler",
     "The moment after confirming: the trip is Cancelling and the Order service is releasing the components at the suppliers. The Cancel button is gone (a repeat would change nothing).",
     [("Cancelling", "an honest intermediate state; the page keeps polling")]),
    ("20-trip-cancelled.png", "Cancelled", "traveler",
     "Every component was released: the order is Cancelled, the items are Cancelled, and only now does the trip read Cancelled. The timeline shows the request, the release and the completion, each with its actor.",
     []),
    ("21-trip-cancelling-needs-a-person.png", "A supplier refused", "traveler",
     "The LAX hotel in the sandbox sells a non-refundable rate that refuses cancellation. The flights are released, the room is not: the trip stays Cancelling (needs a person), the order is cancellation pending with the room marked Cancel failed, and a Financial exposure is recorded. The trip does not claim to be cancelled while the room is still confirmed at the hotel.",
     [("Cancellation incomplete", "what was refused and what happens next"), ("Financial exposure", "the open liability, in the order card, with the supplier's own reason")]),
    ("22-finance-open-exposure.png", "Finance: open exposures", "travel admin, Finance",
     "Money the company may still owe, one row per refused release, with the order, trip, supplier, amount and the supplier's reason. A person deals with the supplier (a phone call, a written-off night) and closes the exposure here; the platform completes the cancellation from that.",
     [("Show", "open liabilities or resolved ones"), ("Resolve", "records how it was settled; idempotent, and a second different resolution is refused"), ("Settled refunds", "Finance records what a supplier actually refunded, in the order's currency; zero is a legitimate settlement, a foreign currency is refused")]),
    ("23-trip-cancelled-after-resolution.png", "Cancelled, once a person released it", "traveler",
     "After Finance resolved the exposure the order became Cancelled and the trip followed: Cancelled, with the refusal and the resolution both in the narration and the timeline.",
     []),
  ]),
  ("When the airline cancels", "Disruptions are detected from supplier notices and recovered within policy.", [
    ("25-disruption-needs-a-person-traveler.png", "A cancelled flight", "traveler",
     "The sandbox airline sent a signed FLIGHT_CANCELLED notice. The platform confirmed the impact on the booked trip, searched alternatives, let policy judge them and picked a replacement. Its incremental cost (USD 180) is above the USD 100 autonomy limit, so a person decides — and the traveler cannot approve a change to their own trip.",
     [("What happened", "the notice as received: flight, date, severity, supplier reference"), ("Recovery", "the replacement, its incremental cost, the policy decision and whose approval it needs"), ("The decision record", "candidates searched, permitted, feasible, and every rejected one with its reason")]),
    ("26-approvals-inbox-recoveries.png", "Recoveries in the manager's inbox", "manager, travel admin",
     "Recovery decisions sit beside trip approvals.",
     []),
    ("27-disruption-manager-decision.png", "Approving the replacement", "manager, travel admin",
     "The manager sees the same evidence plus the buttons. Approving executes the change; rejecting leaves the booking as it is for a person to handle by hand.",
     [("Approve the replacement", "the Order service changes the booking at the supplier"), ("Reject", "no change is made")]),
    ("28-disruption-resolved.png", "Resolved", "everyone involved",
     "The change went through: the new flights are booked, the old ones replaced, the history shows every step with its timestamp.",
     []),
    ("29-trip-after-recovery.png", "The trip after recovery", "traveler",
     "The trip page shows the change with its incremental cost, distinct from the original booking, so the total the company paid is explained line by line.",
     [("change(s) from disruption recovery", "what was replaced, retimed or re-dated, and by how much the price moved")]),
    ("30-operations-disruptions.png", "Disruptions", "travel admin, Finance",
     "Every disruption of the tenant with its state, for the people who run travel.",
     []),
  ]),
  ("Demand detected from the calendar", "The platform notices travel before anyone asks.", [
    ("31-demand-inbox.png", "Demand inbox", "traveler",
     "Accepted in-person meetings away from your work location, seen in the (simulated) calendar and cross-checked with HRIS and CRM, become candidates: what, where, when, and why the platform thinks it is travel. Virtual, declined and local events never appear.",
     [("Show", "actionable, needs review, dismissed or converted"), ("Row link", "opens the candidate with its evidence")]),
    ("32-demand-candidate.png", "A candidate and its evidence", "traveler",
     "Each source is shown as data — the calendar event, the HRIS identity, the CRM visit — with the rule that fired and what is missing. Injected text in an event title is quoted, never obeyed.",
     [("Convert into a trip", "one trip per candidate, however many times it is clicked; the trip records the candidate it came from"), ("Dismiss", "with a reason; no trip is ever created for it"), ("Supply details", "when the destination could not be resolved")]),
    ("33-trip-from-demand.png", "The trip it became", "traveler",
     "A trip like any other, whose source is DEMAND and whose ledger names the candidate; it goes through the same policy, approval and booking.",
     []),
    ("34-connectors-admin.png", "Connectors", "travel admin",
     "The enterprise sources (calendar, CRM, HRIS, expense), each labelled simulated, with their sync runs, status and configuration.",
     [("Sync now", "runs one synchronization and shows its outcome"), ("Runs", "every run with items seen, candidates created, errors")]),
  ]),
  ("Learning and money", "Two admin areas that say exactly what they do.", [
    ("35-learning-admin.png", "Learning", "travel admin",
     "The learned-preference loop: its mode (Off, Shadow, Active), the evidence it was built from, profiles with their bounded adjustments, and honest evaluation wording — in Shadow the learned pick is recorded, not applied. Activation and rollback are versioned; a stale change is refused with a conflict.",
     [("Mode", "changing it needs the current version"), ("Build / Activate / Roll back", "each recorded in the activation history")]),
    ("36-finance-refunds-and-exposures.png", "Finance", "travel admin, Finance",
     "Exposures (above) and settled refunds: Finance records what a supplier actually refunded; a cancellation never implies a refund.",
     [("Record settled refund", "trip, order, optional item, amount in the order's currency, and the supplier or bank reference")]),
  ]),
  ("Edges", "What the app does when something is not for you, or not there.", [
    ("38-not-available-for-role.png", "Not available for your role", "traveler at an admin address",
     "Typing /finance as a traveler shows a plain refusal, and the server would refuse the data anyway.",
     []),
    ("39-trip-not-found.png", "Not here yet", "anyone",
     "A trip that does not exist — or that belongs to someone else — is not found; existence is never disclosed. The page offers the way back.",
     []),
  ]),
]

def md():
  out = ["# UI tour — travel-os web app", "",
         "Every screen of the web app, captured from the running local stack (sandbox suppliers) by `ui-tour/capture.js` on 2026-09-23 (commit after `b4e2fe1`). One section per screen: what it shows, who sees it, what each control does. Re-take the screenshots with:", "",
         "```", "make images && make stack-up", "cd web && NODE_PATH=$PWD/node_modules node ../docs/frontend/ui-tour/capture.js", "python3 docs/frontend/ui-tour/build.py", "```", ""]
  out.append("## Chapters"); out.append("")
  for i, (title, _, _) in enumerate(CHAPTERS, 1):
    out.append(f"{i}. [{title}](#{i}-{title.lower().replace(' ', '-').replace(',', '').replace('’', '')})")
  out.append("")
  for i, (title, lede, screens) in enumerate(CHAPTERS, 1):
    out += [f"## {i}. {title}", "", lede, ""]
    for j, (img, name, who, what, controls) in enumerate(screens, 1):
      out += [f"### {i}.{j} {name}", "", f"*Who sees it:* {who}", "", f"![{name}]({REL}/{img})", "", what, ""]
      if controls:
        out.append("| Control | What it does |"); out.append("|---|---|")
        for c, d in controls: out.append(f"| {c} | {d} |")
        out.append("")
  (DOCS / "ui-tour.md").write_text("\n".join(out))

def page():
  e = html.escape
  toc = "".join(f'<li><a href="#c{i}"><span class="n">{i}</span>{e(t)}</a></li>' for i, (t, _, _) in enumerate(CHAPTERS, 1))
  body = []
  for i, (title, lede, screens) in enumerate(CHAPTERS, 1):
    body.append(f'<section class="chapter" id="c{i}"><header><span class="kicker">Chapter {i}</span><h2>{e(title)}</h2><p class="lede">{e(lede)}</p></header>')
    for j, (img, name, who, what, controls) in enumerate(screens, 1):
      phone = img.startswith("40-") or img.startswith("41-")
      ctl = "".join(f"<div class=\"ctl\"><dt>{e(c)}</dt><dd>{e(d)}</dd></div>" for c, d in controls)
      body.append(f'''<article class="screen{' screen--phone' if phone else ''}" id="s{i}-{j}">
  <h3><span class="num">{i}.{j}</span> {e(name)}</h3>
  <p class="who"><span class="who__label">Who sees it</span> {e(who)}</p>
  <figure><a href="{img}" target="_blank" rel="noopener"><img src="{img}" alt="{e(name)}" loading="lazy"></a><figcaption>{e(img)}</figcaption></figure>
  <p class="what">{e(what)}</p>
  {'<dl class="controls">' + ctl + '</dl>' if controls else ''}
</article>''')
    body.append("</section>")
  doc = f'''<title>travel-os UI tour</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,500;9..144,600&family=Public+Sans:ital,wght@0,400;0,500;0,600;1,400&display=swap">
<style>
:root{{--bg:#f6f7f4;--surface:#ffffff;--ink:#1b2420;--muted:#5b665f;--line:#d9ded8;--accent:#1f4d2e;--accent-soft:#e3efe6;--accent-ink:#163a22;--warn:#8a5a10;--warn-soft:#fbf1dc;--frame:#c9d1cb;--radius:8px;--display:"Fraunces",Georgia,"Times New Roman",serif;--body:"Public Sans",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color-scheme:light}}
@media (prefers-color-scheme: dark){{:root:not([data-theme="light"]){{--bg:#141916;--surface:#1b211d;--ink:#e8ece7;--muted:#9fa9a2;--line:#2c352f;--accent:#7cc493;--accent-soft:#1e2c23;--accent-ink:#bfe4ca;--warn:#e0b160;--warn-soft:#2b2417;--frame:#3a453e;color-scheme:dark}}}}
:root[data-theme="dark"]{{--bg:#141916;--surface:#1b211d;--ink:#e8ece7;--muted:#9fa9a2;--line:#2c352f;--accent:#7cc493;--accent-soft:#1e2c23;--accent-ink:#bfe4ca;--warn:#e0b160;--warn-soft:#2b2417;--frame:#3a453e;color-scheme:dark}}
*{{box-sizing:border-box}} body{{margin:0;background:var(--bg);color:var(--ink);font-family:var(--body);font-size:16px;line-height:1.55;padding-block:0 48px;padding-inline:16px}}
a{{color:var(--accent);text-underline-offset:2px}} a:focus-visible,button:focus-visible{{outline:3px solid var(--accent);outline-offset:2px}}
.hero{{max-width:1180px;margin:0 auto;padding:40px 0 24px;border-bottom:1px solid var(--line)}}
.hero h1{{font-family:var(--display);font-weight:600;font-size:clamp(30px,4.2vw,46px);line-height:1.08;margin:0 0 10px;letter-spacing:-.01em;text-wrap:balance}}
.hero p{{max-width:66ch;margin:0 0 14px;color:var(--muted);font-size:17px}}
.chips{{display:flex;flex-wrap:wrap;gap:8px}} .chip{{display:inline-flex;align-items:center;gap:6px;border:1px solid var(--line);background:var(--surface);border-radius:999px;padding:4px 11px;font-size:13px;color:var(--muted)}} .chip--warn{{background:var(--warn-soft);color:var(--warn);border-color:transparent}} .chip code{{font-family:var(--mono);font-size:12px;color:var(--ink)}}
.layout{{max-width:1180px;margin:0 auto;display:grid;grid-template-columns:230px minmax(0,1fr);gap:40px;align-items:start}}
.toc{{position:sticky;top:20px;padding-top:28px}} .toc h2{{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin:0 0 10px;font-weight:600}}
.toc ol{{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:2px}} .toc a{{display:flex;gap:10px;align-items:baseline;text-decoration:none;color:var(--ink);padding:6px 8px;border-radius:6px;font-size:14.5px}} .toc a:hover{{background:var(--accent-soft)}} .toc .n{{font-family:var(--mono);font-size:12px;color:var(--muted);min-width:1.4em}}
.content{{min-width:0;max-width:880px}}
.chapter{{padding-top:36px}} .chapter header{{margin-bottom:8px}} .kicker{{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--accent);font-weight:600}}
.chapter h2{{font-family:var(--display);font-weight:600;font-size:30px;line-height:1.15;margin:4px 0 6px;letter-spacing:-.01em;text-wrap:balance}} .lede{{margin:0;color:var(--muted);max-width:66ch}}
.screen{{margin-top:30px;padding-top:22px;border-top:1px solid var(--line)}} .screen h3{{font-family:var(--display);font-weight:500;font-size:22px;margin:0 0 6px;display:flex;gap:12px;align-items:baseline}} .num{{font-family:var(--mono);font-size:13px;color:var(--muted);font-weight:400}}
.who{{margin:0 0 12px;font-size:14px;color:var(--muted)}} .who__label{{display:inline-block;background:var(--accent-soft);color:var(--accent-ink);border-radius:4px;padding:1px 7px;font-size:12px;font-weight:600;margin-right:6px;letter-spacing:.02em}}
figure{{margin:0 0 14px}} figure a{{display:block}} figure img{{display:block;width:100%;max-width:100%;height:auto;border:1px solid var(--frame);border-radius:var(--radius);background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.06)}} figcaption{{font-family:var(--mono);font-size:12px;color:var(--muted);margin-top:6px}}
.screen--phone figure{{max-width:390px}}
.what{{max-width:68ch;margin:0 0 12px}}
.controls{{margin:0;display:grid;grid-template-columns:minmax(150px,220px) minmax(0,1fr);gap:0;border:1px solid var(--line);border-radius:var(--radius);overflow:hidden;background:var(--surface)}} .controls .ctl{{display:contents}} .controls dt{{padding:9px 12px;font-weight:600;font-size:14px;border-top:1px solid var(--line);background:var(--accent-soft);color:var(--accent-ink)}} .controls dd{{margin:0;padding:9px 12px;font-size:14.5px;border-top:1px solid var(--line)}} .controls .ctl:first-child dt,.controls .ctl:first-child dd{{border-top:0}}
@media (max-width:900px){{.layout{{grid-template-columns:1fr;gap:12px}} .toc{{position:static;padding-top:16px}} .toc ol{{flex-direction:row;flex-wrap:wrap}} .controls{{grid-template-columns:1fr}} .controls dd{{border-top:0;padding-top:0}}}}
@media (prefers-reduced-motion:no-preference){{html{{scroll-behavior:smooth}}}}
</style>
<header class="hero">
  <h1>travel-os UI tour</h1>
  <p>Every screen of the web app, as a traveler, a manager, a travel admin and Finance see it — captured from the running sandbox stack, one section per screen: what it shows, who sees it, what each control does.</p>
  <div class="chips"><span class="chip chip--warn">Sandbox — simulated suppliers, simulated enterprise sources</span><span class="chip">captured 2026-09-23</span><span class="chip">commit <code>b4e2fe1</code>+</span><span class="chip">{sum(len(s) for _, _, s in CHAPTERS)} screens · {len(CHAPTERS)} chapters</span></div>
</header>
<div class="layout">
  <nav class="toc" aria-label="Chapters"><h2>Chapters</h2><ol>{toc}</ol></nav>
  <main class="content">{"".join(body)}</main>
</div>
'''
  (HERE / "ui-tour.html").write_text(doc)

md(); page()
print("built ui-tour.md and ui-tour/ui-tour.html")
