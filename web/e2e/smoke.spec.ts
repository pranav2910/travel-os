import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { signIn } from './support';

test.describe('smoke (every browser project)', () => {
  test('signs in, opens the overview and the new-trip form, and passes an accessibility scan', async ({
    page,
  }) => {
    await signIn(page, 'alice');
    await expect(page.getByRole('heading', { name: /Hello, Alice/ })).toBeVisible();
    const overview = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    expect(overview.violations.map((v) => `${v.id}: ${v.nodes.length}`)).toEqual([]);
    await page.screenshot({
      path: `test-results/desktop-overview-${test.info().project.name}.png`,
      fullPage: true,
    });
    await page.goto('/trips/new');
    await expect(page.getByRole('heading', { name: 'New trip' })).toBeVisible();
    const form = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    expect(form.violations.map((v) => `${v.id}: ${v.nodes.length}`)).toEqual([]);
    // keyboard: the first field is reachable and focus is visible
    await page.keyboard.press('Tab');
    await expect(page.locator(':focus')).toBeVisible();
    await page.screenshot({
      path: `test-results/desktop-new-trip-${test.info().project.name}.png`,
      fullPage: true,
    });
  });
});
