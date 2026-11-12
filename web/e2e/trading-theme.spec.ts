import { type Page, expect, test } from '@playwright/test';

// The theme switch in a real browser: a stored choice wins over the system's scheme, applies before the app has drawn
// anything (no flash of the other scheme, even on the sign-in page), reaches other tabs, and is remembered across a
// reload. Runs last; it only signs in and changes this browser's own display preference.

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

/** The page background in the dark and the light palettes, as the browser reports a computed colour. */
const DARK_BACKGROUND = 'rgb(18, 16, 23)';
const LIGHT_BACKGROUND = 'rgb(246, 245, 248)';

const background = (page: Page) => page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);

test('a stored dark theme is on the sign-in page before any app code runs, on a light system', async ({ browser }) => {
  const context = await browser.newContext({ colorScheme: 'light' });
  const page = await context.newPage();
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  expect(await background(page)).toBe(LIGHT_BACKGROUND);
  await page.evaluate(() => window.localStorage.setItem('mse.theme', 'dark'));

  // Note the theme at the moment the body is created: theme.js must already have run by then, not merely before the
  // app's code. A deferred or async theme.js would leave this unset.
  await page.addInitScript(() => {
    const record = window as unknown as { themeAtBody?: string | null };
    new MutationObserver((changes, observer) => {
      if (changes.some((change) => [...change.addedNodes].some((node) => node.nodeName === 'BODY'))) {
        record.themeAtBody = document.documentElement.getAttribute('data-theme');
        observer.disconnect();
      }
    }).observe(document, { childList: true, subtree: true });
  });

  // Hold the app's code back, so what the page shows meanwhile is what a slow connection would show first.
  let release: () => void = () => undefined;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route('**/assets/*.js', async (route) => {
    await held;
    await route.continue();
  });
  // The new page asks for theme.js only once it has replaced the old one, so the wait below sees the new page.
  const headScript = page.waitForResponse((response) => new URL(response.url()).pathname === '/theme.js');
  const reloaded = page.reload();
  await headScript;
  await page.waitForFunction(
    () => document.body !== null && getComputedStyle(document.documentElement).backgroundColor !== 'rgba(0, 0, 0, 0)',
    undefined,
    { polling: 50 },
  );
  expect(await page.locator('#root').innerHTML(), 'the app has not drawn anything yet').toBe('');
  expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe('dark');
  expect(await page.evaluate(() => (window as unknown as { themeAtBody?: string | null }).themeAtBody)).toBe('dark');
  expect(await background(page)).toBe(DARK_BACKGROUND);

  release();
  await reloaded;
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  expect(await background(page)).toBe(DARK_BACKGROUND);
  await context.close();
});

test('the switch forces a theme over the system, in other tabs too, keeps it across a reload, and System follows the system again', async ({
  browser,
}) => {
  const context = await browser.newContext({ colorScheme: 'dark' });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByLabel('Username').fill('trader');
  await page.getByLabel('Password').fill(required('E2E_TRADER_PASSWORD'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  const theme = page.getByLabel('Theme');
  await expect(theme).toHaveValue('system');
  expect(await background(page)).toBe(DARK_BACKGROUND);

  // A second tab follows the choice made in the first: its switch only shows what is applied, so this is the app's
  // own listener for other tabs at work.
  const other = await context.newPage();
  await other.goto('/');
  await expect(other.getByLabel('Theme')).toHaveValue('system');
  expect(await background(other)).toBe(DARK_BACKGROUND);

  await theme.selectOption('light');
  expect(await background(page)).toBe(LIGHT_BACKGROUND);
  await expect.poll(() => background(other)).toBe(LIGHT_BACKGROUND);
  await expect(other.getByLabel('Theme')).toHaveValue('light');
  await other.close();

  await page.reload();
  await expect(page.getByLabel('Theme')).toHaveValue('light');
  expect(await background(page)).toBe(LIGHT_BACKGROUND);

  await page.getByLabel('Theme').selectOption('system');
  expect(await background(page)).toBe(DARK_BACKGROUND);
  expect(await page.evaluate(() => Object.keys(window.localStorage)), 'following the system stores nothing').toEqual([]);
  await context.close();
});
