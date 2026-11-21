import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { App } from '../App';
import { resetSession } from '../api/client';
import type { BotRun, BotRunDetail } from '../api/types';
import { botRun, finishedRun, manifest, runDetail, runFigures } from '../test/botRun';
import { signedIn } from '../test/fakeServer';

const FIRST = '20000000-0000-0000-0000-000000000011';
const SECOND = '20000000-0000-0000-0000-000000000012';
const THIRD = '20000000-0000-0000-0000-000000000013';
const MISSING = '20000000-0000-0000-0000-000000000019';

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
      window: { ...finishedRun().window, endedAt: '2026-11-06T11:05:00Z' },
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
    expect(row(markets, 'Price at the start')).toEqual(['100.00 (reference)', '100.00 (reference)']);
    expect(row(markets, 'Price at the end')).toEqual(['101.25 (last trade)', '101.25 (last trade)']);

    const strategies = screen.getByRole('table', { name: 'Strategies' });
    // The faster run had no market makers: its column says so rather than showing zeros.
    expect(row(strategies, 'Bots')).toEqual(['1', 'No bots', '2', '2']);
    expect(row(strategies, 'Fill ratio')).toEqual(['62.5%', 'No bots', '—', '—']);
    expect(row(strategies, 'Refused')).toEqual(['0', 'No bots', '3 (INSUFFICIENT_CASH 2, MARKET_NOT_OPEN 1)', '3 (INSUFFICIENT_CASH 2, MARKET_NOT_OPEN 1)']);
    expect(row(strategies, 'Estimated profit or loss')).toEqual(['100.50', 'No bots', '-0.75', '-0.75']);

    expect(screen.getByRole('link', { name: 'Back to bot runs' })).toHaveAttribute(
      'href',
      `/operator?compare=${FIRST},${SECOND}`,
    );
  });

  it("shows each run's simulator side by side, and marks the settings that differ", async () => {
    const tuned = run(SECOND, {
      manifest: manifest({
        defaults: { ...manifest().defaults, maxInFlight: 64 },
        strategies: { ...manifest().strategies, MARKET_MAKER: { ...manifest().strategies.MARKET_MAKER!, halfSpreadTicks: 3 } },
      }),
    });
    server({
      [FIRST]: runDetail(run(FIRST)),
      [SECOND]: runDetail(tuned),
      [THIRD]: runDetail(run(THIRD, { manifest: null })),
      [MISSING]: 404,
    });

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND},${THIRD},${MISSING}`);

    const simulator = await screen.findByRole('table', { name: 'Simulator' });
    // A run that reported nothing says so; a run that isn't there has nothing to say.
    await waitFor(() => expect(row(simulator, 'Defaults: maxInFlight')).toEqual(['32', '64', 'Not reported', '—']));
    expect(row(simulator, 'Market makers: halfSpreadTicks')).toEqual(['2', '3', 'Not reported', '—']);
    expect(within(simulator).getByRole('rowheader', { name: 'Defaults: maxInFlight Differs' })).toBeInTheDocument();
    // Tinted as well as said.
    expect(within(simulator).getByRole('row', { name: /^Defaults: maxInFlight/ })).toHaveClass('differs');
    expect(within(simulator).getByRole('row', { name: /^Noise: spreadTicks/ })).not.toHaveClass('differs');
    // Each strategy's settings under its own name, to the eye and in each row's name, so the two quote lifetimes are
    // told apart.
    expect(within(simulator).getByRole('rowheader', { name: 'Market makers' })).toBeInTheDocument();
    expect(row(simulator, 'Market makers: quoteLifetimeSeconds')).toEqual(['30', '30', 'Not reported', '—']);
    expect(row(simulator, 'Noise: quoteLifetimeSeconds')).toEqual(['10', '10', 'Not reported', '—']);
    expect(screen.getByText(/2 settings differ, marked Differs/)).toBeInTheDocument();
    expect(screen.getByText(/simulators differ in 2 settings, marked under Simulator/)).toBeInTheDocument();
  });

  it('claims no likeness from one run that reported, and nothing before the runs are in', async () => {
    server({ [FIRST]: runDetail(run(FIRST)), [THIRD]: runDetail(run(THIRD, { manifest: null })) });

    openAt(`/operator/runs/compare?runs=${FIRST},${THIRD}`);

    const panel = await screen.findByRole('region', { name: 'Simulator' });
    expect(within(panel).queryByText(/No run reported|Every setting is the same/)).toBeNull();
    expect(
      await within(panel).findByText(/Only one run reported its simulator, so there is nothing to compare it with\./),
    ).toBeInTheDocument();
    expect(within(panel).queryByText('Differs')).toBeNull();
  });

  it('keeps a column for each run it cannot compare, and says why', async () => {
    const running = botRun({ runId: SECOND });
    const legacy = run(THIRD, {
      window: { startSequence: '5', stopSequence: null, endSequence: null, endedBy: 'NOT_KEPT', endedAt: null },
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

  it('says when a run had another instrument, or could not be loaded', async () => {
    const orbt = { ...runFigures().instruments[0]!, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' };
    server({
      [FIRST]: runDetail(run(FIRST)),
      [SECOND]: runDetail(run(SECOND), runFigures({ instruments: [orbt] })),
      [THIRD]: 503,
    });

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND},${THIRD}`);

    const markets = await screen.findByRole('table', { name: 'Markets' });
    await within(markets).findByRole('rowheader', { name: 'ORBT' });
    expect(row(markets, 'Trades')).toEqual(['320', 'Not in this run', '—', 'Not in this run', '320', '—']);
    const settings = screen.getByRole('table', { name: 'Settings' });
    await within(settings).findAllByText('Could not be loaded', undefined, { timeout: 5_000 });
    expect(row(settings, 'Comparable')[2]).toBe('Could not be loaded');
  });

  it('stops asking once a run is finished with figures, unknown or not comparable, and follows one still going', async () => {
    let finished = false;
    const legacy = run(SECOND, { window: { startSequence: '5', stopSequence: null, endSequence: null, endedBy: 'NOT_KEPT', endedAt: null } });
    const missing = '20000000-0000-0000-0000-000000000099';
    const fake = server({ [FIRST]: runDetail(run(FIRST)), [SECOND]: runDetail(legacy, null), [missing]: 404 });
    fake.on(`GET /api/v1/admin/bot-runs/${THIRD}`, () => ({
      status: 200,
      json: finished ? runDetail(run(THIRD)) : runDetail(botRun({ runId: THIRD }), null),
    }));
    const reads = (id: string) => fake.requests.filter((request) => request.url.endsWith(`/bot-runs/${id}`)).length;

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND},${THIRD},${missing}`);

    const settings = await screen.findByRole('table', { name: 'Settings' });
    await within(settings).findByText('Not finished yet: Running');
    finished = true;
    await waitFor(() => expect(row(settings, 'Comparable')[2]).toBe('Finished'), { timeout: 8_000 });
    // Long past another round of asking: the settled ones were read once.
    await new Promise((resolve) => setTimeout(resolve, 5_500));
    expect([reads(FIRST), reads(SECOND), reads(missing)]).toEqual([1, 1, 1]);
    const third = reads(THIRD);
    await new Promise((resolve) => setTimeout(resolve, 5_500));
    expect(reads(THIRD)).toBe(third);
  }, 25_000);

  it('goes back to the operator screen as it was left, ticking only runs the server knows', async () => {
    const missing = '20000000-0000-0000-0000-000000000099';
    server({ [FIRST]: runDetail(run(FIRST)), [SECOND]: runDetail(run(SECOND)), [missing]: 404 });

    window.history.pushState({ usr: { from: '?runsSize=50&runsOrder=oldest&compare=x' }, key: 'k', idx: 0 }, '',
      `/operator/runs/compare?runs=${FIRST},${SECOND},${missing}`);
    render(<App />);

    const settings = await screen.findByRole('table', { name: 'Settings' });
    await within(settings).findAllByText('No such run');
    expect(screen.getByRole('link', { name: 'Back to bot runs' })).toHaveAttribute(
      'href',
      `/operator?runsSize=50&runsOrder=oldest&compare=${FIRST},${SECOND}`,
    );
  });

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

describe('the market over each run', () => {
  beforeEach(() => resetSession());

  const NOVA = '00000000-0000-0000-0000-000000000001';
  const ORBT = '00000000-0000-0000-0000-000000000002';

  /** One minute after another from the hour's start: best bid, best ask, last price. */
  function minutes(hour: string, rows: readonly [bid: string | null, ask: string | null, last: string | null][]) {
    return rows.map(([bestBid, bestAsk, lastPrice], index) => ({
      minute: `2026-11-06T${hour}:0${index}:00Z`,
      bestBid,
      bestAsk,
      spread: bestBid !== null && bestAsk !== null ? (Number(bestAsk) - Number(bestBid)).toFixed(2) : null,
      lastPrice,
      changed: true,
    }));
  }

  function quality(fromSequence: string, rows: ReturnType<typeof minutes>, overrides: object = {}) {
    return {
      instrumentId: NOVA,
      fromSequence,
      toSequence: '9999',
      minutes: rows,
      averageSpread: '0.0350',
      twoSidedMinutes: 2,
      volatility: '0.008165',
      returns: 2,
      feed: { state: 'LIVE', lag: 0 },
      ...overrides,
    };
  }

  const both = {
    ...finishedRun().config,
    instruments: [
      { instrumentId: NOVA, symbol: 'NOVA' },
      { instrumentId: ORBT, symbol: 'ORBT' },
    ],
  };
  // The first ran into its fourth minute, though its book on NOVA last changed in its third.
  const first = run(FIRST, {
    startedAt: '2026-11-06T10:00:30Z',
    config: both,
    window: { ...finishedRun().window, startSequence: '100', endSequence: '199', endedAt: '2026-11-06T10:03:20Z' },
  });
  const second = run(SECOND, {
    startedAt: '2026-11-06T11:00:15Z',
    window: { ...finishedRun().window, startSequence: '200', endSequence: '299', endedAt: '2026-11-06T11:01:40Z' },
  });

  it('charts each run from its start on one scale, with the same minutes as a table', async () => {
    const fake = server({ [FIRST]: runDetail(first), [SECOND]: runDetail(second) });
    fake.on(`GET /api/v1/instruments/${NOVA}/market-quality`, (request) => ({
      status: 200,
      json: request.url.includes('fromSequence=100')
        ? quality(
            '100',
            minutes('10', [
              ['99.98', '100.02', '100.00'],
              ['99.99', null, '100.50'],
              ['100.00', '100.03', '101.00'],
            ]),
          )
        : quality(
            '200',
            minutes('11', [
              ['100.00', '100.01', null],
              ['100.01', '100.02', '100.02'],
            ]),
            { averageSpread: '0.0100', volatility: null, returns: 0 },
          ),
    }));

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND}`);

    const market = await screen.findByRole('region', { name: 'The market over each run' });
    const summary = await within(market).findByRole('table', { name: 'Market quality' });
    await waitFor(() => expect(row(summary, 'Average spread')).toEqual(['0.0350', '0.0100']));
    expect(row(summary, 'Volatility')).toEqual(['0.8165%', 'Too few price changes']);
    // NOVA, the instrument both traded, is chosen; only the first traded ORBT.
    expect(within(market).getByRole('button', { name: 'NOVA' })).toHaveAttribute('aria-pressed', 'true');
    const asked = fake.requests.filter((request) => request.url.includes('/market-quality')).map((request) => request.url);
    expect(asked).toContain(`/api/v1/instruments/${NOVA}/market-quality?fromSequence=100&toSequence=199`);
    expect(asked).toContain(`/api/v1/instruments/${NOVA}/market-quality?fromSequence=200&toSequence=299`);

    const charts = within(market).getAllByRole('figure');
    expect(charts[0]).toHaveTextContent('Run 10.03 to 0.04');
    expect(charts[1]).toHaveTextContent('Run 20.01 to 0.01');
    // The one-sided minute breaks the first run's spread line in two.
    expect(charts[0]!.querySelector('path')?.getAttribute('d')).toMatch(/^M .* M /);
    // One scale for the row: the second run's 0.01 is the row's lowest spread, so its line runs along the bottom.
    // One scale across too: minutes 0 to 2 over the width, so its two minutes take the left half.
    expect(charts[1]!.querySelector('path')?.getAttribute('d')).toBe('M 0.0 72.0 L 120.0 72.0');
    expect(charts[2]).toHaveTextContent('Run 1100.00 to 101.00');

    fireEvent.click(within(market).getByText('Spread by minute, as a table'));
    const table = await within(market).findByRole('table', { name: 'Spread' });
    expect(row(table, '\\+0 min')).toEqual(['0.04', '0.01']);
    expect(row(table, '\\+1 min')).toEqual(['—', '0.01']);
    expect(row(table, '\\+2 min')).toEqual(['0.03', 'Ended']);
    fireEvent.click(within(market).getByText('Last price by minute, as a table'));
    const prices = await within(market).findByRole('table', { name: 'Last price' });
    expect(row(prices, '\\+0 min')).toEqual(['100.00', '—']);
    expect(row(prices, '\\+1 min')).toEqual(['100.50', '100.02']);
  });

  it('starts on the instrument every run traded, switches, and says which runs did not trade one', async () => {
    const orbtOnly = { ...second, config: { ...second.config, instruments: [{ instrumentId: ORBT, symbol: 'ORBT' }] } };
    const fake = server({ [FIRST]: runDetail(first), [SECOND]: runDetail(orbtOnly) });
    fake
      .on(`GET /api/v1/instruments/${NOVA}/market-quality`, {
        status: 200,
        json: quality('100', minutes('10', [['99.98', '100.02', '100.00']])),
      })
      .on(`GET /api/v1/instruments/${ORBT}/market-quality`, {
        status: 200,
        json: quality('100', minutes('10', [['19.98', '20.02', '20.00']]), { instrumentId: ORBT }),
      });

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND}`);

    const market = await screen.findByRole('region', { name: 'The market over each run' });
    // ORBT, the one both traded, though NOVA comes first.
    expect(await within(market).findByRole('button', { name: 'ORBT' })).toHaveAttribute('aria-pressed', 'true');
    await waitFor(() =>
      expect(fake.requests.some((request) => request.url.includes(`${ORBT}/market-quality?fromSequence=100`))).toBe(true),
    );
    fireEvent.click(within(market).getByRole('button', { name: 'NOVA' }));
    const summary = within(market).getByRole('table', { name: 'Market quality' });
    await waitFor(() => expect(row(summary, 'Average spread')).toEqual(['0.0350', 'Not in this run']));
    expect(within(market).getByRole('button', { name: 'NOVA' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('says a minute is past a run only past its window, not where its data stops', async () => {
    const long = { ...second, window: { ...second.window, endedAt: '2026-11-06T11:03:40Z' } };
    const fake = server({ [FIRST]: runDetail(first), [SECOND]: runDetail(long) });
    fake.on(`GET /api/v1/instruments/${NOVA}/market-quality`, (request) => ({
      status: 200,
      json: request.url.includes('fromSequence=100')
        ? quality(
            '100',
            minutes('10', [
              ['99.98', '100.02', '100.00'],
              ['99.98', '100.02', '100.00'],
              ['99.98', '100.02', '100.00'],
              ['99.98', '100.02', '100.00'],
            ]),
          )
        : quality('200', minutes('11', [['100.00', '100.01', '100.00']])),
    }));

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND}`);

    const market = await screen.findByRole('region', { name: 'The market over each run' });
    await waitFor(() => expect(within(market).getAllByRole('figure')[1]).toHaveTextContent('Run 20.01 to 0.01'));
    fireEvent.click(within(market).getByText('Spread by minute, as a table'));
    const table = await within(market).findByRole('table', { name: 'Spread' });
    // Its book last changed in its first minute, but it ran into its fourth.
    expect(row(table, '\\+2 min')).toEqual(['0.04', '—']);
    expect(row(table, '\\+3 min')).toEqual(['0.04', '—']);
  });

  it('asks only for runs it can chart, keeps the instrument in the address, and says when one is unreachable', async () => {
    const orbtOnly = { ...second, config: { ...second.config, instruments: [{ instrumentId: ORBT, symbol: 'ORBT' }] } };
    const fake = server({
      [FIRST]: runDetail(first),
      [SECOND]: runDetail(orbtOnly),
      [THIRD]: runDetail(botRun({ runId: THIRD }), null),
    });
    fake
      .on(`GET /api/v1/instruments/${NOVA}/market-quality`, {
        status: 503,
        problem: { status: 503, code: 'UNAVAILABLE' },
      })
      .on(`GET /api/v1/instruments/${ORBT}/market-quality`, { status: 200, json: quality('100', minutes('10', [])) });

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND},${THIRD}&instrument=${NOVA}`);

    const market = await screen.findByRole('region', { name: 'The market over each run' });
    expect(await within(market).findByRole('button', { name: 'NOVA' })).toHaveAttribute('aria-pressed', 'true');
    const summary = within(market).getByRole('table', { name: 'Market quality' });
    await waitFor(
      () =>
        expect(row(summary, 'Average spread')).toEqual(['Market data is not reachable', 'Not in this run', 'Not finished']),
      { timeout: 5_000 },
    );
    const asked = fake.requests.filter((request) => request.url.includes('/market-quality'));
    expect(asked.every((request) => request.url.includes(`${NOVA}/market-quality?fromSequence=100&toSequence=199`))).toBe(true);
    fireEvent.click(within(market).getByRole('button', { name: 'ORBT' }));
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('instrument')).toBe(ORBT));
    expect(new URLSearchParams(window.location.search).get('runs')).toBe(`${FIRST},${SECOND},${THIRD}`);
  });

  it('asks again for a window that only just ended, and never for an offline feed', async () => {
    const justEnded = { ...first, window: { ...first.window, endedAt: new Date().toISOString() } };
    let more = false;
    const fake = server({ [FIRST]: runDetail(justEnded), [SECOND]: runDetail(second) });
    fake.on(`GET /api/v1/instruments/${NOVA}/market-quality`, (request) => ({
      status: 200,
      json: request.url.includes('fromSequence=100')
        ? quality('100', minutes('10', [['99.98', '100.02', '100.00']]), more ? { averageSpread: '0.0300' } : {})
        : quality('200', minutes('11', [['100.00', '100.01', '100.00']]), { feed: { state: 'DISABLED', lag: -1 } }),
    }));
    const asked = (from: string) => fake.requests.filter((request) => request.url.includes(`fromSequence=${from}`)).length;

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND}`);

    const market = await screen.findByRole('region', { name: 'The market over each run' });
    expect(await within(market).findByText(/feed is off here/)).toBeInTheDocument();
    more = true;
    const summary = within(market).getByRole('table', { name: 'Market quality' });
    // Its last snapshots may still be on their way, so it is read again though the feed says live.
    await waitFor(() => expect(row(summary, 'Average spread')[0]).toBe('0.0300'), { timeout: 15_000 });
    // An offline feed won't catch up: read once.
    expect(asked('200')).toBe(1);
  }, 25_000);

  it('asks again while market data is behind, until it has caught up', async () => {
    let caughtUp = false;
    const fake = server({ [FIRST]: runDetail(first), [SECOND]: runDetail(second) });
    fake.on(`GET /api/v1/instruments/${NOVA}/market-quality`, (request) => ({
      status: 200,
      json: request.url.includes('fromSequence=100')
        ? caughtUp
          ? quality(
              '100',
              minutes('10', [
                ['99.98', '100.02', '100.00'],
                ['99.99', '100.01', '100.10'],
              ]),
              { averageSpread: '0.0300' },
            )
          : quality('100', minutes('10', [['99.98', '100.02', '100.00']]), { feed: { state: 'CATCHING_UP', lag: 40 } })
        : quality('200', minutes('11', [['100.00', '100.01', '100.00']])),
    }));

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND}`);

    const market = await screen.findByRole('region', { name: 'The market over each run' });
    expect(await within(market).findByText(/Market data is catching up/)).toBeInTheDocument();
    caughtUp = true;
    const summary = within(market).getByRole('table', { name: 'Market quality' });
    await waitFor(() => expect(row(summary, 'Average spread')[0]).toBe('0.0300'), { timeout: 15_000 });
    expect(within(market).queryByText(/Market data is catching up/)).toBeNull();
    // Live now, and the window ended long ago: no more asking.
    const asked = () => fake.requests.filter((request) => request.url.includes('fromSequence=100')).length;
    const settled = asked();
    await new Promise((resolve) => setTimeout(resolve, 11_000));
    expect(asked()).toBe(settled);
  }, 35_000);

  it('says when market data is behind, a window is too long, or a run is not finished', async () => {
    const fake = server({
      [FIRST]: runDetail(first),
      [SECOND]: runDetail(second),
      [THIRD]: runDetail(botRun({ runId: THIRD }), null),
    });
    fake.on(`GET /api/v1/instruments/${NOVA}/market-quality`, (request) =>
      request.url.includes('fromSequence=100')
        ? {
            status: 200,
            json: quality('100', minutes('10', [['99.98', '100.02', '100.00']]), {
              feed: { state: 'CATCHING_UP', lag: 40 },
            }),
          }
        : {
            status: 400,
            problem: {
              status: 400,
              code: 'VALIDATION_FAILED',
              errors: [{ field: 'toSequence', code: 'RANGE_TOO_LARGE', message: 'too long' }],
            },
          },
    );

    openAt(`/operator/runs/compare?runs=${FIRST},${SECOND},${THIRD}`);

    const market = await screen.findByRole('region', { name: 'The market over each run' });
    expect(await within(market).findByText(/Market data is catching up/)).toBeInTheDocument();
    const summary = within(market).getByRole('table', { name: 'Market quality' });
    await waitFor(() =>
      expect(row(summary, 'Average spread')).toEqual([
        '0.0350',
        'Too long to chart: over a day of minutes',
        'Not finished',
      ]),
    );
  });
});
