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

test.describe('manager approvals', () => {
  test('a trip needing approval waits; the traveler cannot self-approve; the manager approves; a stale decision conflicts', async ({
    page,
  }) => {
    await seedPolicy((d) => {
      (d as { approval: { managerRequiredAbove: number } }).approval.managerRequiredAbove = 1;
    });
    let tripId = '';
    try {
      await signIn(page, 'alice', '/trips/new');
      const purpose = `web e2e approval ${Date.now()}`; // earlier runs' trips stay in the inbox
      await fillRoundTrip(page, purpose, tripDate(14), tripDate(15));
      tripId = await submitTrip(page);
      await waitForStatus(page, ['Awaiting approval']);
      await expect(page.getByText('You cannot approve your own trip.')).toBeVisible();
      await expect(page.getByRole('button', { name: 'Approve' })).toHaveCount(0);
      // bypassing the UI: the backend refuses self-approval
      const alice = await api('alice');
      const self = await alice.post(`/api/v1/trips/${tripId}/approval`, {
        data: { decision: 'APPROVE' },
        headers: { 'Idempotency-Key': `e2e-self-${tripId}` },
      });
      expect(self.status()).toBe(403);
      await alice.dispose();
      await signOut(page);
      await signIn(page, 'bob', '/approvals');
      const row = page.getByRole('row').filter({ hasText: purpose });
      await expect(row).toBeVisible();
      await row.getByRole('link').first().click();
      await expect(page.getByRole('heading', { level: 1 })).toContainText('Awaiting approval');
      await expect(page.getByText(/Needs approval|USD/).first()).toBeVisible();
      await page.getByLabel('Comment for the traveler (optional)').fill('approved by web e2e');
      await page.getByRole('button', { name: 'Approve' }).click();
      await waitForStatus(page, ['Booked', 'Booking', 'Approved']);
      await waitForStatus(page, ['Booked']);
      // deciding again is refused: the version moved on
      const bob = await api('bob');
      const stale = await bob.post(`/api/v1/trips/${tripId}/approval`, {
        data: { decision: 'REJECT' },
        headers: { 'Idempotency-Key': `e2e-stale-${tripId}` },
      });
      expect([409, 422]).toContain(stale.status());
      await bob.dispose();
    } finally {
      await seedPolicy();
    }
  });

  test('a rejected trip ends honestly', async ({ page }) => {
    await seedPolicy((d) => {
      (d as { approval: { managerRequiredAbove: number } }).approval.managerRequiredAbove = 1;
    });
    try {
      await signIn(page, 'dan', '/trips/new');
      await fillRoundTrip(page, 'web e2e rejection', tripDate(17), tripDate(18));
      const tripId = await submitTrip(page);
      await waitForStatus(page, ['Awaiting approval']);
      await signOut(page);
      await signIn(page, 'bob', `/trips/${tripId}`);
      await page.getByRole('button', { name: 'Reject' }).click();
      await waitForStatus(page, ['Failed', 'Cancelled']);
      await expect(page.getByText(/rejected|Not booked/i).first()).toBeVisible();
    } finally {
      await seedPolicy();
    }
  });
});
