import { mkdirSync, writeFileSync } from 'node:fs';

import AxeBuilder from '@axe-core/playwright';
import { type Browser, type Page, type Route, expect, test } from '@playwright/test';

// Every screen and the states it can be in, at the three sizes the screens are checked at. Runs after
// trading-flow.spec.ts (files run in name order on one worker), which set up the market and left the trader holding
// NOVA. States the live stack won't produce on demand (a busy exchange, a rate limit, a lost stream, a service
// down) are made by answering those requests in the browser. Each case must not scroll sideways, must show its state
// and must pass an accessibility scan; a full-page screenshot of each goes to test-results/screens/ for review, and
// each scan's results to test-results/accessibility/.

const SIZES = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'laptop', width: 1280, height: 720 },
  { name: 'phone', width: 390, height: 844 },
] as const;

type Size = (typeof SIZES)[number];

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

async function signIn(page: Page, username: 'trader' | 'operator'): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Username').fill(username);
  await page
    .getByLabel('Password')
    .fill(required(username === 'trader' ? 'E2E_TRADER_PASSWORD' : 'E2E_OPERATOR_PASSWORD'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();
}

/** The WCAG 2.2 A and AA rules axe can check automatically. */
const WCAG_AA = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/** No WCAG A or AA violation axe can find in the page as it is now, with every result kept for the report. */
async function scan(page: Page, size: Size, name: string): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(WCAG_AA).analyze();
  mkdirSync('test-results/accessibility', { recursive: true });
  writeFileSync(`test-results/accessibility/${size.name}-${name}.json`, JSON.stringify(results, null, 2));
  const found = results.violations.map(
    (violation) =>
      `${violation.id} (${violation.impact ?? 'unknown'}): ${violation.help}\n` +
      violation.nodes.map((node) => `    ${node.target.join(' ')}: ${node.failureSummary ?? ''}`).join('\n'),
  );
  expect(found, `${name} at ${size.name} has accessibility violations`).toEqual([]);
}

/** The only keys the app may keep in browser storage: display preferences, never credentials or account data. */
const PREFERENCE_KEYS = ['mse.theme'];

/** Browser storage holds display preferences and nothing else, in every state of every screen. */
async function storesOnlyPreferences(page: Page, size: Size, name: string): Promise<void> {
  const stored = await page.evaluate(async () => ({
    local: Object.keys(window.localStorage),
    session: Object.keys(window.sessionStorage),
    databases: (await window.indexedDB.databases()).map((database) => database.name ?? ''),
  }));
  expect(stored.local.filter((key) => !PREFERENCE_KEYS.includes(key)), `${name} at ${size.name}: localStorage`).toEqual([]);
  expect(stored.session, `${name} at ${size.name}: sessionStorage`).toEqual([]);
  expect(stored.databases, `${name} at ${size.name}: IndexedDB`).toEqual([]);
}

/** Forces a theme the way the theme switch does, or lets the page follow the system again. */
async function force(page: Page, theme: 'light' | 'dark' | null): Promise<void> {
  await page.evaluate((chosen) => {
    if (chosen === null) {
      delete document.documentElement.dataset.theme;
    } else {
      document.documentElement.dataset.theme = chosen;
    }
  }, theme);
}

/**
 * No sideways scrolling, nothing stored but preferences, and no accessibility violation in either forced theme, each
 * forced against the other system scheme so the forced one has to win. Screenshots of the light page and the dark one
 * go to test-results/screens/ for review.
 */
async function capture(page: Page, size: Size, name: string): Promise<void> {
  expect(blocked.get(page) ?? [], `${name} at ${size.name}: the content security policy blocked something`).toEqual([]);
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width, `${name} at ${size.name} scrolls sideways`).toBeLessThanOrEqual(size.width);
  await storesOnlyPreferences(page, size, name);
  await page.screenshot({ path: `test-results/screens/${size.name}-${name}.png`, fullPage: true });
  await page.emulateMedia({ colorScheme: 'dark' });
  await force(page, 'light');
  await scan(page, size, name);
  await page.emulateMedia({ colorScheme: 'light' });
  await force(page, 'dark');
  await page.screenshot({ path: `test-results/screens/${size.name}-${name}-dark.png`, fullPage: true });
  await scan(page, size, `${name}-dark`);
  await force(page, null);
}

/** What the proxy's content security policy blocked on each page; the app should need nothing it forbids. */
const blocked = new WeakMap<Page, string[]>();

/**
 * A fresh browser at one size, signed in. Routes go in before signing in: signing in lands on the terminal, whose
 * live stream and market reads start at once.
 */
async function at(
  browser: Browser,
  size: Size,
  user: 'trader' | 'operator' | null,
  routes: (page: Page) => Promise<void> = async () => undefined,
): Promise<Page> {
  const context = await browser.newContext({ viewport: { width: size.width, height: size.height } });
  const page = await context.newPage();
  const violations: string[] = [];
  blocked.set(page, violations);
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy')) {
      violations.push(message.text());
    }
  });
  await routes(page);
  if (user !== null) {
    await signIn(page, user);
  }
  return page;
}

const problem = (status: number, code: string) => (route: Route) =>
  route.fulfill({
    status,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status, code, detail: code }),
  });

async function openTerminal(page: Page): Promise<void> {
  await page.getByRole('link', { name: 'Terminal' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'NOVA' })).toBeVisible();
}

/** Tries a small buy on the open ticket; the answer is whatever the routes in place give. */
async function buy(page: Page, shares: string, price: string): Promise<void> {
  const ticket = page.getByRole('region', { name: 'Order · NOVA' });
  await ticket.getByLabel('Shares').fill(shares);
  await ticket.getByLabel('Limit price').fill(price);
  await ticket.getByRole('button', { name: 'Buy NOVA' }).click();
}

for (const size of SIZES) {
  test.describe(`screens at ${size.name} (${size.width}x${size.height})`, () => {
    test('sign-in, and a refused sign-in', async ({ browser }) => {
      const page = await at(browser, size, null);
      await page.goto('/');
      await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
      await capture(page, size, 'sign-in');
      await page.getByLabel('Username').fill('trader');
      await page.getByLabel('Password').fill('not-the-password');
      await page.getByRole('button', { name: 'Sign in' }).click();
      await expect(page.getByRole('alert')).toBeVisible();
      await capture(page, size, 'sign-in-refused');
      await page.context().close();
    });

    test('the terminal, live, and every ticket outcome', async ({ browser }) => {
      const page = await at(browser, size, 'trader');
      await openTerminal(page);
      await expect(page.getByRole('status').filter({ hasText: 'Connected' })).toBeVisible();
      // Both sides of the book fit inside its panel: a table that only fits by scrolling within the panel hides the
      // asks, while the page itself still looks fine.
      const book = await page.getByRole('region', { name: 'Order book' }).boundingBox();
      const asks = await page.getByRole('table', { name: 'Asks' }).boundingBox();
      expect(book !== null && asks !== null, 'the order book and its asks').toBe(true);
      expect(asks!.x + asks!.width, `asks at ${size.name} reach past their panel`).toBeLessThanOrEqual(
        book!.x + book!.width,
      );
      await capture(page, size, 'terminal');

      // A committed rejection: more than the trader's cash can cover.
      await buy(page, '10000', '500.00');
      await expect(page.getByRole('region', { name: 'Order · NOVA' }).getByRole('alert')).toContainText('Rejected');
      await capture(page, size, 'terminal-rejected');

      // Overloaded: the exchange did not take the order, so it can be retried as the same order.
      await page.reload();
      await openTerminal(page);
      await page.route('**/api/v1/orders', (route) =>
        route.request().method() === 'POST' ? problem(503, 'EXCHANGE_BUSY')(route) : route.fallback(),
      );
      await buy(page, '1', '1.00');
      await expect(page.getByRole('button', { name: 'Retry the same order' })).toBeVisible();
      await capture(page, size, 'terminal-busy');

      // Too many orders at once.
      await page.reload();
      await openTerminal(page);
      await page.unroute('**/api/v1/orders');
      await page.route('**/api/v1/orders', (route) =>
        route.request().method() === 'POST' ? problem(429, 'RATE_LIMITED')(route) : route.fallback(),
      );
      await buy(page, '1', '1.00');
      await expect(page.getByText('Too many orders in a moment')).toBeVisible();
      await capture(page, size, 'terminal-rate-limited');

      // No answer, and the key was never committed: the order was not placed, and retrying is safe.
      await page.reload();
      await openTerminal(page);
      await page.unroute('**/api/v1/orders');
      await page.route('**/api/v1/orders', (route) =>
        route.request().method() === 'POST' ? problem(504, 'COMMAND_OUTCOME_UNKNOWN')(route) : route.fallback(),
      );
      await page.route('**/api/v1/commands/*', problem(404, 'COMMAND_NOT_FOUND'));
      await buy(page, '1', '1.00');
      await expect(page.getByText('The order was not placed')).toBeVisible({ timeout: 20_000 });
      await capture(page, size, 'terminal-not-placed');
      await page.context().close();
    });

    test('the terminal when its data is behind or gone', async ({ browser }) => {
      // The public feed says it is stale. The terminal prefers the live stream's own feed state, so the stream is cut
      // too: stale data and a lost connection show together, as they would.
      const page = await at(browser, size, 'trader', async (fresh) => {
        await fresh.route('**/api/v1/market-events*', (route) => route.abort());
        await fresh.route(/\/api\/v1\/instruments$/, async (route) => {
          const response = await route.fetch();
          const listing = (await response.json()) as { feed: { state: string; lag: number } };
          listing.feed = { state: 'STALE', lag: 12 };
          await route.fulfill({ response, json: listing });
        });
      });
      await openTerminal(page);
      await expect(page.getByRole('status').filter({ hasText: 'Market data: Stale' })).toBeVisible();
      // No frame has arrived, so the book and tape say they are not known yet instead of claiming they are empty.
      await expect(page.getByText('Waiting for the live market…')).toHaveCount(2);
      await capture(page, size, 'terminal-feed-stale');
      await page.context().close();

      // The live stream can't connect.
      const offline = await at(browser, size, 'trader', async (fresh) => {
        await fresh.route('**/api/v1/market-events*', (route) => route.abort());
      });
      await openTerminal(offline);
      await expect(offline.getByRole('status').filter({ hasText: 'Connecting' })).toBeVisible();
      await expect(offline.getByText('Waiting for the live market…')).toHaveCount(2);
      await capture(offline, size, 'terminal-stream-lost');
      await offline.context().close();

      // Market Data is down.
      const down = await at(browser, size, 'trader', async (fresh) => {
        await fresh.route(/\/api\/v1\/instruments$/, problem(503, 'UNAVAILABLE'));
      });
      await down.getByRole('link', { name: 'Terminal' }).click();
      await expect(down.getByRole('alert')).toContainText('Market data is not reachable');
      await capture(down, size, 'terminal-market-data-down');
      await down.context().close();
    });

    test('the price chart, its controls, and its data behind or gone', async ({ browser }) => {
      const chart = (page: Page) => page.getByRole('region', { name: 'Price', exact: true });
      const choose = (page: Page, group: string, name: string) =>
        chart(page).getByRole('group', { name: group }).getByRole('button', { name, exact: true }).click();

      // Live: bars from the analytics, topped up from the tape, with the newest bar in text.
      const page = await at(browser, size, 'trader');
      await openTerminal(page);
      await expect(chart(page).getByText(/^C \d/)).toBeVisible({ timeout: 30_000 });
      await choose(page, 'Chart type', 'Area');
      await choose(page, 'Bar size', '5 min');
      await choose(page, 'Range', '30 min');
      for (const [group, name] of [
        ['Chart type', 'Area'],
        ['Bar size', '5 min'],
        ['Range', '30 min'],
      ] as const) {
        await expect(chart(page).getByRole('group', { name: group }).getByRole('button', { name, exact: true })).toHaveAttribute(
          'aria-pressed',
          'true',
        );
      }
      await expect(chart(page).getByRole('button', { name: '1 hour', exact: true })).toBeDisabled();
      await expect(chart(page).getByText('5 min bars · last 30 min')).toBeVisible();
      // The settings are in the address, so a reload keeps them.
      await page.reload();
      await expect(chart(page).getByText('5 min bars · last 30 min')).toBeVisible();
      await capture(page, size, 'terminal-chart-area');
      await page.context().close();

      // The analytics are further behind than the tape reaches back: the bars stay, and say so.
      const behind = await at(browser, size, 'trader', async (fresh) => {
        await fresh.route('**/api/v1/instruments/*/candles*', async (route) => {
          const response = await route.fetch();
          const body = (await response.json()) as {
            freshness: { candlesThroughTradeSequence: string | null; latestTradeSequence: string; tradesBehind: number };
          };
          // Far past anything on the tape, so the tape cannot cover the difference.
          body.freshness = {
            ...body.freshness,
            candlesThroughTradeSequence: '100000',
            latestTradeSequence: '200000',
            tradesBehind: 100_000,
          };
          await route.fulfill({ response, json: body });
        });
      });
      await openTerminal(behind);
      await expect(chart(behind).getByRole('status')).toHaveText('Analytics behind by 100,000 trades');
      await capture(behind, size, 'terminal-chart-behind');
      await behind.context().close();

      // The bars cannot be read: the tape is drawn as a line instead.
      const unavailable = await at(browser, size, 'trader', async (fresh) => {
        await fresh.route('**/api/v1/instruments/*/candles*', problem(503, 'UNAVAILABLE'));
      });
      await openTerminal(unavailable);
      await expect(chart(unavailable).getByRole('status')).toContainText('Bars unavailable', { timeout: 30_000 });
      await capture(unavailable, size, 'terminal-chart-unavailable');
      await unavailable.context().close();

      // Nothing has traded in the range, and there is no tape to fall back on.
      const empty = await at(browser, size, 'trader', async (fresh) => {
        await fresh.route('**/api/v1/market-events*', (route) => route.abort());
        await fresh.route('**/api/v1/instruments/*/candles*', async (route) => {
          const response = await route.fetch();
          const body = (await response.json()) as { candles: unknown[]; freshness: Record<string, unknown> };
          body.candles = [];
          body.freshness = { ...body.freshness, candlesThroughTradeSequence: null, latestTradeSequence: '0', tradesBehind: 0 };
          await route.fulfill({ response, json: body });
        });
      });
      await openTerminal(empty);
      await expect(chart(empty).getByText('No trades in this range yet.')).toBeVisible();
      await capture(empty, size, 'terminal-chart-empty');
      await empty.context().close();
    });

    test('the portfolio, empty, and unreachable', async ({ browser }) => {
      const page = await at(browser, size, 'trader');
      await page.getByRole('link', { name: 'Portfolio' }).click();
      await expect(page.getByRole('table', { name: 'Holdings' })).toBeVisible();
      // At least two figures a row at every size, so the summary isn't a column of cards filling the first screen.
      const summary = page.getByRole('region', { name: 'Summary' });
      const equity = await summary.getByText('Estimated equity').boundingBox();
      const cash = await summary.getByText('Cash', { exact: true }).boundingBox();
      expect(cash?.y, `summary at ${size.name}: cash on its own row`).toBe(equity?.y);
      await capture(page, size, 'portfolio');
      await page.context().close();

      const empty = await at(browser, size, 'trader');
      await empty.route(/\/api\/v1\/portfolio$/, async (route) => {
        const response = await route.fetch();
        const portfolio = (await response.json()) as { holdings: unknown[] };
        portfolio.holdings = [];
        await route.fulfill({ response, json: portfolio });
      });
      await empty.getByRole('link', { name: 'Portfolio' }).click();
      await expect(empty.getByText('No holdings.')).toBeVisible();
      await capture(empty, size, 'portfolio-no-holdings');
      await empty.context().close();

      const down = await at(browser, size, 'trader');
      await down.route(/\/api\/v1\/portfolio$/, problem(503, 'TRADING_UNAVAILABLE'));
      await down.getByRole('link', { name: 'Portfolio' }).click();
      await expect(down.getByRole('alert')).toContainText('Your portfolio is not reachable');
      await capture(down, size, 'portfolio-unreachable');
      await down.context().close();
    });

    test('a long history, one page at a time', async ({ browser }) => {
      // 300 executions, served a page at a time the way the exchange pages them: limit, cursor and order honoured.
      const history = Array.from({ length: 300 }, (_, index) => ({
        tradeId: `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
        instrumentId: '00000000-0000-4000-8000-000000000000',
        symbol: 'NOVA',
        side: index % 2 === 0 ? 'BUY' : 'SELL',
        liquidity: index % 3 === 0 ? 'MAKER' : 'TAKER',
        price: `${100 + (index % 7)}.00`,
        quantity: 1 + (index % 9),
        notional: `${(100 + (index % 7)) * (1 + (index % 9))}.00`,
        executedAt: new Date(Date.UTC(2026, 10, 10, 9, 0, index)).toISOString(),
        commandSequence: String(index + 1),
      }));
      const asked: URL[] = [];
      const page = await at(browser, size, 'trader', async (fresh) => {
        await fresh.route(/\/api\/v1\/portfolio\/trades/, async (route) => {
          const url = new URL(route.request().url());
          asked.push(url);
          const limit = Number(url.searchParams.get('limit') ?? '50');
          const start = Number(url.searchParams.get('cursor') ?? '0');
          const side = url.searchParams.get('side');
          const ordered = (url.searchParams.get('order') === 'oldest' ? history : [...history].reverse()).filter(
            (trade) => side === null || trade.side === side,
          );
          const trades = ordered.slice(start, start + limit);
          await route.fulfill({
            json: { trades, nextCursor: start + limit < ordered.length ? String(start + limit) : null },
          });
        });
      });
      await page.getByRole('link', { name: 'Portfolio' }).click();
      const panel = page.getByRole('region', { name: 'Executions' });
      const rows = panel.getByRole('table', { name: 'Executions' }).locator('tbody tr');
      await expect(rows).toHaveCount(25);
      await expect(panel.getByText('Showing 1–25')).toBeVisible();

      for (let next = 0; next < 3; next++) {
        await panel.getByRole('button', { name: 'Next' }).click();
      }
      await expect(panel.getByText('Showing 76–100')).toBeVisible();
      // Only the page on screen is in the page, and only it is asked for again by the five-second refresh.
      await expect(rows).toHaveCount(25);
      const before = asked.length;
      await expect.poll(() => asked.length, { timeout: 12_000 }).toBeGreaterThan(before);
      expect(asked.slice(before).map((url) => url.searchParams.get('cursor'))).toEqual(
        asked.slice(before).map(() => '75'),
      );

      await panel.getByLabel('Rows per page').selectOption('100');
      await expect(rows).toHaveCount(100);
      await expect(panel.getByText('Showing 1–100')).toBeVisible();
      await panel.getByLabel('Order').selectOption('oldest');
      await expect(rows.first()).toContainText('09:00:00 UTC');
      expect(new URL(page.url()).search).toContain('executionsSize=100');
      await capture(page, size, 'portfolio-paged');

      // Filtered: only sells, from the first page, with the filter as a chip to take off.
      await panel.getByLabel('Side').selectOption('SELL');
      await expect(panel.getByRole('button', { name: 'Remove filter Side: Sell from Executions' })).toBeVisible();
      await expect(rows).toHaveCount(100);
      await expect(panel.getByText('Showing 1–100')).toBeVisible();
      await expect(rows.filter({ hasText: 'Buy' })).toHaveCount(0);
      expect(asked.at(-1)?.searchParams.get('side')).toBe('SELL');
      await capture(page, size, 'portfolio-filtered');
      await page.context().close();
    });

    test('the operator screen, and the exchange unreachable', async ({ browser }) => {
      const page = await at(browser, size, 'operator');
      await expect(page.getByRole('heading', { name: 'Market' })).toBeVisible();
      await expect(page.getByRole('region', { name: 'Bot runs' })).toBeVisible();
      const live = page.getByRole('region', { name: 'Health' });
      await expect(live.getByText('Taking commands')).toBeVisible();
      // Market data and analytics have answered, through the proxy, before the screen is captured.
      await expect(live.getByText('Checking…')).toHaveCount(0, { timeout: 15_000 });
      await expect(live.getByText('Not reachable')).toHaveCount(0);
      await capture(page, size, 'operator');
      await page.context().close();

      // Trading falling behind: a halted writer, a full queue, refusals and a backlog, each said in words.
      const behind = await at(browser, size, 'operator', async (fresh) => {
        await fresh.route('**/api/v1/admin/health', (route) =>
          route.fulfill({
            json: {
              writer: { available: false, queueDepth: 200, queueCapacity: 256 },
              lastMinute: { committed: 80, failed: 20, withinBudget: 60, refused: 12 },
              outbox: { admitting: false, pendingEvents: 50_000, oldestPendingMillis: 30_000 },
            },
          }),
        );
      });
      const health = behind.getByRole('region', { name: 'Health' });
      await expect(health.getByText('Trading stopped until Execution restarts')).toBeVisible();
      await expect(health.getByText('Publication backlog is full')).toBeVisible();
      await capture(behind, size, 'operator-health-behind');
      await behind.context().close();

      // Execution's summary cannot be read.
      const lost = await at(browser, size, 'operator', async (fresh) => {
        await fresh.route('**/api/v1/admin/health', problem(503, 'UNAVAILABLE'));
      });
      await expect(lost.getByRole('region', { name: 'Health' }).getByRole('alert')).toContainText(
        'Trading health is not reachable',
      );
      await capture(lost, size, 'operator-health-down');
      await lost.context().close();

      const down = await at(browser, size, 'operator');
      await down.route(/\/api\/v1\/market$/, problem(503, 'TRADING_UNAVAILABLE'));
      await down.reload();
      await expect(down.getByRole('alert')).toContainText('The exchange is not reachable');
      await capture(down, size, 'operator-unreachable');
      await down.context().close();
    });
  });
}
