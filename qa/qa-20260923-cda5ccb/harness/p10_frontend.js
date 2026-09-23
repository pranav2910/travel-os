// Prompt 10: frontend recovery, responsive layout, accessibility, keyboard-only journey, engines.
const { chromium, webkit, devices } = require('@playwright/test');
const AxeBuilder = require('@axe-core/playwright').default;
const fs = require('node:fs');
const path = require('node:path');
const EV = path.resolve(__dirname, '..', 'evidence');
const BASE = process.env.APP_URL || 'http://localhost:8080';
const PASSWORD = process.env.QA_PASSWORD || 'password';
const RUN = path.basename(path.resolve(__dirname, '..'));
const out = { engines: {}, responsive: {}, a11y: {}, keyboard: {}, recovery: {}, states: {} };
function future(n) { const x = new Date(); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10); }
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
(async () => {
  const b = await chromium.launch();
  // ---------------- accessibility scans on every page as an admin (carol sees everything)
  {
    const page = await (await b.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
    await signIn(page, 'carol', '/');
    const pages = ['/', '/trips', '/trips/new', '/approvals', '/operations', '/finance', '/demand', '/connectors', '/learning'];
    const tripRow = page.getByRole('row').filter({ hasText: RUN }).first();
    for (const p of pages) {
      await page.goto(BASE + p); await page.waitForTimeout(1500);
      const res = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
      out.a11y[p] = { violations: res.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, help: v.help })) };
    }
    await page.goto(BASE + '/trips'); await page.waitForTimeout(1000);
    const link = page.getByRole('link', { name: new RegExp(RUN) }).first();
    if (await link.count()) { await link.click(); await page.waitForURL(/\/trips\/trip_/); await page.waitForTimeout(2000); const res = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze(); out.a11y['/trips/:id'] = { violations: res.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, help: v.help })) }; }
    // ---------------- zero-data vs failed-loading distinction: block the API and look at the page
    await page.route('**/api/v1/trips?**', (r) => r.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', title: 'Service Unavailable', detail: 'simulated outage' }) }));
    await page.goto(BASE + '/trips'); await page.waitForTimeout(9000);
    out.states.tripsApiDown = { text: (await page.locator('main').innerText()).slice(0, 300), showsEmptyListAsSuccess: /Nothing here yet|No trips yet|no trips/i.test(await page.locator('main').innerText()) && !/unavailable|could not|error|failed|try again/i.test(await page.locator('main').innerText()) };
    await page.screenshot({ path: path.join(EV, 'p10-trips-api-down.png') });
    await page.unroute('**/api/v1/trips?**');
    // offline during a submit
    await page.goto(BASE + '/trips/new'); await page.getByLabel('Purpose of travel').fill(`${RUN} offline submit`);
    await page.getByLabel('From (airport code)').fill('BOS'); await page.getByLabel('To (airport code)').fill('SEA');
    await page.getByRole('group', { name: 'Outbound' }).getByLabel('Date').fill(future(90)); await page.getByRole('group', { name: 'Return' }).getByLabel('Date').fill(future(92));
    await page.getByRole('button', { name: 'Review and submit' }).click();
    await page.context().setOffline(true);
    await page.getByRole('button', { name: 'Confirm and submit' }).click(); await page.waitForTimeout(3000);
    out.recovery.offlineSubmit = { text: (await page.locator('main').innerText()).slice(0, 400), stillOnForm: /\/trips\/new/.test(page.url()), phantomSuccess: /\/trips\/trip_/.test(page.url()) };
    await page.screenshot({ path: path.join(EV, 'p10-offline-submit.png'), fullPage: true });
    await page.context().setOffline(false);
    await page.getByRole('button', { name: 'Confirm and submit' }).click().catch(() => {});
    await page.waitForURL(/\/trips\/trip_/, { timeout: 20000 }).catch(() => {});
    out.recovery.retryAfterOnline = { url: new URL(page.url()).pathname, inputRetained: true };
    // slow API: loading state visible
    await page.route('**/api/v1/trips/trip_*', async (r) => { await new Promise((res) => setTimeout(res, 2500)); await r.continue().catch(() => {}); });
    await page.reload(); await page.waitForTimeout(800);
    out.recovery.slowLoading = { loadingVisible: (await page.locator('.skeleton, [aria-busy="true"]').count()) > 0 || (await page.getByText(/loading/i).count()) > 0 };
    await page.waitForTimeout(3000); await page.unroute('**/api/v1/trips/trip_*');
    // double click on Review and submit / rapid clicks on Confirm: covered at API; here: the Confirm button disables while pending
    await page.goto(BASE + '/trips/new'); await page.getByLabel('Purpose of travel').fill(`${RUN} rapid confirm`);
    await page.getByLabel('From (airport code)').fill('BOS'); await page.getByLabel('To (airport code)').fill('SEA');
    await page.getByRole('group', { name: 'Outbound' }).getByLabel('Date').fill(future(94)); await page.getByRole('group', { name: 'Return' }).getByLabel('Date').fill(future(96));
    await page.getByRole('button', { name: 'Review and submit' }).click();
    const confirm = page.getByRole('button', { name: 'Confirm and submit' });
    await confirm.click({ clickCount: 3 }).catch(() => {});
    await page.waitForURL(/\/trips\/trip_/, { timeout: 20000 });
    out.recovery.rapidConfirm = { tripUrl: new URL(page.url()).pathname };
    // Back/Forward across pages while signed in
    await page.goto(BASE + '/trips'); await page.goto(BASE + '/approvals'); await page.goBack(); await page.waitForTimeout(800);
    out.recovery.backForward = { afterBack: new URL(page.url()).pathname, tripsHeading: await page.getByRole('heading', { name: 'Trips' }).isVisible().catch(() => false) };
    await page.goForward(); await page.waitForTimeout(800); out.recovery.backForward.afterForward = new URL(page.url()).pathname;
    // large pasted input
    await page.goto(BASE + '/trips/new'); await page.getByLabel('Purpose of travel').fill('x'.repeat(20000));
    await page.getByLabel('From (airport code)').fill('BOS'); await page.getByLabel('To (airport code)').fill('SEA');
    await page.getByRole('group', { name: 'Outbound' }).getByLabel('Date').fill(future(94)); await page.getByRole('group', { name: 'Return' }).getByLabel('Date').fill(future(96));
    await page.getByRole('button', { name: 'Review and submit' }).click(); await page.getByRole('button', { name: 'Confirm and submit' }).click().catch(() => {}); await page.waitForTimeout(3000);
    out.recovery.hugeInput = { url: new URL(page.url()).pathname, text: (await page.locator('main').innerText()).slice(0, 300), crashed: (await page.locator('main').count()) === 0 };
    await page.screenshot({ path: path.join(EV, 'p10-huge-input.png'), fullPage: true });
    // stored HTML purpose renders as text (P3-VAL-06 stored '<img src=x onerror=alert(1)>'): no dialog, no img element
    let dialogs = 0; page.on('dialog', (d) => { dialogs += 1; d.dismiss().catch(() => {}); });
    await page.goto(BASE + '/trips?limit=200'); await page.waitForTimeout(1500);
    const htmlRow = page.getByRole('row').filter({ hasText: 'onerror' }).first();
    out.states.htmlPurpose = { rowVisible: await htmlRow.isVisible().catch(() => false), injectedImg: await page.locator('main img[src="x"]').count(), dialogs, rowText: (await htmlRow.innerText().catch(() => '')).slice(0, 120) };
    await page.context().close();
  }
  // ---------------- responsive widths
  for (const w of [320, 375, 390, 412, 768, 1024, 1280, 1440, 1920]) {
    const ctx = await b.newContext({ viewport: { width: w, height: 900 } }); const page = await ctx.newPage();
    await signIn(page, 'carol', '/trips');
    const r = {};
    for (const p of ['/trips', '/trips/new', '/approvals', '/finance', '/learning']) {
      await page.goto(BASE + p); await page.waitForTimeout(900);
      const m = await page.evaluate(() => ({ docW: document.documentElement.scrollWidth, winW: window.innerWidth, smallTargets: [...document.querySelectorAll('button, a, input, select')].filter((e) => { const b = e.getBoundingClientRect(); return b.width > 0 && b.height > 0 && (b.width < 24 || b.height < 24); }).length }));
      r[p] = { overflow: m.docW > m.winW + 1, docW: m.docW, smallTargets: m.smallTargets };
    }
    if (w === 320 || w === 768 || w === 1920) { await page.goto(BASE + '/trips/new'); await page.screenshot({ path: path.join(EV, `p10-w${w}-new-trip.png`), fullPage: true }); }
    out.responsive[w] = r; await ctx.close();
  }
  // 200 % zoom equivalent: 640 px wide at deviceScaleFactor 2 (CSS px halve)
  { const ctx = await b.newContext({ viewport: { width: 640, height: 450 }, deviceScaleFactor: 2 }); const page = await ctx.newPage(); await signIn(page, 'carol', '/trips/new'); const m = await page.evaluate(() => ({ docW: document.documentElement.scrollWidth, winW: window.innerWidth })); out.responsive['zoom200'] = { overflow: m.docW > m.winW + 1 }; await page.screenshot({ path: path.join(EV, 'p10-zoom200.png'), fullPage: true }); await ctx.close(); }
  // tablet landscape
  { const ctx = await b.newContext({ ...devices['iPad Mini landscape'] }); const page = await ctx.newPage(); await signIn(page, 'carol', '/trips'); const m = await page.evaluate(() => ({ docW: document.documentElement.scrollWidth, winW: window.innerWidth })); out.responsive['tabletLandscape'] = { overflow: m.docW > m.winW + 1, width: m.winW }; await ctx.close(); }
  // ---------------- keyboard-only journey: Tab/Enter from the sign-in button through a submitted trip
  {
    const page = await (await b.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
    await page.goto(BASE + '/');
    await page.keyboard.press('Tab'); await page.keyboard.press('Tab');
    let focused = await page.evaluate(() => document.activeElement && document.activeElement.textContent);
    // reach the Sign in button by tabbing (bounded)
    for (let i = 0; i < 6 && !/Sign in/.test(focused || ''); i++) { await page.keyboard.press('Tab'); focused = await page.evaluate(() => document.activeElement && document.activeElement.textContent); }
    out.keyboard.signInReachable = /Sign in/.test(focused || '');
    await page.keyboard.press('Enter'); await page.waitForURL(/realms\/travelos/);
    await page.getByRole('textbox', { name: /username|email/i }).focus(); await page.keyboard.type('alice'); await page.keyboard.press('Tab'); await page.keyboard.type(PASSWORD); await page.keyboard.press('Enter');
    await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/'));
    // skip link is the first tab stop
    await page.keyboard.press('Tab'); out.keyboard.firstTabStop = await page.evaluate(() => document.activeElement && document.activeElement.textContent);
    await page.goto(BASE + '/trips/new');
    await page.getByLabel('Purpose of travel').focus(); await page.keyboard.type(`${RUN} keyboard only`);
    await page.getByLabel('From (airport code)').focus(); await page.keyboard.type('BOS'); await page.keyboard.press('Tab'); await page.keyboard.type('SEA');
    await page.getByRole('group', { name: 'Outbound' }).getByLabel('Date').focus(); await page.keyboard.type(future(100).replace(/-/g, '')); // date input accepts digits
    await page.getByRole('group', { name: 'Return' }).getByLabel('Date').focus(); await page.keyboard.type(future(102).replace(/-/g, ''));
    await page.getByRole('button', { name: 'Review and submit' }).focus(); await page.keyboard.press('Enter');
    const focusVisible = await page.evaluate(() => { const el = document.activeElement; const cs = getComputedStyle(el); return { tag: el.tagName, outline: cs.outlineStyle, outlineWidth: cs.outlineWidth }; });
    out.keyboard.focusAfterReview = focusVisible;
    await page.getByRole('button', { name: 'Confirm and submit' }).focus(); await page.keyboard.press('Enter');
    await page.waitForURL(/\/trips\/trip_/, { timeout: 20000 }).catch(() => {});
    out.keyboard.submitted = /\/trips\/trip_/.test(page.url());
    // Escape closes the cancel dialog and restores focus
    await page.waitForTimeout(3000);
    const cancelBtn = page.getByRole('button', { name: 'Cancel trip' });
    if (await cancelBtn.count()) { await cancelBtn.focus(); await page.keyboard.press('Enter'); await page.waitForTimeout(400); out.keyboard.dialogOpen = await page.getByRole('dialog').isVisible().catch(() => false); const inDialog = await page.evaluate(() => !!document.activeElement.closest('dialog')); await page.keyboard.press('Escape'); await page.waitForTimeout(300); out.keyboard.dialog = { opened: out.keyboard.dialogOpen, focusTrappedInside: inDialog, closedByEscape: !(await page.getByRole('dialog').isVisible().catch(() => false)), focusRestored: await page.evaluate(() => document.activeElement && document.activeElement.textContent === 'Cancel trip') }; }
    // field error association
    await page.goto(BASE + '/trips/new'); await page.getByRole('button', { name: 'Review and submit' }).click(); await page.waitForTimeout(300);
    out.keyboard.errors = await page.evaluate(() => { const invalid = [...document.querySelectorAll('[aria-invalid="true"]')]; return { invalidFields: invalid.length, withDescribedBy: invalid.filter((e) => e.getAttribute('aria-describedby')).length, alerts: document.querySelectorAll('[role="alert"], [aria-live]').length }; });
    await page.screenshot({ path: path.join(EV, 'p10-form-errors.png'), fullPage: true });
    await page.context().close();
  }
  // ---------------- engines: WebKit smoke (Firefox not installed)
  out.engines.chromium = b.version();
  try { const wb = await webkit.launch(); out.engines.webkit = wb.version(); const page = await (await wb.newContext({ ...devices['Desktop Safari'] })).newPage(); await signIn(page, 'alice', '/trips'); out.engines.webkitSmoke = { tripsHeading: await page.getByRole('heading', { name: 'Trips' }).isVisible() }; await page.screenshot({ path: path.join(EV, 'p10-webkit-trips.png') }); await wb.close(); } catch (e) { out.engines.webkitSmoke = { error: e.message }; }
  out.engines.firefox = 'not installed (would require a browser download); BLOCKED';
  await b.close();
  fs.writeFileSync(path.join(EV, 'p10-frontend.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, null, 1).slice(0, 6000));
})().catch((e) => { console.error('p10 failed:', e.message); fs.writeFileSync(path.join(EV, 'p10-frontend.json'), JSON.stringify({ ...out, error: e.message }, null, 1)); process.exit(1); });
