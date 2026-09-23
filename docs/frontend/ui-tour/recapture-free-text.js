// One screen the main capture could not take: the free-text mode (its label matched two elements).
const { chromium } = require('@playwright/test');
const path = require('node:path');
const BASE = process.env.APP_URL || 'http://localhost:8080';
const PASSWORD = process.env.QA_PASSWORD || 'password';
function future(days) { const d = new Date(); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10); }
(async () => {
  const b = await chromium.launch(); const page = await (await b.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
  await page.goto(BASE + '/'); await page.getByRole('button', { name: 'Sign in' }).click(); await page.waitForURL(/realms\/travelos/);
  await page.getByRole('textbox', { name: /username|email/i }).fill('alice'); await page.getByRole('textbox', { name: /^password$/i }).fill(PASSWORD);
  await page.getByRole('button', { name: /sign in/i }).click(); await page.waitForURL((u) => u.origin === BASE && !u.pathname.startsWith('/auth/'));
  await page.goto(BASE + '/trips/new'); await page.getByRole('radio', { name: 'Describe it in words' }).check();
  await page.getByLabel('Describe the trip', { exact: true }).fill(`Fly BOS to SEA on ${future(35)}, back ${future(37)}, hotel`);
  await page.waitForTimeout(600);
  await page.screenshot({ path: path.join(__dirname, 'new-trip-free-text.png'), fullPage: true });
  console.log('shot new-trip-free-text.png'); await b.close();
})().catch((e) => { console.error(e.message); process.exit(1); });
