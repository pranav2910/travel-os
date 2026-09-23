// Prompt 1: the critical end-to-end journey in a fresh browser, with evidence at every step.
// Login -> Trips -> New trip (Bangor -> Boston, unique name) -> review -> confirm (books, sandbox) ->
// detail -> Booked -> refresh -> logout -> login -> the same persisted trip and reservation.
const { chromium } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const RUN = path.basename(path.resolve(__dirname, '..'));
const EV = path.resolve(__dirname, '..', 'evidence');
const BASE = process.env.APP_URL || 'http://localhost:8080';
const KC = process.env.KC_URL || 'http://localhost:8180';
const PASSWORD = process.env.QA_PASSWORD || 'password';
const out = { run: RUN, steps: [], checks: {} };
const log = (m) => { out.steps.push(`${new Date().toISOString()} ${m}`); console.log(m); };
function future(days) { const d = new Date(); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10); }
async function shot(page, name) { const f = path.join(EV, `p01-${name}.png`); await page.screenshot({ path: f, fullPage: true }); log(`screenshot ${path.basename(f)}`); }
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
async function apiGet(user, p) {
  const t = await (await fetch(`${KC}/realms/travelos/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ client_id: 'travelos-dev-cli', grant_type: 'password', username: user, password: PASSWORD }) })).json();
  const r = await fetch(BASE + p, { headers: { Authorization: `Bearer ${t.access_token}` } });
  return { status: r.status, json: await r.json().catch(() => null) };
}
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  const name = `${RUN} Bangor to Boston critical journey`;
  const d0 = future(21), d1 = future(24);
  try {
    log('1 login as alice (fresh context)'); await signIn(page, 'alice', '/');
    out.checks.identityHeader = await page.getByRole('banner').innerText();
    log('2 trips list'); await page.goto(BASE + '/trips'); await page.getByRole('heading', { name: 'Trips' }).waitFor(); await shot(page, '02-trips');
    log('3 new trip form'); await page.goto(BASE + '/trips/new');
    await page.getByRole('radio', { name: 'Round trip' }).check();
    await page.getByLabel('Purpose of travel').fill(name);
    await page.getByLabel('From (airport code)').fill('BGR'); await page.getByLabel('To (airport code)').fill('BOS');
    const o = page.getByRole('group', { name: 'Outbound' }), r = page.getByRole('group', { name: 'Return' });
    await o.getByLabel('Date').fill(d0); await o.getByLabel('Earliest departure (UTC)').fill('05:00'); await o.getByLabel('Arrive by (UTC)').fill('23:59');
    await r.getByLabel('Date').fill(d1); await r.getByLabel('Earliest return (UTC)').fill('10:00'); await r.getByLabel('Latest return (UTC)').fill('23:00');
    await shot(page, '03-form-filled');
    log('4 review'); await page.getByRole('button', { name: 'Review and submit' }).click();
    out.checks.reviewBannerSaysBooks = await page.getByText('Submitting books the trip.').isVisible();
    out.checks.sandboxChipAtSelection = await page.getByRole('banner').getByText('Sandbox — simulated bookings').isVisible();
    await shot(page, '04-review');
    log('5 confirm'); await page.getByRole('button', { name: 'Confirm and submit' }).click();
    await page.waitForURL(/\/trips\/trip_/); const tripId = page.url().split('/trips/')[1]; out.tripId = tripId; log(`trip ${tripId}`);
    await shot(page, '05-submitted');
    log('6 wait for Booked (bounded 180 s)');
    const h1 = page.getByRole('heading', { level: 1 }); const until = Date.now() + 180000; let heading = '';
    while (Date.now() < until) { heading = (await h1.textContent()) || ''; if (/Booked|Failed|Awaiting/.test(heading)) break; await page.waitForTimeout(1500); }
    out.checks.headingAfterWait = heading;
    await page.waitForTimeout(5000); await shot(page, '06-booked');
    out.checks.bookingHeading = await page.getByText(/^Booking/).first().isVisible().catch(() => false);
    out.checks.supplierRef = await page.getByText(/Supplier reference/).isVisible().catch(() => false);
    out.checks.sandboxSupplierNamed = (await page.getByText('sandbox-air').count()) > 0;
    out.checks.whyThisOption = await page.getByRole('heading', { name: 'Why this option' }).isVisible().catch(() => false);
    out.checks.totalShown = await page.getByText(/USD \d/).first().textContent().catch(() => null);
    log('7 refresh'); await page.reload(); await h1.waitFor(); out.checks.afterReload = (await h1.textContent()) || '';
    await shot(page, '07-after-reload');
    log('8 logout'); await page.getByRole('button', { name: 'Sign out' }).click();
    await page.waitForURL((u) => u.origin === BASE && u.pathname === '/', { timeout: 30000 });
    await page.getByRole('button', { name: 'Sign in' }).waitFor({ timeout: 20000 }); await shot(page, '08-signed-out');
    out.checks.protectedAfterLogout = await page.goto(BASE + `/trips/${tripId}`).then(async () => (await page.getByRole('button', { name: 'Sign in' }).isVisible()));
    log('9 login again and open the same trip'); await signIn(page, 'alice', `/trips/${tripId}`);
    await h1.waitFor(); out.checks.afterRelogin = (await h1.textContent()) || ''; await shot(page, '09-after-relogin');
    log('10 authoritative API verification');
    const t = await apiGet('alice', `/api/v1/trips/${tripId}`); const orders = await apiGet('alice', `/api/v1/orders?tripId=${tripId}`);
    const hist = await apiGet('alice', `/api/v1/trips/${tripId}/history`); const pd = await apiGet('alice', `/api/v1/policy-decisions?tripId=${tripId}`);
    out.api = { trip: t.json, orders: orders.json, history: hist.json, policyDecisions: (pd.json || []).length };
    const trip = t.json || {};
    out.checks.verify = {
      identity: trip.traveler, tenantScoped: t.status === 200, route: `${trip.intent?.origin}->${trip.intent?.destination}`,
      dates: [trip.intent?.earliestDeparture, trip.intent?.latestReturn], currency: trip.total?.currency, total: trip.total?.amountMinor,
      status: trip.status, selectedBundle: trip.evidence?.selectedBundleId, policyDecisionId: trip.evidence?.policyDecisionId,
      orderId: trip.evidence?.orderId, workflowId: tripId, reservationCount: (orders.json || []).length,
      supplierReference: (orders.json || [])[0]?.externalOrderId, orderStatus: (orders.json || [])[0]?.status,
    };
    out.result = /Booked/.test(heading) && out.checks.afterReload === heading && out.checks.afterRelogin === heading && trip.status === 'BOOKED' && (orders.json || []).length === 1 ? 'PASS' : 'FAIL';
  } catch (e) { out.error = e.message; out.result = 'FAIL'; await shot(page, 'error').catch(() => {}); }
  fs.writeFileSync(path.join(EV, 'p01-journey.json'), JSON.stringify(out, null, 1));
  console.log('RESULT', out.result, out.tripId || '');
  await b.close();
})();
