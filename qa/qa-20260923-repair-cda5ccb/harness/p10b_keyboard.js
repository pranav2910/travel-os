// Prompt 10 (part 2): keyboard-only journey, stored-HTML rendering as its owner, the trip page's
// contrast violation detail, WebKit smoke, engine versions.
const { chromium, webkit, devices } = require('@playwright/test');
const AxeBuilder = require('@axe-core/playwright').default;
const fs = require('node:fs');
const path = require('node:path');
const EV = path.resolve(__dirname, '..', 'evidence');
const BASE = process.env.APP_URL || 'http://localhost:8080';
const PASSWORD = process.env.QA_PASSWORD || 'password';
const RUN = path.basename(path.resolve(__dirname, '..'));
const out = { keyboard: {}, xss: {}, contrast: {}, engines: {} };
function future(n) { const x = new Date(); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10); }
(async () => {
  const b = await chromium.launch();
  out.engines.chromium = b.version();
  // ---------------- keyboard-only journey
  {
    const page = await (await b.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
    await page.goto(BASE + '/');
    await page.getByRole('button', { name: 'Sign in' }).waitFor();
    const stops = [];
    for (let i = 0; i < 6; i++) { await page.keyboard.press('Tab'); stops.push(await page.evaluate(() => (document.activeElement && (document.activeElement.textContent || document.activeElement.tagName) || '').trim().slice(0, 30))); if (/Sign in/.test(stops[stops.length - 1])) break; }
    out.keyboard.tabStopsToSignIn = stops;
    out.keyboard.signInReachable = /Sign in/.test(stops[stops.length - 1] || '');
    await page.keyboard.press('Enter'); await page.waitForURL(/realms\/travelos/, { timeout: 15000 });
    await page.getByRole('textbox', { name: /username|email/i }).focus(); await page.keyboard.type('alice'); await page.keyboard.press('Tab'); await page.keyboard.type(PASSWORD); await page.keyboard.press('Enter');
    await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/'));
    await page.getByRole('banner').getByText('Sandbox').waitFor();
    await page.keyboard.press('Tab'); out.keyboard.firstTabStopAfterLogin = await page.evaluate(() => (document.activeElement.textContent || '').trim().slice(0, 30));
    // navigate to New trip by keyboard: tab to the link and Enter
    let focusedText = ''; for (let i = 0; i < 12 && !/^New trip$/.test(focusedText); i++) { await page.keyboard.press('Tab'); focusedText = await page.evaluate(() => (document.activeElement.textContent || '').trim()); }
    out.keyboard.newTripLinkReachable = /^New trip$/.test(focusedText);
    await page.keyboard.press('Enter'); await page.waitForURL(/\/trips\/new/, { timeout: 10000 }).catch(() => {});
    out.keyboard.onNewTrip = /\/trips\/new/.test(page.url());
    await page.getByLabel('Purpose of travel').focus(); await page.keyboard.type(`${RUN} keyboard only`);
    await page.getByLabel('From (airport code)').focus(); await page.keyboard.type('BOS'); await page.keyboard.press('Tab'); await page.keyboard.type('SEA');
    const od = page.getByRole('group', { name: 'Outbound' }).getByLabel('Date'); await od.focus(); await od.fill(future(100));
    const rd = page.getByRole('group', { name: 'Return' }).getByLabel('Date'); await rd.focus(); await rd.fill(future(102));
    await page.getByRole('button', { name: 'Review and submit' }).focus();
    out.keyboard.focusRing = await page.evaluate(() => { const cs = getComputedStyle(document.activeElement); return { outlineStyle: cs.outlineStyle, outlineWidth: cs.outlineWidth, boxShadow: cs.boxShadow.slice(0, 40) }; });
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: 'Confirm and submit' }).focus(); await page.keyboard.press('Enter');
    await page.waitForURL(/\/trips\/trip_/, { timeout: 20000 }).catch(() => {});
    out.keyboard.submitted = /\/trips\/trip_/.test(page.url());
    await page.waitForTimeout(3000);
    const cancelBtn = page.getByRole('button', { name: 'Cancel trip' });
    if (await cancelBtn.count()) {
      await cancelBtn.focus(); await page.keyboard.press('Enter'); await page.waitForTimeout(400);
      const opened = await page.getByRole('dialog').isVisible().catch(() => false);
      const inDialog = await page.evaluate(() => !!(document.activeElement && document.activeElement.closest('dialog')));
      await page.keyboard.press('Escape'); await page.waitForTimeout(300);
      out.keyboard.dialog = { opened, focusInsideOnOpen: inDialog, closedByEscape: !(await page.getByRole('dialog').isVisible().catch(() => false)), focusRestored: await page.evaluate(() => (document.activeElement.textContent || '').trim() === 'Cancel trip') };
    }
    // field errors: association and announcement
    await page.goto(BASE + '/trips/new'); await page.getByRole('button', { name: 'Review and submit' }).click(); await page.waitForTimeout(300);
    out.keyboard.errors = await page.evaluate(() => { const invalid = [...document.querySelectorAll('[aria-invalid="true"]')]; return { invalidFields: invalid.length, withDescribedBy: invalid.filter((e) => e.getAttribute('aria-describedby')).length, liveRegions: document.querySelectorAll('[role="alert"], [aria-live]').length, firstErrorText: (document.querySelector('[role="alert"], [aria-live]') || {}).textContent }; });
    await page.screenshot({ path: path.join(EV, 'p10-form-errors.png'), fullPage: true });
    // ---------------- stored HTML purpose renders as text. The audit read P3-VAL-06's trip off the first
    // list page; this run has hundreds of newer trips, so a fresh HTML-named trip is submitted first.
    let dialogs = 0; page.on('dialog', (d) => { dialogs += 1; d.dismiss().catch(() => {}); });
    await page.goto(BASE + '/trips/new');
    await page.getByLabel('Purpose of travel').fill(`${RUN} <img src=x onerror=alert(1)><script>alert('qa')</script>`);
    await page.getByLabel('From (airport code)').fill('BOS'); await page.getByLabel('To (airport code)').fill('SEA');
    await page.getByRole('group', { name: 'Outbound' }).getByLabel('Date').fill(future(110));
    await page.getByRole('group', { name: 'Return' }).getByLabel('Date').fill(future(112));
    await page.getByRole('button', { name: 'Review and submit' }).click();
    await page.getByRole('button', { name: 'Confirm and submit' }).click();
    await page.waitForURL(/\/trips\/trip_/, { timeout: 20000 }).catch(() => {});
    await page.goto(BASE + '/trips'); await page.waitForTimeout(1500);
    const htmlRow = page.getByRole('row').filter({ hasText: 'onerror' }).first();
    out.xss = { rowVisible: await htmlRow.isVisible().catch(() => false), injectedImg: await page.locator('main img[src="x"]').count(), scripts: await page.locator('main script').count(), dialogs, rowText: (await htmlRow.innerText().catch(() => '')).slice(0, 120) };
    if (out.xss.rowVisible) { await htmlRow.getByRole('link').first().click(); await page.waitForTimeout(1500); out.xss.detail = { injectedImg: await page.locator('main img[src="x"]').count(), dialogs, h1: (await page.getByRole('heading', { level: 1 }).innerText()).slice(0, 80) }; await page.screenshot({ path: path.join(EV, 'p10-xss-detail.png') }); }
    // ---------------- contrast violation detail on a trip page
    const res = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    out.contrast = res.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.map((n) => ({ target: n.target, html: n.html.slice(0, 160), summary: n.failureSummary.slice(0, 200) })) }));
    await page.context().close();
  }
  try { const wb = await webkit.launch(); out.engines.webkit = wb.version(); const page = await (await wb.newContext({ ...devices['Desktop Safari'] })).newPage(); await page.goto(BASE + '/'); await page.getByRole('button', { name: 'Sign in' }).click(); await page.waitForURL(/realms\/travelos/); await page.getByRole('textbox', { name: /username|email/i }).fill('alice'); await page.getByRole('textbox', { name: /^password$/i }).fill(PASSWORD); await page.getByRole('button', { name: /sign in/i }).click(); await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/')); await page.goto(BASE + '/trips'); await page.getByRole('heading', { name: 'Trips' }).waitFor({ timeout: 20000 }).catch(() => {}); await page.getByRole('row').first().waitFor({ timeout: 20000 }).catch(() => {}); out.engines.webkitSmoke = { tripsHeading: await page.getByRole('heading', { name: 'Trips' }).isVisible(), rows: await page.getByRole('row').count() }; await page.screenshot({ path: path.join(EV, 'p10-webkit-trips.png') }); await wb.close(); } catch (e) { out.engines.webkitSmoke = { error: e.message.slice(0, 120) }; }
  out.engines.firefox = 'not installed (needs a browser download): BLOCKED';
  await b.close();
  fs.writeFileSync(path.join(EV, 'p10b-keyboard.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, null, 1).slice(0, 4000));
})().catch((e) => { console.error('p10b failed:', e.message); fs.writeFileSync(path.join(EV, 'p10b-keyboard.json'), JSON.stringify({ ...out, error: e.message }, null, 1)); process.exit(1); });
