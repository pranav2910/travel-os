import { expect, test } from '@playwright/test';
import { api, seedPolicy, signIn, tripDate } from './support';

/** The sandbox connectors are seeded through the same API the Slice 4 script uses. */
async function connector(kind: string, provider: string): Promise<string> {
  const ctx = await api('carol');
  const list = (await (await ctx.get('/api/v1/connectors')).json()) as {
    connectorId: string;
    kind: string;
    provider: string;
  }[];
  const existing = list.find((c) => c.kind === kind && c.provider === provider);
  if (existing) {
    await ctx.dispose();
    return existing.connectorId;
  }
  const res = await ctx.post('/api/v1/connectors', {
    data: { kind, provider, config: { scheduled: false } },
    headers: { 'Idempotency-Key': `e2e-conn-${kind}` },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
  const view = (await res.json()) as { connectorId: string };
  await ctx.dispose();
  return view.connectorId;
}

async function seedAndSync(connectorId: string, items: unknown[]) {
  const ctx = await api('carol');
  expect(
    (
      await ctx.post(`/api/v1/connectors/${connectorId}/sandbox/items`, {
        data: { items },
        headers: { 'Idempotency-Key': `e2e-seed-${Date.now()}` },
      })
    ).ok(),
  ).toBeTruthy();
  const run = (await (
    await ctx.post(`/api/v1/connectors/${connectorId}/sync`, {
      headers: { 'Idempotency-Key': `e2e-sync-${Date.now()}` },
    })
  ).json()) as { runId: string };
  await expect
    .poll(
      async () => {
        const runs = (await (await ctx.get(`/api/v1/connectors/${connectorId}/runs`)).json()) as {
          runId: string;
          status: string;
        }[];
        return runs.find((r) => r.runId === run.runId)?.status ?? '';
      },
      { timeout: 120_000, intervals: [1000] },
    )
    .toMatch(/COMPLETED|FAILED/);
  await ctx.dispose();
}

test.describe('demand and connectors', () => {
  test('detected demand converts into exactly one trip, however many times it is asked', async ({
    page,
  }) => {
    await seedPolicy();
    const nonce = Date.now();
    const hris = await connector('HRIS', 'sandbox-hris');
    const cal = await connector('CALENDAR', 'sandbox-calendar');
    await seedAndSync(hris, [
      {
        sourceId: 'emp_1001',
        revision: nonce,
        payload: {
          employeeId: 'emp_1001',
          email: 'alice@acme.example',
          displayName: 'Alice Nguyen',
          workLocation: 'BOS',
          timeZone: 'America/New_York',
          managerEmployeeId: 'emp_1002',
          active: true,
        },
      },
    ]);
    const d = tripDate(30);
    const title = `web e2e customer visit ${nonce}`;
    await seedAndSync(cal, [
      {
        sourceId: `web-e2e-${nonce}`,
        revision: 1,
        payload: {
          sourceId: `web-e2e-${nonce}`,
          title,
          organizerEmail: 'organizer@customer.example',
          attendees: [{ email: 'alice@acme.example', status: 'ACCEPTED' }],
          start: `${d}T17:00:00Z`,
          end: `${d}T19:00:00Z`,
          timeZone: 'America/Los_Angeles',
          location: { text: 'Seattle office', city: 'SEA', kind: 'PHYSICAL' },
          conferencingUrl: null,
          status: 'CONFIRMED',
          attendanceMode: 'IN_PERSON',
        },
      },
    ]);
    await signIn(page, 'alice', '/demand');
    const row = page.getByRole('row').filter({ hasText: title });
    await expect(row).toBeVisible({ timeout: 30_000 });
    await row.getByRole('link').click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(title);
    await expect(page.getByText('Traveler (verified from HRIS)')).toBeVisible();
    await expect(page.getByText(/simulated source/).first()).toBeVisible();
    const convert = page.getByRole('button', { name: 'Convert into a trip' });
    await expect(convert).toBeEnabled();
    // two clicks in a row: one trip
    await convert.click();
    // the page may already have moved on: never wait for the button to come back
    await convert.click({ force: true, timeout: 1_000 }).catch(() => {});
    await page.waitForURL(/\/trips\/trip_/);
    const tripId = page.url().split('/trips/')[1]!;
    // the candidate now links to that trip and offers no second conversion
    await page.goto('/demand');
    await page.getByLabel('Show').selectOption('CONVERTED');
    await page.getByRole('row').filter({ hasText: title }).getByRole('link').click();
    await expect(page.getByText('Converted.')).toBeVisible();
    await expect(page.getByRole('link', { name: `trip ${tripId}` })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Convert into a trip' })).toHaveCount(0);
    // and the API agrees: converting again returns the same trip
    const ctx = await api('alice');
    const candidateId = page.url().split('/demand/')[1]!;
    const again = await ctx.post(`/api/v1/demand/${candidateId}/conversion`, {
      headers: { 'Idempotency-Key': `e2e-reconvert-${nonce}` },
    });
    expect([200, 409]).toContain(again.status());
    if (again.status() === 200)
      expect(((await again.json()) as { tripId: string }).tripId).toBe(tripId);
    await ctx.dispose();
  });

  test('connectors are labelled simulated and a travel admin can sync them', async ({ page }) => {
    await signIn(page, 'carol', '/connectors');
    await expect(page.getByText('Simulated providers only.')).toBeVisible();
    await expect(page.getByText('simulated').first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sync now' }).first()).toBeVisible();
  });
});
