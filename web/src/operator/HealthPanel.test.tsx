import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetSession } from '../api/client';
import type { Candles, Feed, HealthSummary, Instrument } from '../api/types';
import { fakeServer } from '../test/fakeServer';
import {
  type AnalyticsReading,
  type FeedReading,
  HealthPanel,
  healthFigures,
  worstAnalytics,
} from './HealthPanel';

const HEALTHY: HealthSummary = {
  writer: { available: true, queueDepth: 2, queueCapacity: 256 },
  lastMinute: { committed: 118, failed: 2, withinBudget: 119, refused: 0 },
  outbox: { admitting: true, pendingEvents: 3, oldestPendingMillis: 420 },
};

const LIVE: FeedReading = { kind: 'read', feed: { state: 'LIVE', lag: 0 } };

const UP_TO_DATE: AnalyticsReading = { kind: 'read', symbol: 'NOVA', behind: 0, feed: 'LIVE' };

const behindBy = (behind: number, feed: Feed['state'] = 'LIVE'): AnalyticsReading => ({
  kind: 'read',
  symbol: 'HELI',
  behind,
  feed,
});

function values(summary: HealthSummary, feed: FeedReading, analytics: AnalyticsReading) {
  return Object.fromEntries(healthFigures(summary, feed, analytics).map((figure) => [figure.label, figure.value]));
}

function warnings(summary: HealthSummary, feed: FeedReading, analytics: AnalyticsReading) {
  return Object.fromEntries(
    healthFigures(summary, feed, analytics)
      .filter((figure) => figure.warning !== null)
      .map((figure) => [figure.label, figure.warning]),
  );
}

describe('healthFigures', () => {
  it('reads a healthy exchange plainly, with no warnings', () => {
    expect(values(HEALTHY, LIVE, UP_TO_DATE)).toEqual({
      Writer: 'Taking commands',
      Queue: '2 of 256',
      'Commands per second': '2.00',
      'Within 150 ms': '99.1%',
      Refused: '0',
      'New orders': 'Admitted',
      Outbox: '3 waiting, oldest 0.4 s',
      'Market data': 'Live',
      Analytics: 'Up to date',
    });
    expect(warnings(HEALTHY, LIVE, UP_TO_DATE)).toEqual({});
  });

  it('says in words what needs attention', () => {
    const behind: HealthSummary = {
      writer: { available: false, queueDepth: 129, queueCapacity: 256 },
      lastMinute: { committed: 90, failed: 10, withinBudget: 94, refused: 7 },
      outbox: { admitting: false, pendingEvents: 50_000, oldestPendingMillis: 5_001 },
    };
    const stale: FeedReading = { kind: 'read', feed: { state: 'STALE', lag: 40 } };
    expect(warnings(behind, stale, behindBy(101))).toEqual({
      Writer: 'Trading stopped until Execution restarts',
      Queue: 'More than half full',
      'Within 150 ms': 'Below 95%',
      Refused: 'Commands turned away',
      'New orders': 'Publication backlog is full',
      Outbox: 'Publishing is behind',
      'Market data': 'Not live',
      Analytics: 'Falling behind',
    });
    const read = values(behind, stale, behindBy(101));
    expect(read['Market data']).toBe('Stale · 40 behind');
    expect(read.Analytics).toBe('101 trades behind (HELI)');
    expect(read['Within 150 ms']).toBe('94.0%');
  });

  it('draws its lines exactly at the limits', () => {
    expect(warnings({ ...HEALTHY, writer: { ...HEALTHY.writer, queueDepth: 128 } }, LIVE, UP_TO_DATE)).toEqual({});
    expect(warnings({ ...HEALTHY, lastMinute: { committed: 95, failed: 5, withinBudget: 95, refused: 0 } }, LIVE, UP_TO_DATE)).toEqual(
      {},
    );
    expect(warnings({ ...HEALTHY, outbox: { ...HEALTHY.outbox, oldestPendingMillis: 5_000 } }, LIVE, UP_TO_DATE)).toEqual({});
    expect(warnings(HEALTHY, LIVE, behindBy(100))).toEqual({});
  });

  it('shows no share before any command, and an outbox that does not run here', () => {
    const quiet: HealthSummary = {
      ...HEALTHY,
      lastMinute: { committed: 0, failed: 0, withinBudget: 0, refused: 0 },
      outbox: { admitting: true, pendingEvents: null, oldestPendingMillis: null },
    };
    const read = values(quiet, LIVE, UP_TO_DATE);
    expect(read['Within 150 ms']).toBe('—');
    expect(read['Commands per second']).toBe('0.00');
    expect(read.Outbox).toBe('Not running here');
  });

  it('tells checking apart from not reachable, and only the second is a warning', () => {
    const checking = { kind: 'checking' } as const;
    const unreachable = { kind: 'unreachable' } as const;
    expect(values(HEALTHY, checking, checking)).toMatchObject({ 'Market data': 'Checking…', Analytics: 'Checking…' });
    expect(warnings(HEALTHY, checking, checking)).toEqual({});
    expect(warnings(HEALTHY, unreachable, unreachable)).toEqual({
      'Market data': 'Not reachable',
      Analytics: 'Not reachable',
    });
    expect(warnings(HEALTHY, LIVE, behindBy(3, 'STALE'))).toEqual({ Analytics: 'Not live' });
  });

  it('shows the lag only when the feed is not live, as the feed status does', () => {
    expect(values(HEALTHY, { kind: 'read', feed: { state: 'LIVE', lag: 3 } }, UP_TO_DATE)['Market data']).toBe('Live');
  });
});

describe('worstAnalytics', () => {
  const candles = (behind: number, state: Feed['state'] = 'LIVE') =>
    ({ freshness: { tradesBehind: behind, feed: { state, lag: 0 } } }) as unknown as Candles;

  it('takes the instrument furthest behind, skipping those not read yet and those that failed', () => {
    expect(
      worstAnalytics([
        { symbol: 'NOVA', candles: candles(4), failed: false },
        { symbol: 'HELI', candles: undefined, failed: false },
        { symbol: 'QUAD', candles: candles(900), failed: true },
        { symbol: 'ORBT', candles: candles(9, 'CATCHING_UP'), failed: false },
      ]),
    ).toEqual({ kind: 'read', symbol: 'ORBT', behind: 9, feed: 'CATCHING_UP' });
  });

  it('is still checking until a first answer, and unreachable once every query failed, old answers or not', () => {
    expect(worstAnalytics([{ symbol: 'NOVA', candles: undefined, failed: false }])).toEqual({ kind: 'checking' });
    expect(worstAnalytics([])).toEqual({ kind: 'checking' });
    expect(
      worstAnalytics([
        { symbol: 'NOVA', candles: candles(0), failed: true },
        { symbol: 'ORBT', candles: undefined, failed: true },
      ]),
    ).toEqual({ kind: 'unreachable' });
  });
});

const NOVA = { instrumentId: '00000000-0000-0000-0000-000000000001', symbol: 'NOVA' } as Instrument;
const ORBT = { instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' } as Instrument;

function candlesFor(behind: number): Candles {
  return {
    instrumentId: NOVA.instrumentId,
    computationEpoch: 1,
    from: '2026-11-11T09:00:00Z',
    to: '2026-11-11T09:01:00Z',
    candles: [],
    freshness: {
      feed: { state: 'LIVE', lag: 0 },
      candlesThroughTradeSequence: '1',
      latestTradeSequence: String(1 + behind),
      tradesBehind: behind,
    },
  };
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <HealthPanel instruments={[NOVA, ORBT]} feed={LIVE} />
    </QueryClientProvider>,
  );
  return client;
}

beforeEach(() => resetSession());

describe('HealthPanel', () => {
  it('shows Execution summary and the worst analytics freshness across instruments', async () => {
    const server = fakeServer()
      .on('GET /api/v1/admin/health', { status: 200, json: HEALTHY })
      .on(`GET /api/v1/instruments/${NOVA.instrumentId}/candles`, { status: 200, json: candlesFor(2) })
      .on(`GET /api/v1/instruments/${ORBT.instrumentId}/candles`, { status: 200, json: candlesFor(250) });
    renderPanel();

    const panel = await screen.findByRole('region', { name: 'Health' });
    expect(await within(panel).findByText('250 trades behind (ORBT)')).toBeInTheDocument();
    expect(within(panel).getByText('Falling behind')).toBeInTheDocument();
    expect(within(panel).getByText('Taking commands')).toBeInTheDocument();
    const asked = new URL(server.requests.find((request) => request.url.includes('/candles'))!.url, 'http://localhost');
    expect(Date.parse(asked.searchParams.get('to')!) - Date.parse(asked.searchParams.get('from')!)).toBe(60_000);
  });

  it('stops trusting the analytics once Market Data stops answering', async () => {
    let up = true;
    fakeServer()
      .on('GET /api/v1/admin/health', { status: 200, json: HEALTHY })
      .on(`GET /api/v1/instruments/${NOVA.instrumentId}/candles`, () =>
        up ? { status: 200, json: candlesFor(0) } : { status: 503, problem: { status: 503, code: 'UNAVAILABLE' } },
      )
      .on(`GET /api/v1/instruments/${ORBT.instrumentId}/candles`, () =>
        up ? { status: 200, json: candlesFor(0) } : { status: 503, problem: { status: 503, code: 'UNAVAILABLE' } },
      );
    const client = renderPanel();
    expect(await screen.findByText('Up to date')).toBeInTheDocument();

    up = false;
    await act(() => client.refetchQueries({ queryKey: ['health-candles'] }));

    await waitFor(() => expect(screen.getAllByText('Not reachable')).toHaveLength(2));
    expect(screen.queryByText('Up to date')).not.toBeInTheDocument();
  });

  it('says so when Execution cannot be reached', async () => {
    fakeServer().on('GET /api/v1/admin/health', { status: 503, problem: { status: 503, code: 'UNAVAILABLE' } });
    renderPanel();
    expect(await screen.findByRole('alert')).toHaveTextContent('Trading health is not reachable');
  });

  it('keeps the last reading when a refresh fails, and says so', async () => {
    let calls = 0;
    fakeServer().on('GET /api/v1/admin/health', () =>
      ++calls === 1 ? { status: 200, json: HEALTHY } : { status: 503, problem: { status: 503, code: 'UNAVAILABLE' } },
    );
    const client = renderPanel();
    await screen.findByText('Taking commands');

    await act(() => client.refetchQueries({ queryKey: ['admin-health'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not refresh; showing the last reading.');
    expect(screen.getByText('Taking commands')).toBeInTheDocument();
  });
});
