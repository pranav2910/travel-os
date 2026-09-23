// Re-takes the cancellation screens after the progress-step label fix (steps of a booked trip stay
// "done" while it is cancelling / cancelled). Same helpers as capture.js, reduced to these screens.
const { chromium } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const BASE = process.env.APP_URL || 'http://localhost:8080';
const PASSWORD = process.env.QA_PASSWORD || 'password';
const RUN = `ui-tour ${new Date().toISOString().slice(11, 16)}`;
const ids = JSON.parse(fs.readFileSync(path.join(__dirname, 'ids.json'), 'utf8'));
function future(days) { const d = new Date(); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10); }
async function signIn(page, user, target = '/') {
  await page.goto(BASE + target); await page.getByRole('button', { name: 'Sign in' }).click(); await page.waitForURL(/realms\/travelos/);
  await page.getByRole('textbox', { name: /username|email/i }).fill(user); await page.getByRole('textbox', { name: /^password$/i }).fill(PASSWORD);
  await page.getByRole('button', { name: /sign in/i }).click(); await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/'));
  await page.getByRole('banner').getByText('Sandbox').waitFor();
}
async function waitHeading(page, re, ms = 240_000) { const until = Date.now() + ms; while (Date.now() < until) { const t = (await page.getByRole('heading', { level: 1 }).textContent().catch(() => '')) || ''; if (re.test(t)) return t; await page.waitForTimeout(1500); } return ''; }
async function shot(page, name, fullPage = true) { await page.waitForTimeout(1200); await page.screenshot({ path: path.join(__dirname, name), fullPage }); console.log('shot', name); }
async function fillRoundTrip(page, purpose, out, ret) {
  await page.getByRole('radio', { name: 'Round trip' }).check(); await page.getByLabel('Purpose of travel').fill(purpose);
  await page.getByLabel('From (airport code)').fill('BOS'); await page.getByLabel('To (airport code)').fill('SEA');
  await page.getByRole('group', { name: 'Outbound' }).getByLabel('Date').fill(out); await page.getByRole('group', { name: 'Outbound' }).getByLabel('Earliest departure (UTC)').fill('05:00'); await page.getByRole('group', { name: 'Outbound' }).getByLabel('Arrive by (UTC)').fill('23:59');
  await page.getByRole('group', { name: 'Return' }).getByLabel('Date').fill(ret); await page.getByRole('group', { name: 'Return' }).getByLabel('Earliest return (UTC)').fill('10:00'); await page.getByRole('group', { name: 'Return' }).getByLabel('Latest return (UTC)').fill('23:00');
}
async function submit(page) { await page.getByRole('button', { name: 'Review and submit' }).click(); await page.getByRole('button', { name: 'Confirm and submit' }).click(); await page.waitForURL(/\/trips\/trip_/); return page.url().split('/trips/')[1]; }
(async () => {
  const b = await chromium.launch(); const page = await (await b.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
  await signIn(page, 'alice', '/trips/new');
  // a fresh booked round trip, cancelled: the Cancelling moment, then Cancelled
  await fillRoundTrip(page, `${RUN} customer visit in Seattle`, future(33), future(35));
  const round = await submit(page); await waitHeading(page, /Booked/); await page.waitForTimeout(1500);
  await page.getByRole('button', { name: 'Cancel trip' }).click(); await page.getByRole('dialog').waitFor(); await page.getByLabel('Reason').fill('meeting moved to video');
  await page.getByRole('button', { name: 'Cancel the trip' }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' }).catch(() => {});
  await page.waitForTimeout(200); await shot(page, '19-trip-cancelling.png');
  await waitHeading(page, /Cancelled/); await shot(page, '20-trip-cancelled.png');
  ids.round2 = round;
  // the refused release: a fresh LAX itinerary
  await page.goto(BASE + '/trips/new'); await page.getByRole('radio', { name: /Multi-city itinerary/ }).check();
  await page.getByLabel('Purpose of travel').fill(`${RUN} conference in LA`);
  const d0 = future(52), d1 = future(53);
  const leg1 = page.getByRole('group', { name: 'Leg 1' }); await leg1.getByLabel('From').fill('BOS'); await leg1.getByLabel('To').fill('LAX'); await leg1.getByLabel('Date').fill(d0); await leg1.getByLabel('Earliest departure (UTC)').fill('05:00'); await leg1.getByLabel('Arrive by (UTC)').fill('23:59');
  const leg2 = page.getByRole('group', { name: 'Leg 2' }); await leg2.getByLabel('From').fill('LAX'); await leg2.getByLabel('To').fill('BOS'); await leg2.getByLabel('Date').fill(d1); await leg2.getByLabel('Earliest departure (UTC)').fill('05:00'); await leg2.getByLabel('Arrive by (UTC)').fill('23:59');
  await page.getByRole('button', { name: 'Add stay' }).click(); const stay = page.getByRole('group', { name: 'Stay 1' }); await stay.getByLabel('City (airport code)').fill('LAX'); await stay.getByLabel('Check-in (local date)').fill(d0); await stay.getByLabel('Check-out (local date)').fill(d1);
  const lax = await submit(page); await waitHeading(page, /Booked|Failed/);
  await page.getByRole('button', { name: 'Cancel trip' }).click(); await page.getByLabel('Reason').fill('conference cancelled'); await page.getByRole('button', { name: 'Cancel the trip' }).click();
  await page.getByText('Cancellation incomplete.').waitFor({ timeout: 120000 }); await shot(page, '21-trip-cancelling-needs-a-person.png');
  ids.lax2 = lax;
  // the earlier resolved LAX trip, and the phone view of the cancelled round trip
  await page.goto(BASE + `/trips/${ids.lax}`); await waitHeading(page, /Cancelled/, 30000); await shot(page, '23-trip-cancelled-after-resolution.png');
  await page.setViewportSize({ width: 390, height: 844 }); await page.goto(BASE + `/trips/${round}`); await page.waitForTimeout(1500);
  await page.screenshot({ path: path.join(__dirname, '41-phone-trip-detail.png'), fullPage: true, clip: { x: 0, y: 0, width: 390, height: 1700 } }); console.log('shot 41-phone-trip-detail.png');
  fs.writeFileSync(path.join(__dirname, 'ids.json'), JSON.stringify(ids, null, 1));
  await b.close();
})().catch((e) => { console.error('recapture failed:', e.message); process.exit(1); });
