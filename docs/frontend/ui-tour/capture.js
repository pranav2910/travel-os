// Captures the UI tour screenshots (docs/frontend/ui-tour.md) from a running local stack.
//   cd web && NODE_PATH=$PWD/node_modules node ../docs/frontend/ui-tour/capture.js
// Every journey is driven for real (sandbox suppliers); policy changes are restored; the trips it
// creates carry the purpose prefix "ui-tour" and stay as ordinary sandbox data.
const { chromium } = require('@playwright/test');
const { createHmac } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const OUT = __dirname;
const BASE = process.env.APP_URL || 'http://localhost:8080';
const KC = process.env.KC_URL || 'http://localhost:8180';
const SUPPLIER = process.env.SUPPLIER_URL || 'http://localhost:8084';
const PASSWORD = process.env.QA_PASSWORD || 'password';
const WEBHOOK_SECRET = process.env.E2E_WEBHOOK_SECRET || 'sandbox-air-dev-webhook-secret';
const RUN = `ui-tour ${new Date().toISOString().slice(11, 16)}`;
const SEED = path.resolve(__dirname, '..', '..', '..', 'platform', 'local', 'seed', 'policies', 'acme-us-standard.json');
const log = (m) => console.log(`${new Date().toISOString().slice(11, 19)} ${m}`);

function future(days) { const d = new Date(); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10); }
const tokens = {};
async function token(user) {
  if (tokens[user] && tokens[user].until > Date.now()) return tokens[user].t;
  const r = await fetch(`${KC}/realms/travelos/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ client_id: 'travelos-dev-cli', grant_type: 'password', username: user, password: PASSWORD }) });
  const j = await r.json(); tokens[user] = { t: j.access_token, until: Date.now() + 600_000 }; return j.access_token;
}
async function api(user, method, p, body, base = BASE, extra = {}) {
  const headers = { Authorization: `Bearer ${await token(user)}`, Accept: 'application/json', ...extra };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') headers['Idempotency-Key'] = headers['Idempotency-Key'] || `ui-tour-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const r = await fetch(base + p, { method, headers, body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch {}
  return { status: r.status, json, text };
}
async function seedPolicy(mutate) {
  const doc = JSON.parse(fs.readFileSync(SEED, 'utf8')); if (mutate) mutate(doc);
  const r = await api('carol', 'POST', '/api/v1/policies', { document: doc, note: `${RUN}` });
  if (r.status >= 300) throw new Error(`seed policy ${r.status} ${r.text.slice(0, 200)}`);
}
async function waitTrip(user, id, statuses, ms = 240_000) {
  const until = Date.now() + ms;
  while (Date.now() < until) { const t = (await api(user, 'GET', `/api/v1/trips/${id}`)).json; if (t && statuses.includes(t.status)) return t; await new Promise((r) => setTimeout(r, 1500)); }
  return (await api(user, 'GET', `/api/v1/trips/${id}`)).json;
}
async function signIn(page, user, target = '/') {
  await page.goto(BASE + target);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/realms\/travelos/);
  await page.getByRole('textbox', { name: /username|email/i }).fill(user);
  await page.getByRole('textbox', { name: /^password$/i }).fill(PASSWORD);
  await page.getByRole('button', { name: /sign in/i }).click();
  await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/'));
  await page.getByRole('banner').getByText('Sandbox').waitFor();
}
async function signOut(page) { await page.getByRole('button', { name: 'Sign out' }).click().catch(() => {}); await page.waitForTimeout(1500); }
let n = 0;
async function shot(page, slug, opts = {}) {
  n += 1; const name = `${String(n).padStart(2, '0')}-${slug}.png`;
  await page.waitForTimeout(opts.settle ?? 700);
  await page.screenshot({ path: path.join(OUT, name), fullPage: opts.fullPage ?? false });
  log(`shot ${name}`); return name;
}
async function fillRoundTrip(page, purpose, out, ret, o = 'BOS', d = 'SEA') {
  await page.getByRole('radio', { name: 'Round trip' }).check();
  await page.getByLabel('Purpose of travel').fill(purpose);
  await page.getByLabel('From (airport code)').fill(o); await page.getByLabel('To (airport code)').fill(d);
  await page.getByRole('group', { name: 'Outbound' }).getByLabel('Date').fill(out);
  await page.getByRole('group', { name: 'Outbound' }).getByLabel('Earliest departure (UTC)').fill('05:00');
  await page.getByRole('group', { name: 'Outbound' }).getByLabel('Arrive by (UTC)').fill('23:59');
  await page.getByRole('group', { name: 'Return' }).getByLabel('Date').fill(ret);
  await page.getByRole('group', { name: 'Return' }).getByLabel('Earliest return (UTC)').fill('10:00');
  await page.getByRole('group', { name: 'Return' }).getByLabel('Latest return (UTC)').fill('23:00');
}
async function submit(page) {
  await page.getByRole('button', { name: 'Review and submit' }).click();
  await page.getByRole('button', { name: 'Confirm and submit' }).click();
  await page.waitForURL(/\/trips\/trip_/); return page.url().split('/trips/')[1];
}
async function waitHeading(page, re, ms = 240_000) {
  const until = Date.now() + ms;
  while (Date.now() < until) { const t = (await page.getByRole('heading', { level: 1 }).textContent().catch(() => '')) || ''; if (re.test(t)) return t; await page.waitForTimeout(1500); }
  return '';
}
async function step(name, fn) { try { await fn(); } catch (e) { log(`FAIL ${name}: ${String(e.message || e).split('\n')[0].slice(0, 200)}`); } }

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const ids = {};
  await seedPolicy();

  // ---------------------------------------------------------------- sign-in and the traveler's home
  await step('sign-in', async () => {
    await page.goto(BASE + '/'); await page.getByRole('button', { name: 'Sign in' }).waitFor();
    await shot(page, 'sign-in-page');
    await page.getByRole('button', { name: 'Sign in' }).click(); await page.waitForURL(/realms\/travelos/);
    await shot(page, 'keycloak-login', { settle: 1200 });
    await page.getByRole('textbox', { name: /username|email/i }).fill('alice');
    await page.getByRole('textbox', { name: /^password$/i }).fill(PASSWORD);
    await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/'));
    await page.getByRole('banner').getByText('Sandbox').waitFor();
    await shot(page, 'overview-alice', { settle: 1500, fullPage: true });
    await page.goto(BASE + '/trips'); await page.getByRole('row').first().waitFor({ timeout: 20000 }).catch(() => {});
    await shot(page, 'trips-list', { settle: 1200 });
  });

  // ---------------------------------------------------------------- a round trip, start to finish
  await step('round-trip', async () => {
    await page.goto(BASE + '/trips/new'); await page.getByLabel('Purpose of travel').waitFor();
    await shot(page, 'new-trip-empty', { fullPage: true });
    await fillRoundTrip(page, `${RUN} customer visit in Seattle`, future(30), future(32));
    await shot(page, 'new-trip-round-trip-filled', { fullPage: true });
    await page.getByRole('button', { name: 'Review and submit' }).click();
    await page.getByRole('button', { name: 'Confirm and submit' }).waitFor();
    await shot(page, 'new-trip-review', { fullPage: true });
    await page.getByRole('button', { name: 'Confirm and submit' }).click();
    await page.waitForURL(/\/trips\/trip_/); ids.round = page.url().split('/trips/')[1];
    await shot(page, 'trip-planning', { settle: 400 });
    await waitHeading(page, /Booked/); await page.waitForTimeout(2500);
    await shot(page, 'trip-booked', { fullPage: true, settle: 1500 });
    await page.getByText('What the model concluded').click().catch(() => {});
    await page.waitForTimeout(500);
  });

  // ---------------------------------------------------------------- other ways to ask
  await step('multi-city', async () => {
    await page.goto(BASE + '/trips/new');
    await page.getByRole('radio', { name: /Multi-city itinerary/ }).check();
    await page.getByLabel('Purpose of travel').fill(`${RUN} roadshow`);
    const d0 = future(40), d1 = future(42), d2 = future(43);
    const leg1 = page.getByRole('group', { name: 'Leg 1' });
    await leg1.getByLabel('From').fill('BOS'); await leg1.getByLabel('To').fill('SEA'); await leg1.getByLabel('Date').fill(d0);
    await leg1.getByLabel('Earliest departure (UTC)').fill('05:00'); await leg1.getByLabel('Arrive by (UTC)').fill('23:59');
    const leg2 = page.getByRole('group', { name: 'Leg 2' });
    await leg2.getByLabel('From').fill('SEA'); await leg2.getByLabel('To').fill('SFO'); await leg2.getByLabel('Date').fill(d1);
    await leg2.getByLabel('Earliest departure (UTC)').fill('05:00'); await leg2.getByLabel('Arrive by (UTC)').fill('23:59');
    await page.getByRole('button', { name: 'Add leg' }).click().catch(() => {});
    const leg3 = page.getByRole('group', { name: 'Leg 3' });
    if (await leg3.count()) { await leg3.getByLabel('From').fill('SFO'); await leg3.getByLabel('To').fill('BOS'); await leg3.getByLabel('Date').fill(d2); await leg3.getByLabel('Earliest departure (UTC)').fill('05:00'); await leg3.getByLabel('Arrive by (UTC)').fill('23:59'); }
    await page.getByRole('button', { name: 'Add stay' }).click();
    const stay = page.getByRole('group', { name: 'Stay 1' });
    await stay.getByLabel('City (airport code)').fill('SEA'); await stay.getByLabel('Check-in (local date)').fill(d0); await stay.getByLabel('Check-out (local date)').fill(d1);
    await page.getByRole('button', { name: 'Add transfer' }).click();
    await page.getByRole('group', { name: 'Transfer 1' }).getByLabel('City (airport code)').fill('SEA');
    await shot(page, 'new-trip-multi-city', { fullPage: true });
    ids.multi = await submit(page);
    await waitHeading(page, /Booked|Failed|Awaiting/); await page.waitForTimeout(2500);
    await shot(page, 'trip-multi-city-booked', { fullPage: true, settle: 1500 });
  });
  await step('free-text', async () => {
    await page.goto(BASE + '/trips/new');
    await page.getByRole('radio', { name: /Free text|Describe/i }).check();
    await page.getByLabel(/Describe|What do you need/i).fill(`Fly BOS to SEA on ${future(35)}, back ${future(37)}, hotel`);
    await shot(page, 'new-trip-free-text', { fullPage: true });
  });
  await step('validation', async () => {
    await page.goto(BASE + '/trips/new'); await page.getByRole('button', { name: 'Review and submit' }).click(); await page.waitForTimeout(500);
    await shot(page, 'new-trip-validation-errors', { fullPage: true });
    await fillRoundTrip(page, `${RUN} unknown airport`, future(30), future(32), 'BOS', 'QQQ');
    await page.getByRole('button', { name: 'Review and submit' }).click();
    await page.getByRole('button', { name: 'Confirm and submit' }).click();
    await page.getByText(/refused|UNKNOWN_LOCATION|unknown location/i).first().waitFor({ timeout: 15000 }).catch(() => {});
    await shot(page, 'new-trip-unknown-airport-refused', { fullPage: true });
  });

  // ---------------------------------------------------------------- approval by a manager
  await step('approval', async () => {
    await seedPolicy((d) => { d.approval.managerRequiredAbove = 1; });
    await page.goto(BASE + '/trips/new');
    const purpose = `${RUN} needs approval`;
    await fillRoundTrip(page, purpose, future(45), future(47));
    ids.approval = await submit(page);
    await waitHeading(page, /Awaiting approval/); await page.waitForTimeout(1500);
    await shot(page, 'trip-awaiting-approval-traveler', { fullPage: true });
    await signOut(page); await signIn(page, 'bob', '/approvals');
    await page.getByRole('row').filter({ hasText: purpose }).waitFor({ timeout: 30000 });
    await shot(page, 'approvals-inbox-bob', { fullPage: true });
    await page.getByRole('row').filter({ hasText: purpose }).getByRole('link').first().click();
    await page.getByRole('button', { name: 'Approve' }).waitFor();
    await page.getByLabel('Comment for the traveler (optional)').fill('fine, go');
    await shot(page, 'trip-awaiting-approval-manager', { fullPage: true });
    await page.getByRole('button', { name: 'Approve' }).click();
    await waitHeading(page, /Booked/); await page.waitForTimeout(2000);
    await shot(page, 'trip-approved-then-booked', { fullPage: true });
    await seedPolicy();
    await signOut(page); await signIn(page, 'alice', '/trips');
  });

  // ---------------------------------------------------------------- cancellation: released, then refused
  await step('cancel-booked', async () => {
    await page.goto(BASE + `/trips/${ids.round}`); await page.getByRole('button', { name: 'Cancel trip' }).waitFor();
    await page.getByRole('button', { name: 'Cancel trip' }).click(); await page.getByRole('dialog').waitFor();
    await page.getByLabel('Reason').fill('meeting moved to video');
    await shot(page, 'cancel-dialog-booked');
    await page.getByRole('button', { name: 'Cancel the trip' }).click();
    await page.getByRole('dialog').waitFor({ state: 'hidden' }).catch(() => {});
    await shot(page, 'trip-cancelling', { settle: 300, fullPage: true });
    await waitHeading(page, /Cancelled/); await page.waitForTimeout(1500);
    await shot(page, 'trip-cancelled', { fullPage: true });
  });
  await step('cancel-refused', async () => {
    await page.goto(BASE + '/trips/new');
    await page.getByRole('radio', { name: /Multi-city itinerary/ }).check();
    await page.getByLabel('Purpose of travel').fill(`${RUN} conference in LA`);
    const d0 = future(50), d1 = future(51);
    const leg1 = page.getByRole('group', { name: 'Leg 1' });
    await leg1.getByLabel('From').fill('BOS'); await leg1.getByLabel('To').fill('LAX'); await leg1.getByLabel('Date').fill(d0);
    await leg1.getByLabel('Earliest departure (UTC)').fill('05:00'); await leg1.getByLabel('Arrive by (UTC)').fill('23:59');
    const leg2 = page.getByRole('group', { name: 'Leg 2' });
    await leg2.getByLabel('From').fill('LAX'); await leg2.getByLabel('To').fill('BOS'); await leg2.getByLabel('Date').fill(d1);
    await leg2.getByLabel('Earliest departure (UTC)').fill('05:00'); await leg2.getByLabel('Arrive by (UTC)').fill('23:59');
    await page.getByRole('button', { name: 'Add stay' }).click();
    const stay = page.getByRole('group', { name: 'Stay 1' });
    await stay.getByLabel('City (airport code)').fill('LAX'); await stay.getByLabel('Check-in (local date)').fill(d0); await stay.getByLabel('Check-out (local date)').fill(d1);
    ids.lax = await submit(page);
    await waitHeading(page, /Booked|Failed/);
    await page.getByRole('button', { name: 'Cancel trip' }).click(); await page.getByLabel('Reason').fill('conference cancelled');
    await page.getByRole('button', { name: 'Cancel the trip' }).click();
    await page.getByText('Cancellation incomplete.').waitFor({ timeout: 120000 });
    await page.waitForTimeout(1500);
    await shot(page, 'trip-cancelling-needs-a-person', { fullPage: true });
    await signOut(page); await signIn(page, 'carol', '/finance');
    await page.getByText(/exposure/i).first().waitFor({ timeout: 20000 }).catch(() => {});
    await shot(page, 'finance-open-exposure', { fullPage: true });
    const orders = (await api('alice', 'GET', `/api/v1/orders?tripId=${ids.lax}`)).json || [];
    const exp = ((orders[0] || {}).exposures || []).find((e) => e.status === 'OPEN');
    if (exp) await api('carol', 'POST', `/api/v1/orders/${orders[0].orderId}/exposures/${exp.exposureId}/resolution`, { resolution: 'released by phone with the property; no refund' });
    await waitTrip('alice', ids.lax, ['CANCELLED'], 200000);
    await page.goto(BASE + `/trips/${ids.lax}`); await waitHeading(page, /Cancelled/, 30000); await page.waitForTimeout(1500);
    await shot(page, 'trip-cancelled-after-resolution', { fullPage: true });
    await signOut(page); await signIn(page, 'alice', '/trips');
  });

  // ---------------------------------------------------------------- policy says no
  await step('policy-denial', async () => {
    await seedPolicy((d) => { d.trip = { maxTotal: 100, onViolation: 'DENY' }; });
    try {
      await page.goto(BASE + '/trips/new');
      await fillRoundTrip(page, `${RUN} over budget`, future(55), future(57));
      ids.denied = await submit(page);
      await waitHeading(page, /Failed|Booked/); await page.waitForTimeout(2500);
      await shot(page, 'trip-denied-by-policy', { fullPage: true });
    } finally { await seedPolicy(); }
  });

  // ---------------------------------------------------------------- a disruption after booking
  await step('disruption', async () => {
    await page.goto(BASE + '/trips/new');
    const d0 = future(60);
    await fillRoundTrip(page, `${RUN} board meeting`, d0, future(61));
    ids.disrupted = await submit(page);
    await waitTrip('alice', ids.disrupted, ['BOOKED', 'FAILED']);
    const orders = (await api('alice', 'GET', `/api/v1/orders?tripId=${ids.disrupted}`)).json || [];
    const order = orders[0]; const item = (order.items || []).find((i) => i.status === 'CONFIRMED');
    const flight = item.flights[0].flightNumber;
    const body = JSON.stringify({ eventId: `ui-tour-${Date.now()}`, type: 'FLIGHT_CANCELLED', externalOrderId: order.externalOrderId, flightNumber: flight, date: d0, reason: 'crew availability', reaccommodation: { fareDeltaMinor: 18000 } });
    const sig = createHmac('sha256', WEBHOOK_SECRET).update(body).digest('hex');
    const r = await api('carol', 'POST', '/api/v1/suppliers/sandbox-air/events', body, SUPPLIER, { 'X-Supplier-Signature': `sha256=${sig}` });
    const disruptionId = (r.json || {}).disruptionId; ids.disruption = disruptionId;
    await page.goto(BASE + `/disruptions/${disruptionId}`);
    await waitHeading(page, /Needs a person|Resolved|Manual|No alternative/, 150000); await page.waitForTimeout(1500);
    await shot(page, 'disruption-needs-a-person-traveler', { fullPage: true });
    await signOut(page); await signIn(page, 'bob', '/approvals');
    await page.getByRole('heading', { name: 'Recoveries needing a decision' }).waitFor({ timeout: 30000 }).catch(() => {});
    await shot(page, 'approvals-inbox-recoveries', { fullPage: true });
    await page.goto(BASE + `/disruptions/${disruptionId}`); await page.getByRole('button', { name: 'Approve the replacement' }).waitFor();
    await shot(page, 'disruption-manager-decision', { fullPage: true });
    await page.getByRole('button', { name: 'Approve the replacement' }).click();
    await waitHeading(page, /Resolved|Changing|Manual|Failed/, 150000); await page.waitForTimeout(2000);
    await shot(page, 'disruption-resolved', { fullPage: true });
    await page.goto(BASE + `/trips/${ids.disrupted}`); await page.getByText(/change\(s\) from disruption recovery/).waitFor({ timeout: 30000 }).catch(() => {});
    await shot(page, 'trip-after-recovery', { fullPage: true });
    await page.goto(BASE + '/operations'); await page.waitForTimeout(1500);
    await shot(page, 'operations-disruptions', { fullPage: true });
    await signOut(page);
  });

  // ---------------------------------------------------------------- demand from the calendar
  await step('demand', async () => {
    const list = (await api('carol', 'GET', '/api/v1/connectors')).json || [];
    async function connector(kind, provider) {
      const e = list.find((c) => c.kind === kind && c.provider === provider); if (e) return e.connectorId;
      return ((await api('carol', 'POST', '/api/v1/connectors', { kind, provider, config: { scheduled: false } })).json || {}).connectorId;
    }
    async function seedAndSync(id, items) {
      await api('carol', 'POST', `/api/v1/connectors/${id}/sandbox/items`, { items });
      const run = (await api('carol', 'POST', `/api/v1/connectors/${id}/sync`)).json || {};
      const until = Date.now() + 120000;
      while (Date.now() < until) { const runs = (await api('carol', 'GET', `/api/v1/connectors/${id}/runs`)).json || []; const s = (runs.find((x) => x.runId === run.runId) || {}).status; if (s === 'COMPLETED' || s === 'FAILED') break; await new Promise((r) => setTimeout(r, 1000)); }
    }
    const nonce = Date.now();
    const hris = await connector('HRIS', 'sandbox-hris'); const cal = await connector('CALENDAR', 'sandbox-calendar');
    await seedAndSync(hris, [{ sourceId: 'emp_1001', revision: nonce, payload: { employeeId: 'emp_1001', email: 'alice@acme.example', displayName: 'Alice Nguyen', workLocation: 'BOS', timeZone: 'America/New_York', managerEmployeeId: 'emp_1002', active: true } }]);
    const d = future(70); const title = `Customer QBR in Seattle (${RUN})`;
    await seedAndSync(cal, [{ sourceId: `ui-tour-${nonce}`, revision: 1, payload: { sourceId: `ui-tour-${nonce}`, title, organizerEmail: 'organizer@customer.example', attendees: [{ email: 'alice@acme.example', status: 'ACCEPTED' }], start: `${d}T17:00:00Z`, end: `${d}T19:00:00Z`, timeZone: 'America/Los_Angeles', location: { text: 'Seattle office', city: 'SEA', kind: 'PHYSICAL' }, conferencingUrl: null, status: 'CONFIRMED', attendanceMode: 'IN_PERSON' } }]);
    await signIn(page, 'alice', '/demand');
    await page.getByRole('row').filter({ hasText: title }).waitFor({ timeout: 30000 });
    await shot(page, 'demand-inbox', { fullPage: true });
    await page.getByRole('row').filter({ hasText: title }).getByRole('link').click();
    await page.getByRole('button', { name: 'Convert into a trip' }).waitFor();
    await shot(page, 'demand-candidate', { fullPage: true });
    await page.getByRole('button', { name: 'Convert into a trip' }).click();
    await page.waitForURL(/\/trips\/trip_/); ids.demand = page.url().split('/trips/')[1];
    await waitHeading(page, /Booked|Failed|Awaiting/); await page.waitForTimeout(2500);
    await shot(page, 'trip-from-demand', { fullPage: true });
    await signOut(page);
  });

  // ---------------------------------------------------------------- admin screens and edges
  await step('admin', async () => {
    await signIn(page, 'carol', '/connectors'); await page.waitForTimeout(1500);
    await shot(page, 'connectors-admin', { fullPage: true });
    await page.goto(BASE + '/learning'); await page.waitForTimeout(2000);
    await shot(page, 'learning-admin', { fullPage: true });
    await page.goto(BASE + '/finance'); await page.waitForTimeout(1500);
    await shot(page, 'finance-refunds-and-exposures', { fullPage: true });
    await page.goto(BASE + '/trips/new'); await page.getByLabel('Traveler employee id (optional)').fill('emp_1004'); await page.waitForTimeout(400);
    await page.getByLabel('Traveler first name').fill('Dan'); await page.getByLabel('Traveler last name').fill('Okafor'); await page.getByLabel('Traveler email').fill('dan@acme.example');
    await page.getByLabel('Traveler employee id (optional)').scrollIntoViewIfNeeded();
    await shot(page, 'new-trip-arranging-for-someone', { fullPage: true });
    await signOut(page);
  });
  await step('edges', async () => {
    await signIn(page, 'alice', '/finance'); await page.waitForTimeout(1200);
    await shot(page, 'not-available-for-role');
    await page.goto(BASE + '/trips/trip_00000000000000000000000000'); await page.waitForTimeout(1500);
    await shot(page, 'trip-not-found');
    await page.setViewportSize({ width: 390, height: 844 }); await page.goto(BASE + '/trips'); await page.waitForTimeout(1500);
    await shot(page, 'phone-trips-list', { fullPage: true });
    await page.goto(BASE + `/trips/${ids.round}`); await page.waitForTimeout(1500);
    await shot(page, 'phone-trip-detail', { fullPage: true });
    await page.setViewportSize({ width: 1280, height: 900 });
    await signOut(page); await page.waitForTimeout(800);
    await shot(page, 'signed-out');
  });

  fs.writeFileSync(path.join(OUT, 'ids.json'), JSON.stringify(ids, null, 1));
  await browser.close();
  log(`done: ${n} screenshots; trips ${JSON.stringify(ids)}`);
})().catch((e) => { log(`capture crashed: ${e.message}`); process.exit(1); });
