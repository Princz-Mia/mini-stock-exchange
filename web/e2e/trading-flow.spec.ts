import { randomUUID } from 'node:crypto';

import AxeBuilder from '@axe-core/playwright';
import { type APIRequestContext, type APIResponse, type Page, expect, request, test } from '@playwright/test';

import type { CsrfToken, OrderRequest } from '../src/api/types';

// Runs against a fresh stack whose only addition is the counterparty's login (seed-users.sql). The operator sets up
// the market through the API: NOVA at 100.00, and the dev trader and the counterparty with 100,000.00 and 500 shares
// each. The amounts below follow from that, so the test runs once per stack. Two bots are provisioned too: the last
// test runs them from the operator screen, after the trader's amounts are checked.

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

interface Session {
  api: APIRequestContext;
  /** A POST with the session's CSRF token and a fresh idempotency key, as the web client sends commands. */
  command: (path: string, data?: unknown) => Promise<APIResponse>;
}

/** The operator and the counterparty act through the API, so the browser only drives the dev trader. */
async function signedIn(username: string, password: string): Promise<Session> {
  const api = await request.newContext({ baseURL: required('E2E_BASE_URL') });
  const before = (await (await api.get('/api/v1/auth/csrf')).json()) as CsrfToken;
  const login = await api.post('/api/v1/auth/login', {
    headers: { [before.headerName]: before.token },
    data: { username, password },
  });
  expect(login.status(), `${username} login`).toBe(200);
  // Login rotates the session's token.
  const csrf = (await (await api.get('/api/v1/auth/csrf')).json()) as CsrfToken;
  const command = (path: string, data?: unknown) =>
    api.post(path, { headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() }, data });
  return { api, command };
}

async function expectStatus(response: APIResponse, status: number, what: string): Promise<void> {
  expect(response.status(), `${what}: ${await response.text()}`).toBe(status);
}

/** SETUP through the operator API: one instrument, two funded traders, two bots, then open. Returns NOVA's ID. */
async function setUpMarket(): Promise<string> {
  const operator = await signedIn('operator', required('E2E_OPERATOR_PASSWORD'));
  const added = await operator.command('/api/v1/admin/instruments', { symbol: 'NOVA', referencePrice: '100.00' });
  await expectStatus(added, 201, 'add NOVA');
  const instrumentId = ((await added.json()) as { instrumentId: string }).instrumentId;
  for (const username of ['trader', 'counterparty']) {
    const funded = await operator.command('/api/v1/admin/accounts', {
      username,
      cash: '100000.00',
      holdings: [{ instrumentId, quantity: 500 }],
    });
    await expectStatus(funded, 201, `fund ${username}`);
  }
  const bots = await operator.command('/api/v1/admin/bots', {
    count: 2,
    cash: '10000.00',
    holdings: [{ instrumentId, quantity: 100 }],
  });
  await expectStatus(bots, 201, 'provision two bots');
  await expectStatus(await operator.command('/api/v1/admin/market/open'), 200, 'open the market');
  await operator.api.dispose();
  return instrumentId;
}

async function signIn(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Username').fill('trader');
  await page.getByLabel('Password').fill(required('E2E_TRADER_PASSWORD'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('link', { name: 'Terminal' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'NOVA' })).toBeVisible();
}

test.describe.serial('trading through the browser', () => {
  test('sign in, buy into a partial fill, cancel the rest, and see it in the portfolio', async ({ page }) => {
    const instrumentId = await setUpMarket();
    const other = await signedIn('counterparty', required('E2E_COUNTERPARTY_PASSWORD'));
    const offer: OrderRequest = {
      instrumentId,
      side: 'SELL',
      type: 'LIMIT',
      timeInForce: 'GTC',
      quantity: 5,
      limitPrice: '101.00',
    };
    await expectStatus(await other.command('/api/v1/orders', offer), 201, 'counterparty offer');
    // Market Data learns about the instrument from Kafka, a moment after it was added.
    await expect
      .poll(async () => JSON.stringify(await (await other.api.get('/api/v1/instruments')).json()), { timeout: 30_000 })
      .toContain('"symbol":"NOVA"');
    await other.api.dispose();

    await signIn(page);
    const ticket = page.getByRole('region', { name: 'Order · NOVA' });
    const asks = page.getByRole('table', { name: 'Asks' });
    const bids = page.getByRole('table', { name: 'Bids' });
    const availableCash = ticket.getByRole('definition').first();

    // The live stream shows the counterparty's offer, and the ticket shows the funded balances.
    await expect(page.getByRole('status').filter({ hasText: 'Connected' })).toBeVisible();
    await expect(asks.getByRole('row').filter({ hasText: '101.00' })).toContainText('5');
    await expect(availableCash).toHaveText('100,000.00');
    await expect(ticket.getByRole('definition').nth(1)).toHaveText('500');

    // Buy 6 at 101.00 against the 5 offered: 5 fill at once, 1 rests as the best bid.
    await ticket.getByLabel('Shares').fill('6');
    await ticket.getByLabel('Limit price').fill('101.00');
    await expect(ticket).toContainText('Maximum spend 606.00');
    await ticket.getByRole('button', { name: 'Buy NOVA' }).click();
    await expect(ticket.getByRole('status')).toHaveText('Partly filled: 5 filled, 1 still working.');

    await expect(bids.getByRole('row').filter({ hasText: '101.00' })).toContainText('1');
    await expect(asks).not.toContainText('101.00');
    await expect(page.getByRole('table', { name: 'Recent trades' }).getByRole('row').nth(1)).toContainText('101.00');
    // 100,000.00 − 505.00 paid − 101.00 still reserved for the working share.
    await expect(availableCash).toHaveText('99,394.00');
    await expect(ticket.getByRole('definition').nth(1)).toHaveText('505');

    // Cancel the working share: the bid leaves the book and its reservation is released.
    const openOrders = page.getByRole('table', { name: 'Open orders' });
    await expect(openOrders.getByRole('row')).toHaveCount(2);
    await page.getByRole('button', { name: 'Cancel buy NOVA at 101.00' }).click();
    await expect(page.getByText('No open orders.')).toBeVisible();
    await expect(bids).not.toContainText('101.00');
    await expect(availableCash).toHaveText('99,495.00');

    // The canceled order is under Recent orders, with what had filled; narrowed to NOVA, it is still there.
    await page.getByRole('tab', { name: 'Recent orders' }).click();
    const recent = page.getByRole('table', { name: 'Recent orders' }).getByRole('row').nth(1);
    await expect(recent).toContainText('Canceled · 5 of 6 filled');
    await expect(recent).toContainText('101.00');
    await page.getByLabel('Only NOVA').check();
    await expect(page.getByRole('table', { name: 'Recent orders' }).getByRole('row').nth(1)).toContainText('Canceled');
    await page.getByRole('tab', { name: /Open orders/ }).click();

    // The portfolio agrees: cash, 505 shares marked at the 101.00 last trade, the execution and the canceled order.
    await page.getByRole('link', { name: 'Portfolio' }).click();
    const summary = page.getByRole('region', { name: 'Summary' });
    await expect(summary).toContainText('Estimated equity150,500.00');
    await expect(summary).toContainText('Cash99,495.00');
    await expect(summary).toContainText('Reserved for open orders0.00');
    const holding = page.getByRole('table', { name: 'Holdings' }).getByRole('row', { name: /NOVA/ });
    await expect(holding).toContainText('505');
    await expect(holding).toContainText('51,005.00');
    const execution = page.getByRole('table', { name: 'Executions' }).getByRole('row').nth(1);
    await expect(execution).toContainText('Buy');
    await expect(execution).toContainText('101.00');
    const order = page.getByRole('table', { name: 'Orders' }).getByRole('row').nth(1);
    await expect(order).toContainText('5 / 6');
    await expect(order).toContainText('canceled');
  });

  test('on a phone the ticket comes first and no page scrolls sideways', async ({ page }) => {
    const width = 390;
    const pageWidth = () => page.evaluate(() => document.documentElement.scrollWidth);
    await page.setViewportSize({ width, height: 844 });
    await signIn(page);
    const ticket = await page.getByRole('region', { name: 'Order · NOVA' }).boundingBox();
    const book = await page.getByRole('region', { name: 'Order book' }).boundingBox();
    expect(ticket).not.toBeNull();
    expect(book).not.toBeNull();
    expect(ticket!.y).toBeLessThan(book!.y);
    expect(await pageWidth(), 'terminal width').toBeLessThanOrEqual(width);

    await page.getByRole('link', { name: 'Portfolio' }).click();
    await expect(page.getByRole('table', { name: 'Orders' })).toBeVisible();
    expect(await pageWidth(), 'portfolio width').toBeLessThanOrEqual(width);
  });

  test('the operator halts and resumes trading, and the trader sees it', async ({ page, browser }) => {
    await signIn(page);
    const buy = page.getByRole('region', { name: 'Order · NOVA' }).getByRole('button', { name: 'Buy NOVA' });
    await expect(buy).toBeEnabled();

    // The operator works in a separate browser session, through the operator screen.
    const operatorContext = await browser.newContext();
    const operator = await operatorContext.newPage();
    await operator.goto('/');
    await operator.getByLabel('Username').fill('operator');
    await operator.getByLabel('Password').fill(required('E2E_OPERATOR_PASSWORD'));
    await operator.getByRole('button', { name: 'Sign in' }).click();
    await expect(operator.getByRole('heading', { name: 'Market' })).toBeVisible();
    await expect(operator.getByRole('button', { name: 'Open market' })).toHaveAccessibleDescription(
      'The market has already opened. It opens only once.',
    );
    await expect(operator.getByRole('button', { name: 'Resume trading' })).toBeDisabled();

    await operator.getByRole('button', { name: 'Halt trading' }).click();
    await expect(operator.getByText('Trading is halted. Cancels still work.')).toBeVisible();
    await expect(operator.getByRole('button', { name: 'Halt trading' })).toBeDisabled();

    // The trader's terminal follows the committed phase within its refresh interval.
    await expect(page.getByText('Trading halted')).toBeVisible({ timeout: 10_000 });
    await expect(buy).toBeDisabled();
    await expect(buy).toHaveAccessibleDescription(
      'Trading is halted. New orders are paused; you can still cancel open orders.',
    );

    await operator.getByRole('button', { name: 'Resume trading' }).click();
    await expect(operator.getByText('Trading has resumed.')).toBeVisible();
    await expect(page.getByText('Trading halted')).toBeHidden({ timeout: 10_000 });
    await expect(buy).toBeEnabled();
    await operatorContext.close();
  });

  test('the operator starts a bot run, watches it trade, and stops it', async ({ page, browser }) => {
    // The trader rests a bid above the last trade, in a browser of their own. The book is otherwise empty, so the
    // market maker's first ask, two ticks above the last trade, sells into it: a bot fills the resting order.
    const traderContext = await browser.newContext();
    const trader = await traderContext.newPage();
    await signIn(trader);
    const ticket = trader.getByRole('region', { name: 'Order · NOVA' });
    await ticket.getByLabel('Shares').fill('3');
    await ticket.getByLabel('Limit price').fill('101.50');
    await ticket.getByRole('button', { name: 'Buy NOVA' }).click();
    await expect(trader.getByRole('table', { name: 'Open orders' }).getByRole('row')).toHaveCount(2);

    await page.goto('/');
    await page.getByLabel('Username').fill('operator');
    await page.getByLabel('Password').fill(required('E2E_OPERATOR_PASSWORD'));
    await page.getByRole('button', { name: 'Sign in' }).click();
    const runs = page.getByRole('region', { name: 'Bot runs' });
    const form = runs.getByRole('form', { name: 'Start a bot run' });

    // The two bots from setup: one market maker to quote, one noise bot to trade against it.
    await form.getByLabel('Market makers').fill('1');
    await form.getByLabel('Noise').fill('1');
    await form.getByLabel('Momentum').fill('0');
    await form.getByLabel('Seed').fill('11');
    await form.getByRole('button', { name: 'Start run' }).click();

    // The simulator takes the run within its poll interval, and its counts arrive with each heartbeat.
    await expect(runs.getByRole('status', { name: 'Run status: Running' })).toBeVisible({ timeout: 30_000 });
    const current = runs.getByRole('group', { name: 'Current run' });
    await expect(current).toContainText('1 market maker, 1 noise');
    await expect(current).toContainText(/Placed[1-9]/, { timeout: 30_000 });

    // The trader is told, wherever they are, with a link to their portfolio.
    const notice = trader.getByRole('region', { name: 'Fills' });
    await expect(notice.getByRole('status')).toContainText(/Bought \d NOVA at 101\.50/, { timeout: 30_000 });
    await expect(notice.getByRole('status')).toContainText('Bought 3 NOVA at 101.50, the order is filled.', {
      timeout: 30_000,
    });
    // It passes the accessibility rules, and on a phone it fits the screen without the page scrolling sideways.
    const scan = await new AxeBuilder({ page: trader })
      .include('.fill-notice')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze();
    expect(scan.violations.map((violation) => violation.id)).toEqual([]);
    await trader.setViewportSize({ width: 390, height: 844 });
    const box = await notice.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    expect(await trader.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await trader.screenshot({ path: 'test-results/screens/phone-fill-notice.png' });
    await trader.setViewportSize({ width: 1280, height: 800 });
    await trader.screenshot({ path: 'test-results/screens/laptop-fill-notice.png' });
    await notice.getByRole('link', { name: 'View in Portfolio' }).click();
    await expect(notice).toBeHidden();
    const execution = trader.getByRole('table', { name: 'Executions' }).getByRole('row').nth(1);
    await expect(execution).toContainText('101.50');
    await expect(execution).toContainText('Maker');
    await traderContext.close();

    // Nothing on the operator screen scrolls sideways on a phone either.
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await page.setViewportSize({ width: 1280, height: 800 });

    // A stop drains, cancels the maker's quotes and is confirmed; then the form is back and the run is history.
    await current.getByRole('button', { name: 'Stop run' }).click();
    await expect(form).toBeVisible({ timeout: 30_000 });
    const last = runs.getByRole('table', { name: 'Recent runs' }).getByRole('row').nth(1);
    await expect(last).toContainText('1 market maker, 1 noise');
    await expect(last).toContainText(/\d{2}:\d{2}:\d{2} UTC$/);
    await expect(last).not.toContainText('not canceled');
  });
});
