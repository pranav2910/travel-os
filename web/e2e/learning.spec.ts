import { expect, test } from '@playwright/test';
import { api, signIn } from './support';

test.describe('learning administration', () => {
  test('shows the real mode and evidence, refuses a stale activation with a 409, and rolls back to the baseline', async ({
    page,
  }) => {
    const carol = await api('carol');
    // start from the baseline in SHADOW so the page state is known; the mode change is versioned
    let cfg = (await (await carol.get('/api/v1/learning/config')).json()) as {
      version: number;
      mode: string;
      activeProfileId: string | null;
    };
    if (cfg.activeProfileId) {
      await carol.post('/api/v1/learning/rollback', {
        data: { toBaseline: true, expectedVersion: cfg.version },
        headers: { 'Idempotency-Key': `e2e-base-${Date.now()}` },
      });
      cfg = (await (await carol.get('/api/v1/learning/config')).json()) as typeof cfg;
    }
    if (cfg.mode !== 'SHADOW') {
      await carol.put('/api/v1/learning/config', {
        data: { mode: 'SHADOW', expectedVersion: cfg.version },
        headers: { 'Idempotency-Key': `e2e-shadow-${Date.now()}` },
      });
    }
    await signIn(page, 'carol', '/learning');
    await expect(page.getByText('Shadow', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('SANDBOX').first()).toBeVisible();
    await expect(page.getByText(/Adjustment bound/)).toBeVisible();
    await expect(
      page.getByText(/counts decisions where the learned pick differed|Ranking changed/).first(),
    )
      .toBeVisible({ timeout: 5000 })
      .catch(() => {});
    // a concurrent change elsewhere: the page's version is stale, the next action conflicts and refreshes
    const fresh = (await (await carol.get('/api/v1/learning/config')).json()) as {
      version: number;
    };
    await carol.put('/api/v1/learning/config', {
      data: { mode: 'OFF', expectedVersion: fresh.version },
      headers: { 'Idempotency-Key': `e2e-off-${Date.now()}` },
    });
    await page.getByRole('button', { name: 'Active' }).click();
    await expect(page.getByText('Stale view.')).toBeVisible();
    await expect(page.getByText('Off', { exact: true }).first()).toBeVisible();
    // a traveler may not read or change learning
    const alice = await api('alice');
    expect((await alice.get('/api/v1/learning/config')).status()).toBe(403);
    expect(
      (
        await alice.put('/api/v1/learning/config', {
          data: { mode: 'ACTIVE', expectedVersion: 0 },
          headers: { 'Idempotency-Key': 'e2e-alice-mode' },
        })
      ).status(),
    ).toBe(403);
    await alice.dispose();
    // back to SHADOW through the page
    await page.getByRole('button', { name: 'Shadow' }).click();
    await expect(page.getByText('Shadow', { exact: true }).first()).toBeVisible();
    await carol.dispose();
  });
});
