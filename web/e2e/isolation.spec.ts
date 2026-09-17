import { expect, test } from '@playwright/test';
import {
  api,
  fillRoundTrip,
  seedPolicy,
  signIn,
  signOut,
  submitTrip,
  tripDate,
  waitForStatus,
} from './support';

test.describe('tenant and traveler isolation', () => {
  test("a deep link to another tenant's trip is not found; a peer traveler is not found; direct mutations are refused", async ({
    page,
  }) => {
    await seedPolicy();
    await signIn(page, 'alice', '/trips/new');
    await fillRoundTrip(page, 'web e2e isolation', tripDate(34), tripDate(35));
    const tripId = await submitTrip(page);
    await waitForStatus(page, ['Booked', 'Awaiting approval', 'Failed']);
    const zoe = await api('zoe');
    expect((await zoe.get(`/api/v1/trips/${tripId}`)).status()).toBe(404);
    expect(
      (
        await zoe.post(`/api/v1/trips/${tripId}/cancellation`, {
          data: { reason: 'x' },
          headers: { 'Idempotency-Key': 'e2e-zoe-cancel' },
        })
      ).status(),
    ).toBe(404);
    await zoe.dispose();
    const dan = await api('dan');
    expect((await dan.get(`/api/v1/trips/${tripId}`)).status()).toBe(404);
    expect(
      (
        await dan.post('/api/v1/learning/feedback', {
          data: { tripId, rating: 1, tags: ['AVOID'] },
          headers: { 'Idempotency-Key': 'e2e-dan-feedback' },
        })
      ).status(),
    ).toBe(403);
    await dan.dispose();
    // in the browser, the other tenant's user sees "not found", never the trip
    await signOut(page); // wait for Keycloak's end-session round trip: a half-ended session signs alice back in
    await signIn(page, 'zoe', `/trips/${tripId}`);
    await expect(page.getByRole('heading', { name: 'Not found' })).toBeVisible();
    await expect(page.getByText('web e2e isolation')).toHaveCount(0);
  });
});
