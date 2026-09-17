import { expect, type APIRequestContext, type Page, request } from '@playwright/test';

export const KC = process.env['E2E_KEYCLOAK_URL'] ?? 'http://localhost:8180';
export const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:8080';
/** Not a browser route (the edge refuses it): supplier notices are posted to the gateway itself. */
export const SUPPLIER = process.env['E2E_SUPPLIER_URL'] ?? 'http://localhost:8084';
export const USERS = {
  alice: 'alice',
  bob: 'bob',
  carol: 'carol',
  dan: 'dan',
  zoe: 'zoe',
} as const;
export type User = keyof typeof USERS;

/** Sign in through the real Keycloak login page (Authorization Code + PKCE). */
export async function signIn(page: Page, user: User, path = '/') {
  await page.goto(path);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/realms\/travelos/);
  await page.getByRole('textbox', { name: /username|email/i }).fill(USERS[user]);
  await page.getByRole('textbox', { name: /^password$/i }).fill('password');
  await page.getByRole('button', { name: /sign in/i }).click();
  await page.waitForURL(
    (u) => u.origin === new URL(BASE).origin && !u.pathname.startsWith('/auth/'),
  );
  await expect(page.getByRole('banner')).toContainText('Sandbox');
}

export async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  // Keycloak ends the session and sends the browser back to the app's origin
  await page.waitForURL((u) => u.origin === new URL(BASE).origin && u.pathname === '/', {
    timeout: 30_000,
  });
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible({ timeout: 20_000 });
}

/** A machine token for fixtures the UI does not create (supplier notices, connector seeds). */
export async function token(user: User): Promise<string> {
  const ctx = await request.newContext();
  const res = await ctx.post(`${KC}/realms/travelos/protocol/openid-connect/token`, {
    form: {
      client_id: 'travelos-dev-cli',
      grant_type: 'password',
      username: USERS[user],
      password: 'password',
    },
  });
  expect(res.ok()).toBeTruthy();
  const body = (await res.json()) as { access_token: string };
  await ctx.dispose();
  return body.access_token;
}

export async function api(user: User): Promise<APIRequestContext> {
  const t = await token(user);
  return request.newContext({ baseURL: BASE, extraHTTPHeaders: { Authorization: `Bearer ${t}` } });
}

/** Per-run dates: the sandbox airline keeps a cancelled flight cancelled, so reruns must not collide. */
export function tripDate(offsetDays: number): string {
  const base = new Date(Date.UTC(2026, 9, 6));
  const nonce = Math.floor(Date.now() / 1000) % 300;
  base.setUTCDate(base.getUTCDate() + 7 + nonce + offsetDays);
  return base.toISOString().slice(0, 10);
}

export async function seedPolicy(mutate?: (doc: Record<string, unknown>) => void): Promise<void> {
  const fs = await import('node:fs/promises');
  const path = await import('node:path');
  const seedPath = path.resolve(
    process.cwd(),
    '..',
    'platform',
    'local',
    'seed',
    'policies',
    'acme-us-standard.json',
  );
  const doc = JSON.parse(await fs.readFile(seedPath, 'utf8')) as Record<string, unknown>;
  mutate?.(doc);
  const ctx = await api('carol');
  const res = await ctx.post('/api/v1/policies', { data: { document: doc, note: 'web e2e' } });
  expect(res.ok()).toBeTruthy();
  await ctx.dispose();
}

export async function fillRoundTrip(page: Page, purpose: string, out: string, ret: string) {
  await page.getByRole('radio', { name: 'Round trip' }).check();
  await page.getByLabel('Purpose of travel').fill(purpose);
  await page.getByLabel('From (airport code)').fill('BOS');
  await page.getByLabel('To (airport code)').fill('SEA');
  await page.getByRole('group', { name: 'Outbound' }).getByLabel('Date').fill(out);
  await page
    .getByRole('group', { name: 'Outbound' })
    .getByLabel('Earliest departure (UTC)')
    .fill('05:00');
  await page.getByRole('group', { name: 'Outbound' }).getByLabel('Arrive by (UTC)').fill('23:59');
  await page.getByRole('group', { name: 'Return' }).getByLabel('Date').fill(ret);
  await page
    .getByRole('group', { name: 'Return' })
    .getByLabel('Earliest return (UTC)')
    .fill('10:00');
  await page.getByRole('group', { name: 'Return' }).getByLabel('Latest return (UTC)').fill('23:00');
}

export async function submitTrip(page: Page): Promise<string> {
  await page.getByRole('button', { name: 'Review and submit' }).click();
  await page.getByRole('button', { name: 'Confirm and submit' }).click();
  await page.waitForURL(/\/trips\/trip_/);
  return page.url().split('/trips/')[1]!;
}

export async function waitForStatus(page: Page, statuses: string[], timeoutMs = 200_000) {
  const heading = page.getByRole('heading', { level: 1 });
  await expect
    .poll(async () => (await heading.textContent()) ?? '', {
      timeout: timeoutMs,
      intervals: [1000, 2000, 3000],
    })
    .toMatch(new RegExp(statuses.join('|')));
}
