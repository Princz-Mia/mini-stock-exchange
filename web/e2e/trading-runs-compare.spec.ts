import { randomUUID } from 'node:crypto';

import { type APIRequestContext, type APIResponse, expect, request, test } from '@playwright/test';

import type { BotRun, BotRunDetail, BotRunList, CsrfToken } from '../src/api/types';

// Runs after trading-flow.spec.ts (files run in name order on one worker), whose last test ran a bot run from the
// operator screen to a confirmed stop: one market maker and one noise bot, seed 11, at 4.00 a second typed in. This
// test runs a second through the operator API at twice the rate, then compares the two on the screen. It leaves no
// run active: the gate's later steps start runs of their own.

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

interface Session {
  api: APIRequestContext;
  command: (path: string, data?: unknown) => Promise<APIResponse>;
}

async function operator(): Promise<Session> {
  const api = await request.newContext({ baseURL: required('E2E_BASE_URL') });
  const before = (await (await api.get('/api/v1/auth/csrf')).json()) as CsrfToken;
  const login = await api.post('/api/v1/auth/login', {
    headers: { [before.headerName]: before.token },
    data: { username: 'operator', password: required('E2E_OPERATOR_PASSWORD') },
  });
  expect(login.status(), 'operator login').toBe(200);
  const csrf = (await (await api.get('/api/v1/auth/csrf')).json()) as CsrfToken;
  const command = (path: string, data?: unknown) =>
    api.post(path, { headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() }, data });
  return { api, command };
}

async function runs(session: Session): Promise<readonly BotRun[]> {
  return ((await (await session.api.get('/api/v1/admin/bot-runs?limit=10')).json()) as BotRunList).runs;
}

test('the operator compares two finished runs side by side', async ({ page }) => {
  test.setTimeout(180_000);
  const session = await operator();
  let runId: string | null = null;
  try {
    // A second run at twice the rate of the first, so the comparison warns that the rates differ.
    const started = await session.command('/api/v1/admin/bot-runs', {
      marketMakers: 1,
      noise: 1,
      seed: 23,
      commandsPerSecond: '8.00',
    });
    expect(started.status(), await started.text()).toBe(201);
    runId = ((await started.json()) as { runId: string }).runId;
    await expect
      .poll(async () => (await runs(session)).find((run) => run.runId === runId)?.activity.placed ?? 0, {
        timeout: 60_000,
      })
      .toBeGreaterThan(0);
    const stopped = await session.command(`/api/v1/admin/bot-runs/${runId}/stop`);
    expect(stopped.status(), await stopped.text()).toBe(200);
    // Finished once its worker confirms the stop; its figures are worked out a few seconds later.
    await expect
      .poll(
        async () => {
          const detail = (await (await session.api.get(`/api/v1/admin/bot-runs/${runId}`)).json()) as BotRunDetail;
          return detail.run.window.endSequence !== null && detail.figures !== null;
        },
        { timeout: 90_000 },
      )
      .toBe(true);

    await page.goto('/');
    await page.getByLabel('Username').fill('operator');
    await page.getByLabel('Password').fill(required('E2E_OPERATOR_PASSWORD'));
    await page.getByRole('button', { name: 'Sign in' }).click();
    const panel = page.getByRole('region', { name: 'Bot runs' });
    const recent = panel.getByRole('table', { name: 'Recent runs' });
    await expect(panel.getByText('Tick two to four finished runs to compare them.')).toBeVisible({ timeout: 30_000 });
    // Newest first: this run, then the one from the trading flow.
    const boxes = recent.getByRole('checkbox');
    await boxes.nth(0).check();
    await boxes.nth(1).check();
    await panel.getByRole('link', { name: 'Compare 2 runs' }).click();

    await expect(page.getByRole('heading', { name: 'Compare bot runs' })).toBeVisible();
    await expect(page.getByText(/asked for different rates \(4\.00, 8\.00 commands a second\)/)).toBeVisible();
    const settings = page.getByRole('table', { name: 'Settings' });
    const comparable = settings.getByRole('row', { name: /^Comparable/ });
    await expect(comparable.getByRole('cell')).toHaveText(['Finished', 'Finished']);
    // Each run keeps its column: this one first, as ticked.
    await expect(settings.getByRole('row', { name: /^Requested rate/ }).getByRole('cell')).toHaveText([
      '8.00 a second',
      '4.00 a second',
    ]);
    await expect(settings.getByRole('row', { name: /^Seed/ }).getByRole('cell')).toHaveText(['23', '11']);
    await expect(settings.getByRole('row', { name: /^Achieved rate/ })).not.toContainText('—');
    await expect(settings.getByRole('row', { name: /^Simulator version/ })).toContainText(/\d+\.\d+\.\d+\+[0-9a-f]{12}/);

    const strategies = page.getByRole('table', { name: 'Strategies' });
    await expect(strategies.getByRole('rowheader', { name: 'Market makers' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Markets' }).getByRole('rowheader', { name: 'NOVA' })).toBeVisible();

    // The market over each run, from Market Data: NOVA, the only instrument, measured over each run's window.
    const market = page.getByRole('region', { name: 'The market over each run' });
    await expect(market.getByRole('button', { name: 'NOVA' })).toHaveAttribute('aria-pressed', 'true');
    // Market Data has read each run's window. A short run may end in the minute it started, and the stop's cancels
    // leave that minute one-sided, so a run may have no spread to average; it still has a count of its minutes.
    const summary = market.getByRole('table', { name: 'Market quality' });
    await expect(summary.getByRole('row', { name: /^Minutes with both sides/ }).getByRole('cell')).toHaveText(
      [/^\d+$/, /^\d+$/],
      { timeout: 30_000 },
    );
    const spread = /^(\d+\.\d+|No two-sided minute)$/;
    await expect(summary.getByRole('row', { name: /^Average spread/ }).getByRole('cell')).toHaveText([spread, spread]);
    await expect(market.getByRole('figure')).toHaveCount(4);
    await market.getByText('Spread by minute, as a table').click();
    await expect(market.getByRole('table', { name: 'Spread' }).getByRole('rowheader', { name: '+0 min' })).toBeVisible();

    // Back on the operator screen, both runs are still ticked.
    await page.getByRole('link', { name: 'Back to bot runs' }).click();
    await expect(boxes.nth(0)).toBeChecked();
    await expect(boxes.nth(1)).toBeChecked();
  } finally {
    // Whatever happened, no run is left active for the steps that come after.
    if (runId !== null) {
      await session.command(`/api/v1/admin/bot-runs/${runId}/stop`);
      await expect
        .poll(async () => (await runs(session))[0]?.status, { timeout: 90_000 })
        .toBe('STOPPED');
    }
    await session.api.dispose();
  }
});
