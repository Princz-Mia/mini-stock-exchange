import AxeBuilder from '@axe-core/playwright';
import { type Browser, type Page, expect, test } from '@playwright/test';

// The term tips and "How this exchange works" in a real browser: a tip opens above the page beside its term, stays on
// screen at phone width even at the right edge, passes an accessibility scan while open, and closes on Escape, on a
// second click of its button and on a click elsewhere; the page opens from Help and passes the same scan at both widths. Runs after
// trading-flow.spec.ts, which opened the market and funded the trader, who has seen the tour.

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

async function signIn(browser: Browser, width: number, height: number): Promise<Page> {
  const context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByLabel('Username').fill('trader');
  await page.getByLabel('Password').fill(required('E2E_TRADER_PASSWORD'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();
  return page;
}

async function noViolations(page: Page, where: string): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  expect(
    results.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`),
    where,
  ).toEqual([]);
}

/** Opens a tip and checks it is shown, on screen, and below its button. */
async function open(page: Page, name: string, width: number) {
  const button = page.getByRole('button', { name });
  await button.click();
  await expect(button).toHaveAttribute('aria-expanded', 'true');
  const tip = page.locator(`#${await button.getAttribute('aria-controls')}`);
  await expect(tip).toBeVisible();
  const [box, anchor] = [await tip.boundingBox(), await button.boundingBox()];
  expect(box!.x, `${name}: left edge`).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width, `${name}: right edge`).toBeLessThanOrEqual(width);
  expect(box!.y, `${name}: below its button`).toBeGreaterThan(anchor!.y);
  return { button, tip };
}

for (const [size, width, height] of [
  ['desktop', 1440, 900],
  ['phone', 390, 844],
] as const) {
  test(`term tips open beside their terms and close again, at ${size} width`, async ({ browser }) => {
    const page = await signIn(browser, width, height);
    await expect(page.getByRole('region', { name: 'Order · NOVA' })).toBeVisible();

    const types = await open(page, 'What are limit order and protected market order?', width);
    await expect(types.tip).toContainText('Protected market order');
    await noViolations(page, `${size}: order type tip open`);
    await page.keyboard.press('Escape');
    await expect(types.tip).toBeHidden();
    await expect(types.button).toHaveAttribute('aria-expanded', 'false');
    // The button is the tip's invoker: clicking it again closes it, rather than light dismiss and the click undoing
    // each other.
    await open(page, 'What are limit order and protected market order?', width);
    await types.button.click();
    await expect(types.tip).toBeHidden();
    await expect(types.button).toHaveAttribute('aria-expanded', 'false');

    await page.getByRole('link', { name: 'Portfolio' }).click();
    // The rightmost figure on a phone: the tip must move left to stay on screen.
    const reserved = await open(page, 'What is reserved?', width);
    await page.mouse.click(5, height - 5);
    await expect(reserved.tip).toBeHidden();
    await page.context().close();
  });

  test(`How this exchange works opens from Help, at ${size} width`, async ({ browser }) => {
    const page = await signIn(browser, width, height);
    await page.getByRole('button', { name: 'Help' }).click();
    await page.getByRole('link', { name: 'How this exchange works' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'How this exchange works' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Glossary' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await noViolations(page, `${size}: How this exchange works`);
    await page.context().close();
  });
}
