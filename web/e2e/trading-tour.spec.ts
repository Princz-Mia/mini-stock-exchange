import AxeBuilder from '@axe-core/playwright';
import { type Browser, type Page, expect, test } from '@playwright/test';

// The first-login tour in a real browser. The newcomer (seeded by the gate, with no tour seen) gets it on their first
// sign-in and not on the next; Help replays it; the operator's tour replays the same way. Every step passes an
// accessibility scan, and the card fits a phone. Runs after trading-flow.spec.ts, which opened the market, so every
// part of both start pages is there to point at. Every other browser test signs in as a user who has seen the tour.

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

const PASSWORDS = {
  newcomer: () => required('E2E_COUNTERPARTY_PASSWORD'),
  operator: () => required('E2E_OPERATOR_PASSWORD'),
} as const;

/** What the server said about the tour on sign-in, kept per page. */
const tourAnswers = new WeakMap<Page, { seenVersion: number }>();

async function signIn(browser: Browser, username: keyof typeof PASSWORDS, width = 1440, height = 900): Promise<Page> {
  const context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(PASSWORDS[username]());
  // The app asks whether to show the tour as soon as the user is signed in; wait for that answer.
  const tourRead = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/me/tour' && response.request().method() === 'GET',
  );
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();
  tourAnswers.set(page, (await (await tourRead).json()) as { seenVersion: number });
  return page;
}

/** The WCAG 2.2 A and AA rules axe can check automatically, as in the screen audit. */
async function noViolations(page: Page, where: string): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  expect(
    results.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`),
    where,
  ).toEqual([]);
}

/** Does what ends the tour and waits until the server has stored it as seen. */
async function recorded(page: Page, action: () => Promise<void>): Promise<void> {
  const saved = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/me/tour' && response.request().method() === 'PUT',
  );
  await action();
  expect((await saved).status()).toBe(200);
}

/** Walks the whole tour from its welcome, scanning each step and checking its part of the page is outlined. */
async function walk(page: Page, role: string): Promise<void> {
  const tour = page.getByRole('dialog');
  await expect(tour).toBeVisible();
  await noViolations(page, `${role} welcome`);
  await tour.getByRole('button', { name: 'Start the tour' }).click();
  for (let step = 1; ; step++) {
    const progress = tour.getByText(/^Step \d+ of \d+$/);
    await expect(progress).toHaveText(new RegExp(`^Step ${step} of `));
    const outlined = await page.locator('.tour-target').count();
    expect(outlined, `${role} step ${step} outlines its part`).toBe(1);
    await noViolations(page, `${role} step ${step}`);
    const done = tour.getByRole('button', { name: 'Done' });
    if (await done.isVisible()) {
      await recorded(page, () => done.click());
      break;
    }
    await tour.getByRole('button', { name: 'Next' }).click();
  }
  await expect(tour).toBeHidden();
  expect(await page.locator('.tour-target').count()).toBe(0);
}

test('a newcomer gets the tour on the first sign-in only, and Help replays it', async ({ browser }) => {
  const first = await signIn(browser, 'newcomer');
  expect(tourAnswers.get(first)).toEqual({ seenVersion: 0 });
  await expect(first.getByRole('dialog', { name: 'Welcome to Mini Stock Exchange' })).toBeVisible();
  await walk(first, 'trader');
  await first.context().close();

  // Another browser, signed in again: the server remembers it was seen, and the page, once it has used that answer
  // (Help works only with no tour in front of it), shows none.
  const again = await signIn(browser, 'newcomer');
  expect(tourAnswers.get(again)?.seenVersion).toBeGreaterThanOrEqual(1);
  await expect(again.getByRole('region', { name: 'Watchlist' })).toBeVisible();
  await again.getByRole('button', { name: 'Help' }).click();
  await expect(again.getByRole('button', { name: 'Take the tour' })).toBeVisible();
  await expect(again.getByRole('dialog')).toHaveCount(0);
  await again.keyboard.press('Escape');

  await again.getByRole('link', { name: 'Portfolio' }).click();
  await again.getByRole('button', { name: 'Help' }).click();
  await again.getByRole('button', { name: 'Take the tour' }).click();
  await expect(again).toHaveURL(/\/terminal/);
  await expect(again.getByRole('dialog', { name: 'Welcome to Mini Stock Exchange' })).toBeVisible();
  await recorded(again, () => again.keyboard.press('Escape'));
  await expect(again.getByRole('dialog')).toBeHidden();
  // Focus is back on Help, not lost at the top of the page.
  await expect(again.getByRole('button', { name: 'Help' })).toBeFocused();
  await again.context().close();
});

test("the operator's tour replays from Help and points at every part of the open market", async ({ browser }) => {
  const page = await signIn(browser, 'operator');
  await expect(page.getByRole('region', { name: 'Health' })).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: 'Help' }).click();
  await page.getByRole('button', { name: 'Take the tour' }).click();
  await walk(page, 'operator');
  await page.context().close();
});

test('the Help list and the tour card fit a phone, and the keyboard stays inside the card', async ({ browser }) => {
  const page = await signIn(browser, 'operator', 390, 844);
  await page.getByRole('button', { name: 'Help' }).click();
  const list = await page.getByRole('button', { name: 'Take the tour' }).boundingBox();
  expect(list).not.toBeNull();
  expect(list!.x).toBeGreaterThanOrEqual(0);
  expect(list!.x + list!.width).toBeLessThanOrEqual(390);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.getByRole('button', { name: 'Take the tour' }).click();
  const tour = page.getByRole('dialog');
  await tour.getByRole('button', { name: 'Start the tour' }).click();
  const box = await tour.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  // A modal dialog: Tab goes through its own buttons and never reaches the page behind it (past the last button the
  // browser may hold focus on the document itself before coming back).
  for (let press = 0; press < 6; press++) {
    await page.keyboard.press('Tab');
    const inPage = await page.evaluate(() => {
      const focused = document.activeElement;
      return focused !== null && focused !== document.body && focused.closest('dialog') === null;
    });
    expect(inPage, `Tab ${press + 1} reached the page behind the tour`).toBe(false);
  }
  await noViolations(page, 'operator step 1 on a phone');
  await page.context().close();
});
