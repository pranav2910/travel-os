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

/**
 * BUG-08: a booked trip is never shown as cancelled while its reservation is confirmed at a
 * supplier. Verified against the application (trip status, order status) AND the supplier's own
 * state (the sandbox order the supplier gateway holds), not the app's word alone.
 */
test.describe('cancelling a booked trip', () => {
  test.beforeAll(async () => {
    await seedPolicy();
  });

  test('a booked round trip is released at the airline before it reads Cancelled', async ({
    page,
  }) => {
    await signIn(page, 'alice', '/trips/new');
    await fillRoundTrip(page, 'web e2e cancel booked', tripDate(12), tripDate(14));
    const tripId = await submitTrip(page);
    await waitForStatus(page, ['Booked']);
    const alice = await api('alice');
    const before = (await (await alice.get(`/api/v1/orders?tripId=${tripId}`)).json()) as {
      orderId: string;
      status: string;
      items: { externalRef?: string; status: string }[];
    }[];
    expect(before).toHaveLength(1);
    expect(before[0]!.status).toBe('CONFIRMED');

    await page.getByRole('button', { name: 'Cancel trip' }).click();
    await expect(page.getByRole('dialog')).toContainText('released at the suppliers first');
    await page.getByLabel('Reason').fill('meeting moved to video');
    await page.getByRole('button', { name: 'Cancel the trip' }).click();
    // the honest intermediate state: Cancelling, with the reason on screen
    await waitForStatus(page, ['Cancelling', 'Cancelled'], 30_000);
    const heading = page.getByRole('heading', { level: 1 });
    if ((await heading.textContent())?.includes('Cancelling')) {
      await expect(page.getByText('Cancelling.', { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Cancel trip' })).toHaveCount(0);
    }
    // ... and then Cancelled, only once the order is
    await waitForStatus(page, ['Cancelled'], 120_000);
    await expect(page.getByRole('button', { name: 'Cancel trip' })).toHaveCount(0);
    const after = (await (await alice.get(`/api/v1/orders?tripId=${tripId}`)).json()) as {
      status: string;
      items: { status: string }[];
    }[];
    expect(after[0]!.status).toBe('CANCELLED');
    expect(after[0]!.items.map((i) => i.status)).toEqual(['CANCELLED']);
    // the audit ledger agrees with the app and with the order
    const ledger = (await (await alice.get(`/api/v1/audit/trips/${tripId}/decisions`)).json()) as {
      status?: string;
      order?: { status?: string };
    };
    expect(ledger.status).toBe('CANCELLED');
    expect(ledger.order?.status).toBe('CANCELLED');
    // a second request changes nothing
    const again = await alice.post(`/api/v1/trips/${tripId}/cancellation`, {
      headers: { 'Idempotency-Key': `e2e-cancel-again-${tripId}` },
      data: { reason: 'again' },
    });
    expect(again.status()).toBe(200);
    expect(((await again.json()) as { status: string }).status).toBe('CANCELLED');
  });

  test('a supplier that refuses keeps the trip Cancelling until Finance resolves the exposure', async ({
    page,
  }) => {
    // the LAX fixture: the cheapest property refuses cancellation
    await signIn(page, 'alice', '/trips/new');
    await page.getByRole('radio', { name: /Multi-city itinerary/ }).check();
    await page.getByLabel('Purpose of travel').fill('web e2e cancel refused');
    const d0 = tripDate(16),
      d1 = tripDate(17);
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
    const tripId = await submitTrip(page);
    await waitForStatus(page, ['Booked', 'Failed']);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Booked');

    await page.getByRole('button', { name: 'Cancel trip' }).click();
    await page.getByLabel('Reason').fill('conference cancelled');
    await page.getByRole('button', { name: 'Cancel the trip' }).click();
    await waitForStatus(page, ['Cancelling'], 30_000);
    // the refusal is on screen, the trip is NOT cancelled, and the order says the same
    await expect(page.getByText('Cancellation incomplete.')).toBeVisible({ timeout: 90_000 });
    await expect(page.getByRole('alert').filter({ hasText: /refused to release/ })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Cancelling');
    const alice = await api('alice');
    const pending = (await (await alice.get(`/api/v1/orders?tripId=${tripId}`)).json()) as {
      orderId: string;
      status: string;
      failureCode?: string;
      items: { type: string; status: string }[];
      exposures?: { exposureId: string; status: string }[];
    }[];
    expect(pending[0]!.status).toBe('CANCELLATION_PENDING');
    expect(pending[0]!.failureCode).toBe('CANCELLATION_INCOMPLETE');
    const hotel = pending[0]!.items.find((i) => i.type === 'HOTEL')!;
    expect(hotel.status).toBe('CANCEL_FAILED');
    // both legs were released; only the non-refundable room was not
    const air = pending[0]!.items.filter((i) => i.type === 'AIR');
    expect(air.length).toBeGreaterThan(0);
    expect(air.every((i) => i.status === 'CANCELLED')).toBe(true);
    const open = pending[0]!.exposures!.filter((e) => e.status === 'OPEN');
    expect(open).toHaveLength(1);

    // Finance releases the room by phone and resolves the exposure: now the trip is Cancelled
    const carol = await api('carol');
    const resolved = await carol.post(
      `/api/v1/orders/${pending[0]!.orderId}/exposures/${open[0]!.exposureId}/resolution`,
      {
        headers: { 'Idempotency-Key': `e2e-resolve-${open[0]!.exposureId}` },
        data: { resolution: 'released by phone with the property; no refund' },
      },
    );
    expect(resolved.status()).toBe(200);
    await waitForStatus(page, ['Cancelled'], 200_000);
    const after = (await (await alice.get(`/api/v1/orders?tripId=${tripId}`)).json()) as {
      status: string;
    }[];
    expect(after[0]!.status).toBe('CANCELLED');
  });
});
