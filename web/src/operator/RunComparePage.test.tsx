import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { App } from '../App';
import { resetSession } from '../api/client';
import type { BotRun, BotRunDetail } from '../api/types';
import { botRun, finishedRun, runDetail, runFigures } from '../test/botRun';
import { signedIn } from '../test/fakeServer';

const FIRST = '20000000-0000-0000-0000-000000000011';
const SECOND = '20000000-0000-0000-0000-000000000012';
const THIRD = '20000000-0000-0000-0000-000000000013';

/** The operator signed in, with each run's detail at its own address. */
function server(details: Record<string, BotRunDetail | number>) {
  const fake = signedIn('operator', ['OPERATOR']);
  for (const [runId, detail] of Object.entries(details)) {
    fake.on(`GET /api/v1/admin/bot-runs/${runId}`, () =>
      typeof detail === 'number'
        ? { status: detail, problem: { status: detail, code: detail === 404 ? 'RUN_NOT_FOUND' : 'UNAVAILABLE' } }
        : { status: 200, json: detail },
    );
  }
  return fake;
}

function openAt(path: string) {
  window.history.pushState({}, '', path);
  return render(<App />);
}

const run = (runId: string, overrides: Partial<BotRun> = {}) => finishedRun({ runId, ...overrides });

/** The cells of every row of a table with this header, in order: one row, or one per group. */
function row(table: HTMLElement, name: string): string[] {
  return within(table)
    .getAllByRole('row', { name: new RegExp(`^${name}`) })
    .flatMap((each) => [...each.querySelectorAll('td')].map((cell) => cell.textContent ?? ''));
}

describe('comparing bot runs', () => {
  beforeEach(() => resetSession());

  it('puts two finished runs side by side: settings, markets and strategies', async () => {
    const faster = run(SECOND, {
      startedAt: '2026-11-06T11:00:00Z',
      stoppedAt: '2026-11-06T11:05:00Z',
      config: { ...finishedRun().config, commandsPerSecond: '8.00', strategies: { marketMakers: 0, noise: 2, momentum: 0 } },
      manifest: { ...finishedRun().manifest!, version: '0.1.0+9f00c0ffee12.dirty', reports: 2 },
    });
    server({
      [FIRST]: runDetail(run(FIRST)),
      [SECOND]: runDetail(faster, runFigures({ strategies: [runFigures().strategies[1]!] })),
    });

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND}`);

    const settings = await screen.findByRole('table', { name: 'Settings' });
    expect(await within(settings).findByRole('columnheader', { name: /Run 2.*11:00:00 UTC/ })).toBeInTheDocument();
    expect(row(settings, 'Comparable')).toEqual(['Finished', 'Finished']);
    expect(row(settings, 'Market-maker share')).toEqual(['1 of 3 (33%)', '0 of 2 (0%)']);
    expect(row(settings, 'Requested rate')).toEqual(['4.00 a second', '8.00 a second']);
    // 1,800 commands in 600 s; the second's 900 noise commands in 300 s.
    expect(row(settings, 'Achieved rate')).toEqual(['3.00 a second', '3.00 a second']);
    expect(row(settings, 'Ran for')).toEqual(['10 min', '5 min']);
    expect(row(settings, 'Simulator version')).toEqual([
      '0.1.0+27a70d6c1e2f',
      '0.1.0+9f00c0ffee12.dirty · changed during the run',
    ]);
    expect(row(settings, 'Command sequences')).toEqual(['41 to 2104', '41 to 2104']);
    expect(screen.getByText(/asked for different rates \(4\.00, 8\.00 commands a second\)/)).toBeInTheDocument();

    const markets = screen.getByRole('table', { name: 'Markets' });
    expect(within(markets).getByRole('rowheader', { name: 'NOVA' })).toBeInTheDocument();
    expect(row(markets, 'Notional')).toEqual(['125,430.50', '125,430.50']);
    expect(row(markets, 'Price at the end')).toEqual(['101.25 (last trade)', '101.25 (last trade)']);

    const strategies = screen.getByRole('table', { name: 'Strategies' });
    // The faster run had no market makers: its column says so rather than showing zeros.
    expect(row(strategies, 'Bots')).toEqual(['1', 'No bots', '2', '2']);
    expect(row(strategies, 'Fill ratio')).toEqual(['62.5%', 'No bots', '—', '—']);
    expect(row(strategies, 'Refused')).toContain('3 (INSUFFICIENT_CASH 2, MARKET_NOT_OPEN 1)');
    expect(row(strategies, 'Estimated profit or loss')).toEqual(['100.50', 'No bots', '-0.75', '-0.75']);

    expect(screen.getByRole('link', { name: 'Back to bot runs' })).toHaveAttribute(
      'href',
      `/operator?compare=${FIRST},${SECOND}`,
    );
  });

  it('keeps a column for each run it cannot compare, and says why', async () => {
    const running = botRun({ runId: SECOND });
    const legacy = run(THIRD, {
      window: { startSequence: '5', stopSequence: null, endSequence: null, endedBy: 'NOT_KEPT' },
    });
    const missing = '20000000-0000-0000-0000-000000000099';
    server({ [FIRST]: runDetail(run(FIRST), null), [SECOND]: runDetail(running, null), [THIRD]: runDetail(legacy, null), [missing]: 404 });

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND},${THIRD},${missing}`);

    const settings = await screen.findByRole('table', { name: 'Settings' });
    await within(settings).findAllByText('No such run');
    expect(row(settings, 'Comparable')).toEqual([
      'Finished',
      'Not finished yet: Running',
      'Not comparable: recorded before run windows were kept',
      'No such run',
    ]);
    expect(row(settings, 'Achieved rate')[0]).toBe('—');
    const markets = screen.getByRole('table', { name: 'Markets' });
    expect(within(markets).getByText('No figures yet.')).toBeInTheDocument();
  });

  it('says a finished run is being worked out, and shows its figures once they are', async () => {
    let worked = false;
    const fake = server({ [SECOND]: runDetail(run(SECOND)) });
    fake.on(`GET /api/v1/admin/bot-runs/${FIRST}`, () => ({
      status: 200,
      json: runDetail(run(FIRST), worked ? runFigures() : null),
    }));

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND}`);

    const strategies = await screen.findByRole('table', { name: 'Strategies' });
    expect(await within(strategies).findAllByText('Being worked out…')).not.toHaveLength(0);
    expect(within(strategies).getAllByText('62.5%')).toHaveLength(1);
    worked = true;
    await waitFor(() => expect(within(strategies).getAllByText('62.5%')).toHaveLength(2), { timeout: 8_000 });
  }, 12_000);

  it('names what it left out of the address, and asks for two runs when it has fewer', async () => {
    server({ [FIRST]: runDetail(run(FIRST)) });

    openAt(`/operator/runs/compare?runs=${FIRST},not-a-run`);

    expect(await screen.findByText('Not run ids, so left out: not-a-run.')).toBeInTheDocument();
    expect(screen.getByText(/Choose two to four finished runs in/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Recent runs' })).toHaveAttribute('href', '/operator');
  });

  it('compares four at most', async () => {
    const ids = [11, 12, 13, 14, 15].map((n) => `20000000-0000-0000-0000-0000000000${n}`);
    server(Object.fromEntries(ids.map((id) => [id, runDetail(run(id))])));

    openAt(`/operator/runs/compare?runs=${ids.join(',')}`);

    const settings = await screen.findByRole('table', { name: 'Settings' });
    expect(within(settings).getAllByRole('columnheader')).toHaveLength(5);
    expect(screen.getByText('At most four runs are compared; 1 more left out.')).toBeInTheDocument();
  });
});
