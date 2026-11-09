import { type Page, expect, test } from '@playwright/test';

// What an automated accessibility scan can't judge on its own: an order placed and cancelled with the keyboard
// alone, a visible focus ring on every stop along the way, no motion when the system asks for less, and buy/sell
// never told by colour alone. Runs after trading-flow.spec.ts, which set up and opened the market; the order rests
// far below the market and is cancelled, so the screens that follow see the same state.

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

/** What the focused element is, and whether it shows a focus ring: an outline, or a box shadow standing in for one. */
async function focused(page: Page): Promise<{ label: string; ring: boolean }> {
  return page.evaluate(() => {
    const element = document.activeElement as HTMLElement | null;
    if (element === null || element === document.body) {
      return { label: 'body', ring: true };
    }
    const style = getComputedStyle(element);
    const outline = style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) >= 2;
    const label =
      element.getAttribute('aria-label') ??
      (element.id === '' ? '' : `#${element.id}`) +
        (element.textContent ?? '').trim().slice(0, 40);
    return { label: `${element.tagName.toLowerCase()} ${label}`, ring: outline || style.boxShadow !== 'none' };
  });
}

/**
 * Presses Tab until the focused element matches, checking the focus ring at every stop. Fails if it takes more than
 * `limit` presses, which would also mean the element can't be reached in a sensible order.
 */
async function tabTo(page: Page, matches: (label: string) => boolean, limit = 40): Promise<string[]> {
  const stops: string[] = [];
  for (let i = 0; i < limit; i++) {
    await page.keyboard.press('Tab');
    const now = await focused(page);
    stops.push(now.label);
    expect(now.ring, `no visible focus on ${now.label}`).toBe(true);
    if (matches(now.label)) {
      return stops;
    }
  }
  throw new Error(`not reached within ${limit} Tab presses: ${stops.join(' > ')}`);
}

test('an order placed and cancelled with the keyboard alone, with focus visible at every stop', async ({ page }) => {
  await page.goto('/');
  await tabTo(page, (label) => label.includes('#username'));
  await page.keyboard.type('trader');
  await tabTo(page, (label) => label.includes('#password'), 1);
  await page.keyboard.type(required('E2E_TRADER_PASSWORD'));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();

  await tabTo(page, (label) => label.startsWith('a') && label.includes('Terminal'));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { level: 1, name: 'NOVA' })).toBeVisible();

  await tabTo(page, (label) => label.includes('#ticket-quantity'));
  await page.keyboard.type('1');
  await tabTo(page, (label) => label.includes('#ticket-price'), 1);
  await page.keyboard.type('1.00');
  await page.keyboard.press('Enter');
  const ticket = page.getByRole('region', { name: 'Order · NOVA' });
  await expect(ticket.getByRole('status')).toContainText('Accepted and resting');
  const cancel = page.getByRole('button', { name: 'Cancel buy NOVA at 1.00' });
  await expect(cancel).toBeVisible();

  await tabTo(page, (label) => label.includes('Cancel buy NOVA at 1.00'), 60);
  await page.keyboard.press('Enter');
  await expect(cancel).toHaveCount(0);
});

test('nothing moves when the system asks for reduced motion', async ({ browser }) => {
  const context = await browser.newContext({ reducedMotion: 'reduce' });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByLabel('Username').fill('trader');
  await page.getByLabel('Password').fill(required('E2E_TRADER_PASSWORD'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('link', { name: 'Terminal' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'NOVA' })).toBeVisible();
  const moving = await page.evaluate(() =>
    [...document.querySelectorAll('*')]
      .map((element) => ({ element, style: getComputedStyle(element) }))
      .filter(({ style }) =>
        [style.transitionDuration, style.animationDuration].some((durations) =>
          durations.split(',').some((duration) => parseFloat(duration) > 0),
        ),
      )
      .map(({ element }) => `${element.tagName.toLowerCase()}.${[...element.classList].join('.')}`),
  );
  expect(moving, 'elements that still animate').toEqual([]);
  await context.close();
});

test('buy and sell are written out wherever colour marks them', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Username').fill('trader');
  await page.getByLabel('Password').fill(required('E2E_TRADER_PASSWORD'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  for (const screen of ['Terminal', 'Portfolio']) {
    await page.getByRole('link', { name: screen }).click();
    await expect(page.locator('.side-buy, .side-sell').first()).toBeVisible();
    const marks = await page
      .locator('.side-buy, .side-sell')
      .evaluateAll((cells) =>
        cells.map((cell) => ({ side: cell.classList.contains('side-buy') ? 'Buy' : 'Sell', text: cell.textContent })),
      );
    for (const mark of marks) {
      expect(mark.text?.trim(), `a ${mark.side.toLowerCase()} marked by colour on the ${screen} screen`).toBe(
        mark.side,
      );
    }
  }
});
