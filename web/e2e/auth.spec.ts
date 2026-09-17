import { expect, test } from '@playwright/test';
import { signIn, signOut } from './support';

test.describe('sign-in and session', () => {
  test('signs in through Keycloak with PKCE, restores after reload, signs out, and isolates caches between users', async ({
    page,
  }) => {
    await signIn(page, 'alice');
    await expect(page.getByRole('banner')).toContainText('Alice');
    // tokens are not persisted: nothing in local or session storage carries an access token
    const stored = await page.evaluate(() =>
      JSON.stringify({ ...window.localStorage, ...window.sessionStorage }),
    );
    expect(stored).not.toMatch(/access_token/);
    // a reload restores the session silently through Keycloak's SSO cookie
    await page.reload();
    await expect(page.getByRole('banner')).toContainText('Alice', { timeout: 20_000 });
    await expect(page.getByRole('navigation', { name: 'Main' })).not.toContainText('Approvals');
    await signOut(page);
    // bob sees his own workspace, never alice's cached data
    await signIn(page, 'bob');
    await expect(page.getByRole('banner')).toContainText('Bob');
    await expect(page.getByRole('navigation', { name: 'Main' })).toContainText('Approvals');
    await expect(page.getByRole('heading', { name: /Hello, Bob/ })).toBeVisible();
    await signOut(page);
  });

  test('protected routes ask to sign in and role-gated areas are refused to a traveler', async ({
    page,
  }) => {
    await page.goto('/learning');
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await signIn(page, 'alice', '/learning');
    await expect(page.getByRole('heading', { name: 'Not available for your role' })).toBeVisible();
    await page.goto('/connectors');
    await expect(page.getByRole('heading', { name: 'Not available for your role' })).toBeVisible();
  });

  test('an expired token sends the user back to sign in', async ({ page }) => {
    await signIn(page, 'alice');
    // simulate the token expiring by making the API refuse it: the client drops the session on a 401
    await page.route('**/api/v1/trips?*', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: '{"status":401,"code":"UNAUTHENTICATED","title":"Unauthorized","detail":"token expired"}',
      }),
    );
    await page.goto('/trips');
    await expect(page.getByText('Your session ended.')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });
});
