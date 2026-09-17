import { expect, test } from '@playwright/test';
import { signIn } from './support';

test.describe('narrow screens', () => {
  test('the primary journeys are usable at phone width without horizontal scrolling', async ({
    page,
  }) => {
    await signIn(page, 'alice');
    const overflow = async () =>
      page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
    expect(await overflow()).toBeLessThanOrEqual(1);
    await page.getByRole('button', { name: 'Menu' }).click();
    await page
      .getByRole('navigation', { name: 'Main' })
      .getByRole('link', { name: 'New trip' })
      .click();
    await expect(page.getByRole('heading', { name: 'New trip' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Main' })).toHaveAttribute(
      'data-open',
      'false',
    );
    await page.waitForTimeout(250); // the drawer's closing transition
    expect(await overflow()).toBeLessThanOrEqual(1);
    await page.screenshot({ path: 'test-results/mobile-new-trip.png', fullPage: true });
    await page.getByRole('button', { name: 'Menu' }).click();
    await page
      .getByRole('navigation', { name: 'Main' })
      .getByRole('link', { name: 'Trips', exact: true })
      .click();
    await expect(page.getByRole('heading', { name: 'Trips' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Main' })).toHaveAttribute(
      'data-open',
      'false',
    );
    await page.waitForTimeout(250);
    expect(await overflow()).toBeLessThanOrEqual(1);
    // the list keeps what a person decides on; the route and the request date wait for the detail page
    await expect(page.getByRole('columnheader', { name: 'Status' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Total' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Route' })).toBeHidden();
    await page.screenshot({ path: 'test-results/mobile-trips.png', fullPage: true });
  });
});
