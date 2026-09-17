import { expect, request, test } from '@playwright/test';
import { createHmac } from 'node:crypto';
import {
  SUPPLIER,
  api,
  fillRoundTrip,
  seedPolicy,
  signIn,
  signOut,
  submitTrip,
  token,
  tripDate,
  waitForStatus,
} from './support';

const WEBHOOK_SECRET = process.env['E2E_WEBHOOK_SECRET'] ?? 'sandbox-air-dev-webhook-secret';

/** The sandbox airline cancels a booked flight (a signed supplier notice, exactly as the gateway expects). */
async function cancelFlight(
  externalOrderId: string,
  flightNumber: string,
  date: string,
  deltaMinor: number,
): Promise<string> {
  const body = JSON.stringify({
    eventId: `web-e2e-${Date.now()}`,
    type: 'FLIGHT_CANCELLED',
    externalOrderId,
    flightNumber,
    date,
    reason: 'crew availability',
    reaccommodation: { fareDeltaMinor: deltaMinor },
  });
  const sig = createHmac('sha256', WEBHOOK_SECRET).update(body).digest('hex');
  const ctx = await request.newContext({
    baseURL: SUPPLIER,
    extraHTTPHeaders: { Authorization: `Bearer ${await token('carol')}` },
  });
  const res = await ctx.post('/api/v1/suppliers/sandbox-air/events', {
    data: body,
    headers: { 'Content-Type': 'application/json', 'X-Supplier-Signature': `sha256=${sig}` },
  });
  expect(res.status(), await res.text()).toBe(202);
  const view = (await res.json()) as { disruptionId: string };
  await ctx.dispose();
  return view.disruptionId;
}

test.describe('disruptions and exposures', () => {
  test.beforeAll(async () => {
    await seedPolicy();
  });

  test('a cancelled flight needing a person is decided by a manager, not the traveler; states stay honest', async ({
    page,
  }) => {
    await signIn(page, 'alice', '/trips/new');
    const d0 = tripDate(20);
    await fillRoundTrip(page, 'web e2e disruption', d0, tripDate(21));
    const tripId = await submitTrip(page);
    await waitForStatus(page, ['Booked']);
    const ctx = await api('alice');
    const orders = (await (await ctx.get(`/api/v1/orders?tripId=${tripId}`)).json()) as {
      externalOrderId: string;
      items: { status: string; flights: { flightNumber: string }[] }[];
    }[];
    await ctx.dispose();
    const order = orders[0]!;
    const flight = order.items.find((i) => i.status === 'CONFIRMED')!.flights[0]!.flightNumber;
    const disruptionId = await cancelFlight(order.externalOrderId, flight, d0, 18000);
    await page.goto(`/disruptions/${disruptionId}`);
    await expect
      .poll(async () => (await page.getByRole('heading', { level: 1 }).textContent()) ?? '', {
        timeout: 150_000,
        intervals: [2000],
      })
      .toMatch(/Needs a person|Resolved|Manual intervention|No alternative/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Needs a person');
    await expect(
      page.getByText('This is your own trip: you cannot approve its recovery.'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve the replacement' })).toHaveCount(0);
    await expect(page.getByText(/Incremental cost/)).toBeVisible();
    await expect(page.getByText('USD 180.00', { exact: true })).toBeVisible();
    await signOut(page);
    await signIn(page, 'bob', '/approvals');
    await expect(
      page.getByRole('heading', { name: 'Recoveries needing a decision' }),
    ).toBeVisible();
    await page.goto(`/disruptions/${disruptionId}`);
    await page.getByRole('button', { name: 'Approve the replacement' }).click();
    await expect
      .poll(async () => (await page.getByRole('heading', { level: 1 }).textContent()) ?? '', {
        timeout: 150_000,
        intervals: [2000],
      })
      .toMatch(/Resolved|Changing|Manual intervention|Failed/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Resolved');
    await expect(page.getByText(/Approved/).first()).toBeVisible();
    // the trip shows the change with its incremental cost, distinct from the original booking
    await page.goto(`/trips/${tripId}`);
    await expect(page.getByText(/change\(s\) from disruption recovery/)).toBeVisible({
      timeout: 30_000,
    });
  });

  test('Finance sees open exposures; a traveler is refused the exposure list and refund recording', async ({
    page,
  }) => {
    // an exposure needs a supplier that refuses a cancellation: the LAX fixture (Slice 3) does that
    await signIn(page, 'alice', '/trips/new');
    await page.getByRole('radio', { name: /Multi-city itinerary/ }).check();
    await page.getByLabel('Purpose of travel').fill('web e2e exposure');
    const d0 = tripDate(24),
      d1 = tripDate(25);
    const leg1 = page.getByRole('group', { name: 'Leg 1' });
    await leg1.getByLabel('From').fill('BOS');
    await leg1.getByLabel('To').fill('LAX');
    await leg1.getByLabel('Date').fill(d0);
    await leg1.getByLabel('Earliest departure (UTC)').fill('05:00');
    await leg1.getByLabel('Arrive by (UTC)').fill('23:59');
    const leg2 = page.getByRole('group', { name: 'Leg 2' });
    await leg2.getByLabel('From').fill('LAX');
    await leg2.getByLabel('To').fill('BOS');
    await leg2.getByLabel('Date').fill(d1);
    await leg2.getByLabel('Earliest departure (UTC)').fill('05:00');
    await leg2.getByLabel('Arrive by (UTC)').fill('23:59');
    await page.getByRole('button', { name: 'Add stay' }).click();
    const stay = page.getByRole('group', { name: 'Stay 1' });
    await stay.getByLabel('City (airport code)').fill('LAX');
    await stay.getByLabel('Check-in (local date)').fill(d0);
    await stay.getByLabel('Check-out (local date)').fill(d1);
    await page.getByRole('button', { name: 'Add transfer' }).click();
    await page
      .getByRole('group', { name: 'Transfer 1' })
      .getByLabel('City (airport code)')
      .fill('LAX');
    const tripId = await submitTrip(page);
    await waitForStatus(page, ['Failed', 'Booked']);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Failed');
    await expect(page.getByText('Financial exposure.')).toBeVisible({ timeout: 30_000 });
    await expect(
      page.getByText(/An open exposure is money the company may still owe/),
    ).toBeVisible();
    const alice = await api('alice');
    expect((await alice.get('/api/v1/orders/exposures')).status()).toBe(403);
    expect(
      (
        await alice.post('/api/v1/learning/outcomes/refunds', {
          data: { tripId, orderId: 'ord_x', amountMinor: 1, currency: 'USD', reference: 'x' },
          headers: { 'Idempotency-Key': 'e2e-refund-alice' },
        })
      ).status(),
    ).toBe(403);
    await alice.dispose();
    await signOut(page);
    await signIn(page, 'carol', '/finance');
    const row = page.getByRole('row').filter({ hasText: 'sandbox-hotel' }).first();
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: 'Resolve' }).click();
    await page.getByLabel('Resolution').fill('cancelled by phone with the property (web e2e)');
    await page.getByRole('button', { name: 'Record resolution' }).click();
    await expect(page.getByRole('dialog')).toBeHidden();
    await page.getByLabel('Show').selectOption('RESOLVED');
    await expect(page.getByRole('row').filter({ hasText: 'web e2e' }).first()).toBeVisible();
  });
});
