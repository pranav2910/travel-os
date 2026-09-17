import { expect, test } from '@playwright/test';
import {
  api,
  fillRoundTrip,
  seedPolicy,
  signIn,
  submitTrip,
  tripDate,
  waitForStatus,
} from './support';

test.describe('traveler booking', () => {
  test.beforeAll(async () => {
    await seedPolicy();
  });

  test('a round trip is booked with the sandbox airline, survives a reload, and explains itself', async ({
    page,
  }) => {
    await signIn(page, 'alice', '/trips/new');
    await fillRoundTrip(page, 'web e2e round trip', tripDate(0), tripDate(1));
    await expect(page.getByText('Submitting books the trip.')).toBeVisible();
    const tripId = await submitTrip(page);
    await waitForStatus(page, ['Booked']);
    await expect(page.getByRole('heading', { name: /Booking/ })).toBeVisible();
    await expect(page.getByText(/Supplier reference/)).toBeVisible();
    await expect(page.getByText(/UTC/).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Why this option' })).toBeVisible();
    await expect(page.locator('.narrative li').first()).toBeVisible({ timeout: 30_000 });
    // reload recovers the same trip and state
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Booked', {
      timeout: 20_000,
    });
    expect(page.url()).toContain(tripId);
    // it is listed, and the money is exact with an explicit currency
    await page.goto('/trips');
    const row = page.getByRole('row').filter({ hasText: 'web e2e round trip' }).first();
    await expect(row).toContainText(/USD \d{1,3}(,\d{3})*\.\d{2}/);
    // a completed trip is an attestation, not a timer: booked stays booked until someone confirms it
    await page.goto(`/trips/${tripId}`);
    await expect(page.getByRole('button', { name: 'Confirm the trip happened' })).toBeVisible();
    await page.getByRole('button', { name: 'Confirm the trip happened' }).click();
    await expect(page.getByText(/last arrival|TRIP_NOT_OVER/)).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Booked');
  });

  test('a multi-city itinerary with a hotel and a transfer books every component', async ({
    page,
  }) => {
    await signIn(page, 'alice', '/trips/new');
    await page.getByRole('radio', { name: /Multi-city itinerary/ }).check();
    await page.getByLabel('Purpose of travel').fill('web e2e roadshow');
    const d0 = tripDate(3),
      d1 = tripDate(5),
      d2 = tripDate(6);
    const leg1 = page.getByRole('group', { name: 'Leg 1' });
    await leg1.getByLabel('From').fill('BOS');
    await leg1.getByLabel('To').fill('SEA');
    await leg1.getByLabel('Date').fill(d0);
    await leg1.getByLabel('Earliest departure (UTC)').fill('05:00');
    await leg1.getByLabel('Arrive by (UTC)').fill('23:59');
    const leg2 = page.getByRole('group', { name: 'Leg 2' });
    await leg2.getByLabel('From').fill('SEA');
    await leg2.getByLabel('To').fill('BOS');
    await leg2.getByLabel('Date').fill(d2);
    await leg2.getByLabel('Earliest departure (UTC)').fill('05:00');
    await leg2.getByLabel('Arrive by (UTC)').fill('23:59');
    await page.getByRole('button', { name: 'Add stay' }).click();
    const stay = page.getByRole('group', { name: 'Stay 1' });
    await stay.getByLabel('City (airport code)').fill('SEA');
    await stay.getByLabel('Check-in (local date)').fill(d0);
    await stay.getByLabel('Check-out (local date)').fill(d1);
    await page.getByRole('button', { name: 'Add transfer' }).click();
    await page
      .getByRole('group', { name: 'Transfer 1' })
      .getByLabel('City (airport code)')
      .fill('SEA');
    await submitTrip(page);
    await waitForStatus(page, ['Booked', 'Failed']);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Booked');
    const components = page.locator('.component');
    await expect(components).toHaveCount(4);
    await expect(page.getByText(/night\(s\)/)).toBeVisible();
    await expect(page.getByText(/America\/Los_Angeles|PDT|PST/).first()).toBeVisible();
  });

  test('a double submit and a lost answer create one trip', async ({ page }) => {
    await signIn(page, 'alice', '/trips/new');
    // a purpose nobody else used: the DB keeps the trips of earlier runs
    const purpose = `web e2e duplicate guard ${Date.now()}`;
    await fillRoundTrip(page, purpose, tripDate(8), tripDate(9));
    // the first answer is swallowed by the network: the client must not invent a success
    let calls = 0;
    await page.route('**/api/v1/trips', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      calls += 1;
      if (calls === 1) return route.abort('failed');
      return route.continue();
    });
    await page.getByRole('button', { name: 'Review and submit' }).click();
    await page.getByRole('button', { name: 'Confirm and submit' }).click();
    await expect(page.getByText('The answer did not arrive.')).toBeVisible();
    await page.getByRole('button', { name: 'Confirm and submit' }).click();
    await page.waitForURL(/\/trips\/trip_/);
    const tripId = page.url().split('/trips/')[1]!;
    await page.unroute('**/api/v1/trips');
    // the backend saw the same idempotency key twice: exactly one trip with this purpose exists
    const ctx = await api('alice');
    const list = (await (await ctx.get('/api/v1/trips?limit=200')).json()) as {
      tripId: string;
      intent?: { purpose?: string };
    }[];
    expect(
      list.filter((t) => t.intent?.purpose === purpose).map((t) => t.tripId),
    ).toEqual([tripId]);
    await ctx.dispose();
  });

  test('a budget denial is explained and nothing is booked', async ({ page }) => {
    await seedPolicy((d) => {
      (d as { trip: unknown }).trip = { maxTotal: 30000, onViolation: 'DENY' };
    });
    try {
      await signIn(page, 'alice', '/trips/new');
      await fillRoundTrip(page, 'web e2e budget denial', tripDate(11), tripDate(12));
      await submitTrip(page);
      await waitForStatus(page, ['Failed']);
      await expect(page.getByText(/denied by policy/)).toBeVisible();
      await expect(page.getByRole('heading', { name: /Booking/ })).toHaveCount(0);
    } finally {
      await seedPolicy();
    }
  });
});
