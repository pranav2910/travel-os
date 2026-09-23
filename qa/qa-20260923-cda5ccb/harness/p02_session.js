// Prompt 2 (browser side): login variants, protected deep links, refresh, logout, Back/Forward after
// logout, two tabs, and a session revoked by the identity provider while a form is being filled.
const { chromium } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const EV = path.resolve(__dirname, '..', 'evidence');
const BASE = process.env.APP_URL || 'http://localhost:8080';
const KC = process.env.KC_URL || 'http://localhost:8180';
const PASSWORD = process.env.QA_PASSWORD || 'password';
const out = {};
async function login(page, user, pw, target = '/') {
  await page.goto(BASE + target);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/realms\/travelos/);
  await page.getByRole('textbox', { name: /username|email/i }).fill(user);
  await page.getByRole('textbox', { name: /^password$/i }).fill(pw);
  await page.getByRole('button', { name: /sign in/i }).click();
}
async function kcAdminLogoutUser(username) {
  const t = await (await fetch(`${KC}/realms/master/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ client_id: 'admin-cli', grant_type: 'password', username: 'admin', password: 'admin' }) })).json();
  if (!t.access_token) return 'admin token unavailable';
  const users = await (await fetch(`${KC}/admin/realms/travelos/users?username=${username}&exact=true`, { headers: { Authorization: `Bearer ${t.access_token}` } })).json();
  const id = users[0]?.id; if (!id) return 'user not found';
  const r = await fetch(`${KC}/admin/realms/travelos/users/${id}/logout`, { method: 'POST', headers: { Authorization: `Bearer ${t.access_token}` } });
  return `logout-all -> ${r.status}`;
}
(async () => {
  const b = await chromium.launch();
  // 1. invalid password, empty password, whitespace-padded username
  {
    const page = await (await b.newContext()).newPage();
    await login(page, 'alice', 'wrong-password');
    out.invalidPassword = { stillOnKeycloak: /realms\/travelos/.test(page.url()), message: (await page.locator('#input-error, .kc-feedback-text, [role=alert]').first().textContent().catch(() => '')).trim() };
    await page.getByRole('textbox', { name: /^password$/i }).fill('');
    await page.getByRole('button', { name: /sign in/i }).click();
    out.emptyPassword = { stillOnKeycloak: /realms\/travelos/.test(page.url()) };
    await page.getByRole('textbox', { name: /username|email/i }).fill('  alice  ');
    await page.getByRole('textbox', { name: /^password$/i }).fill(PASSWORD);
    await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForTimeout(2500);
    out.whitespaceUsername = { signedIn: page.url().startsWith(BASE) && !(await page.getByRole('button', { name: 'Sign in' }).isVisible().catch(() => false)), url: page.url().split('?')[0] };
    await page.screenshot({ path: path.join(EV, 'p02-whitespace-username.png') });
    await page.context().close();
  }
  // 2. protected deep link when signed out -> sign in -> returns to the link; refresh keeps the session
  {
    const ctx = await b.newContext(); const page = await ctx.newPage();
    await page.goto(BASE + '/approvals');
    out.deepLinkSignedOut = { askedToSignIn: await page.getByRole('button', { name: 'Sign in' }).isVisible(), noProtectedContent: (await page.getByRole('heading', { name: 'Approvals' }).count()) === 0 };
    await page.getByRole('button', { name: 'Sign in' }).click(); await page.waitForURL(/realms\/travelos/);
    await page.getByRole('textbox', { name: /username|email/i }).fill('bob'); await page.getByRole('textbox', { name: /^password$/i }).fill(PASSWORD); await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/'));
    out.deepLinkReturn = { landedOn: new URL(page.url()).pathname, approvalsHeading: await page.getByRole('heading', { name: 'Approvals' }).isVisible().catch(() => false) };
    await page.reload(); await page.waitForTimeout(1500);
    out.refreshKeepsSession = { stillSignedIn: await page.getByRole('button', { name: 'Sign out' }).isVisible().catch(() => false), pathname: new URL(page.url()).pathname };
    // storage: no tokens persisted
    const storage = await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage).map((k) => k.slice(0, 40)), cookies: document.cookie }));
    out.storage = storage;
    out.tokenInStorage = await page.evaluate(() => [...Object.values(localStorage), ...Object.values(sessionStorage)].some((v) => /access_token|eyJ[A-Za-z0-9_-]{30,}/.test(String(v))));
    // two tabs share the session
    const tab2 = await ctx.newPage(); await tab2.goto(BASE + '/trips'); await tab2.waitForTimeout(1500);
    out.secondTab = { signedIn: await tab2.getByRole('button', { name: 'Sign out' }).isVisible().catch(() => false) };
    // logout from tab 1, then Back/Forward and the second tab
    await page.getByRole('button', { name: 'Sign out' }).click(); await page.waitForURL((u) => u.origin === BASE && u.pathname === '/', { timeout: 30000 }); await page.getByRole('button', { name: 'Sign in' }).waitFor({ timeout: 20000 });
    await page.goBack().catch(() => {}); await page.waitForTimeout(1500);
    out.backAfterLogout = { url: new URL(page.url()).pathname, protectedVisible: (await page.getByRole('heading', { name: 'Approvals' }).count()) > 0, signInVisible: await page.getByRole('button', { name: 'Sign in' }).isVisible().catch(() => false) };
    await page.goForward().catch(() => {}); await page.waitForTimeout(1000);
    out.forwardAfterLogout = { protectedVisible: (await page.getByRole('heading', { name: 'Approvals' }).count()) > 0 };
    await tab2.reload().catch(() => {}); await tab2.waitForTimeout(2500);
    out.secondTabAfterLogout = { signedInStill: await tab2.getByRole('button', { name: 'Sign out' }).isVisible().catch(() => false), askedToSignIn: await tab2.getByRole('button', { name: 'Sign in' }).isVisible().catch(() => false) };
    await page.screenshot({ path: path.join(EV, 'p02-back-after-logout.png') });
    await ctx.close();
  }
  // 3. session revoked by the IdP while a form is being filled, then submit
  {
    const ctx = await b.newContext(); const page = await ctx.newPage();
    await login(page, 'alice', PASSWORD, '/trips/new'); await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/'));
    await page.getByLabel('Purpose of travel').fill('qa session revoked mid-form');
    out.revocation = { admin: await kcAdminLogoutUser('alice') };
    await page.getByLabel('From (airport code)').fill('BOS'); await page.getByLabel('To (airport code)').fill('SEA');
    const o = page.getByRole('group', { name: 'Outbound' }), r = page.getByRole('group', { name: 'Return' });
    const d = (n) => { const x = new Date(); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10); };
    await o.getByLabel('Date').fill(d(80)); await r.getByLabel('Date').fill(d(82));
    await page.getByRole('button', { name: 'Review and submit' }).click();
    await page.getByRole('button', { name: 'Confirm and submit' }).click();
    await page.waitForTimeout(6000);
    out.revocation.afterSubmit = { url: new URL(page.url()).pathname, signInVisible: await page.getByRole('button', { name: 'Sign in' }).isVisible().catch(() => false), tripCreated: /\/trips\/trip_/.test(page.url()), bodyText: (await page.locator('main').innerText().catch(() => '')).slice(0, 300) };
    await page.screenshot({ path: path.join(EV, 'p02-revoked-mid-form.png'), fullPage: true });
    await ctx.close();
  }
  fs.writeFileSync(path.join(EV, 'p02-session.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, null, 1));
  await b.close();
})().catch((e) => { console.error('p02 session failed:', e.message); fs.writeFileSync(path.join(EV, 'p02-session.json'), JSON.stringify({ ...out, error: e.message }, null, 1)); process.exit(1); });
