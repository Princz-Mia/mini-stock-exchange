import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';

import type { Candle, Candles, TapeTrade } from '../api/types';
import { chartState, createChart, hiddenSeries, liveSeries, resetChart } from '../test/fakeChart';
import { fakeServer } from '../test/fakeServer';
import { PriceChart, readSettings } from './PriceChart';

const NOVA = '00000000-0000-0000-0000-000000000001';
/** 12:00 UTC on the day of the bars, in the chart's seconds. */
const T0 = Date.parse('2026-11-10T12:00:00Z') / 1000;
const ROUTE = `GET /api/v1/instruments/${NOVA}/candles`;

function candle(minute: string, open: string, high: string, low: string, close: string, volume: number, notional: string, sequence: number): Candle {
  return {
    bucketStart: `2026-11-10T12:${minute}:00Z`,
    open,
    high,
    low,
    close,
    volume,
    tradeCount: 1,
    notional,
    vwap: open,
    sourceMaxTradeSequence: String(sequence),
  };
}

const BARS = [
  candle('00', '100.00', '101.00', '99.00', '100.50', 10, '1005.00', 3),
  candle('01', '100.50', '102.00', '100.00', '101.00', 20, '2020.00', 7),
];

function answer(candles: Candle[], through: string | null, latest: string): Candles {
  return {
    instrumentId: NOVA,
    computationEpoch: 1,
    from: '2026-11-10T10:02:00Z',
    to: '2026-11-10T12:02:00Z',
    candles,
    freshness: {
      feed: { state: 'LIVE', lag: 0 },
      candlesThroughTradeSequence: through,
      latestTradeSequence: latest,
      tradesBehind: Number(latest) - Number(through ?? '0'),
    },
  };
}

function trade(sequence: number, price: string, quantity: number, at: string): TapeTrade {
  return {
    tradeId: `t${sequence}`,
    tradeSequence: String(sequence),
    price,
    quantity,
    aggressorSide: 'BUY',
    executedAt: `2026-11-10T12:${at}Z`,
  };
}

/** Where the page's address ends up, to check the settings are kept in it. */
function Address() {
  return <span data-testid="address">{useLocation().search}</span>;
}

const address = () => screen.getByTestId('address').textContent ?? '';

function renderChart(trades: TapeTrade[] | null = [], at = `/terminal?instrument=${NOVA}`) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const ui = (tape: TapeTrade[] | null) => (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[at]}>
        <PriceChart instrumentId={NOVA} trades={tape} />
        <Address />
      </MemoryRouter>
    </QueryClientProvider>
  );
  const rendered = render(ui(trades));
  return { client, frame: (tape: TapeTrade[] | null) => rendered.rerender(ui(tape)) };
}

const values = (kind: string) => liveSeries(kind)?.data.map((point) => (point as { value: number }).value);

beforeEach(() => {
  resetChart();
  createChart.mockClear();
  for (const name of ['--buy', '--sell', '--secondary', '--surface', '--muted', '--line']) {
    document.documentElement.style.removeProperty(name);
  }
  delete document.documentElement.dataset.theme;
});

describe('PriceChart', () => {
  it('draws one-minute candles with volume underneath, and the newest bar in text', async () => {
    const server = fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '7') });
    renderChart();

    await waitFor(() => expect(liveSeries('Candlestick')?.data).toHaveLength(2));
    expect(liveSeries('Candlestick')?.data[0]).toEqual({ time: T0, open: 100, high: 101, low: 99, close: 100.5 });
    const volume = liveSeries('Histogram')!;
    expect(volume.pane).toBe(1);
    expect(volume.data.map((point) => (point as { value: number }).value)).toEqual([10, 20]);
    expect(screen.getByText('C 101.00')).toBeInTheDocument();
    expect(screen.getByText('VWAP 101.00')).toBeInTheDocument();
    expect(screen.getByText('1 min bars · last 2 hours')).toBeInTheDocument();

    // A two-hour window of whole minutes.
    const url = new URL(server.requests.find((request) => request.url.includes('/candles'))!.url, 'http://localhost');
    const from = Date.parse(url.searchParams.get('from')!);
    const to = Date.parse(url.searchParams.get('to')!);
    expect(to - from).toBe(120 * 60_000);
    expect(from % 60_000).toBe(0);
  });

  it('adds live trades the candles do not include yet to the newest bar', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '8') });
    renderChart([trade(8, '103.00', 5, '01:30'), trade(7, '101.00', 1, '01:20')]);

    await waitFor(() => expect(screen.getByText('C 103.00')).toBeInTheDocument());
    expect(liveSeries('Candlestick')?.data[1]).toMatchObject({ high: 103, close: 103 });
    expect(screen.getByText('Vol 25')).toBeInTheDocument();
    expect(screen.queryByText(/Analytics behind/)).not.toBeInTheDocument();
  });

  it('says the analytics are behind when the tape no longer reaches the bars', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '120') });
    renderChart([trade(120, '104.00', 1, '01:59')]);

    expect(await screen.findByRole('status')).toHaveTextContent('Analytics behind by 113 trades');
    expect(liveSeries('Candlestick')?.data).toHaveLength(2);
  });

  it('draws the tape as a line when there are no bars and the analytics are behind', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer([], null, '80') });
    renderChart([trade(80, '104.00', 1, '01:59'), trade(79, '103.50', 1, '01:58')]);

    expect(await screen.findByRole('status')).toHaveTextContent('Analytics behind by 80 trades');
    await waitFor(() => expect(liveSeries('Line')?.data).toHaveLength(2));
    expect(liveSeries('Candlestick')).toBeUndefined();
    // The tape's own volume, per second, stays under the line.
    expect(liveSeries('Histogram')?.data.map((point) => (point as { value: number }).value)).toEqual([1, 1]);
    expect(screen.getByText('Analytics behind by 80 trades · recent trades')).toBeInTheDocument();
  });

  it('draws the tape as a line when the bars cannot be read', async () => {
    fakeServer().on(ROUTE, { status: 503, problem: { status: 503, code: 'UNAVAILABLE' } });
    renderChart([trade(3, '104.00', 1, '01:59')]);

    expect(await screen.findByRole('status')).toHaveTextContent('Bars unavailable');
    await waitFor(() => expect(liveSeries('Line')?.data).toHaveLength(1));
  });

  it('says so when nothing has traded in the range', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer([], null, '0') });
    renderChart();

    expect(await screen.findByText('No trades in this range yet.')).toBeInTheDocument();
  });

  it('switches chart type, range and bar size, and keeps them in the address', async () => {
    const server = fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '7') });
    renderChart();
    await waitFor(() => expect(liveSeries('Candlestick')?.data).toHaveLength(2));

    fireEvent.click(within(screen.getByRole('group', { name: 'Chart type' })).getByRole('button', { name: 'Line' }));
    await waitFor(() => expect(liveSeries('Line')?.data).toEqual([
      { time: T0, value: 100.5 },
      { time: T0 + 60, value: 101 },
    ]));
    expect(liveSeries('Candlestick')).toBeUndefined();

    fireEvent.click(within(screen.getByRole('group', { name: 'Chart type' })).getByRole('button', { name: 'Area' }));
    await waitFor(() => expect(liveSeries('Area')?.data).toHaveLength(2));
    // Price stays in the top pane and volume in its own pane below, whatever the type.
    expect(liveSeries('Area')?.pane).toBe(0);
    expect(liveSeries('Histogram')?.pane).toBe(1);

    fireEvent.click(within(screen.getByRole('group', { name: 'Bar size' })).getByRole('button', { name: '5 min' }));
    await waitFor(() => expect(liveSeries('Area')?.data).toEqual([{ time: T0, value: 101 }]));
    expect(screen.getByText('Vol 30')).toBeInTheDocument();

    const range = screen.getByRole('group', { name: 'Range' });
    fireEvent.click(within(range).getByRole('button', { name: '1 day' }));
    await waitFor(() => {
      const last = new URL(server.requests.at(-1)!.url, 'http://localhost');
      expect(Date.parse(last.searchParams.get('to')!) - Date.parse(last.searchParams.get('from')!)).toBe(1_440 * 60_000);
    });
    expect(address()).toContain('chart=area');
    expect(address()).toContain('range=1d');
    expect(address()).toContain('bars=5m');
    expect(address()).toContain(`instrument=${NOVA}`);
    expect(within(range).getByRole('button', { name: '1 day' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('offers only bar sizes the range can hold two of, and narrows the bar size to fit', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '7') });
    renderChart([], `/terminal?range=2h&bars=1h`);
    const bars = screen.getByRole('group', { name: 'Bar size' });
    expect(within(bars).getByRole('button', { name: '1 hour' })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(within(screen.getByRole('group', { name: 'Range' })).getByRole('button', { name: '30 min' }));
    await waitFor(() => expect(within(bars).getByRole('button', { name: '15 min' })).toHaveAttribute('aria-pressed', 'true'));
    expect(within(bars).getByRole('button', { name: '1 hour' })).toBeDisabled();
    expect(address()).toContain('bars=15m');
  });
});

describe('PriceChart, what it leaves out and keeps', () => {
  it('keeps trades that slid off the tape before the next fetch of bars', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '8') });
    const chart = renderChart([trade(8, '103.00', 5, '01:30')]);
    await waitFor(() => expect(values('Histogram')).toEqual([10, 25]));

    // A burst: trade 8 is no longer on the tape, but the bars still have not caught up with it.
    chart.frame([trade(10, '104.00', 2, '01:40'), trade(9, '103.50', 1, '01:35')]);
    await waitFor(() => expect(values('Histogram')).toEqual([10, 28]));
    expect(screen.queryByText(/Analytics behind/)).not.toBeInTheDocument();
  });

  it('trusts the bars it was sent over a higher figure the server read a moment later', async () => {
    // The server's figure says 9, but the bars it sent only reach 7: trades 8 and 9 are added from the tape.
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '9', '9') });
    renderChart([trade(9, '104.00', 1, '01:50'), trade(8, '103.00', 5, '01:30')]);

    await waitFor(() => expect(values('Histogram')).toEqual([10, 26]));
    expect(screen.getByText('C 104.00')).toBeInTheDocument();
  });

  it('leaves out a bar that starts before the window and so holds only part of its period', async () => {
    const early = [
      candle('00', '100.00', '100.00', '100.00', '100.00', 1, '100.00', 1),
      candle('01', '101.00', '101.00', '101.00', '101.00', 1, '101.00', 2),
    ].map((bar, index) => ({ ...bar, bucketStart: `2026-11-10T11:5${8 + index}:00Z` }));
    const json = { ...answer([...early, ...BARS], '7', '7'), from: '2026-11-10T11:58:00Z' };
    fakeServer().on(ROUTE, { status: 200, json });
    renderChart([], `/terminal?bars=5m`);

    // 11:58 and 11:59 fall in the 11:55 bar, which began before the window; only the 12:00 bar is drawn.
    await waitFor(() =>
      expect(liveSeries('Candlestick')?.data).toEqual([{ time: T0, open: 100, high: 102, low: 99, close: 101 }]),
    );
  });

  it('draws the bars as they are, with no claim about what they miss, before the first market frame', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '120') });
    renderChart(null);

    await waitFor(() => expect(liveSeries('Candlestick')?.data).toHaveLength(2));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('waits for the tape before the first frame unless the bars show nothing has traded', async () => {
    let answered = false;
    fakeServer().on(ROUTE, () => {
      answered = true;
      return { status: 200, json: answer([], '40', '45') };
    });
    renderChart(null);
    await waitFor(() => expect(answered).toBe(true));
    await act(async () => undefined);
    expect(screen.getByText('Loading bars…')).toBeInTheDocument();
    expect(screen.queryByText('No trades in this range yet.')).not.toBeInTheDocument();
  });

  it('says nothing has traded in the range before the first frame when the bars are up to date', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer([], '40', '40') });
    renderChart(null);
    expect(await screen.findByText('No trades in this range yet.')).toBeInTheDocument();
  });

  it('says what is missing rather than that nothing traded, when there are no bars and no tape', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer([], '40', '5040') });
    renderChart([]);

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Analytics behind by 5,000 trades. Nothing to draw until trades or bars arrive.',
    );
    expect(screen.queryByText('No trades in this range yet.')).not.toBeInTheDocument();
  });

  it('says the bars are unavailable even with no tape to draw', async () => {
    fakeServer().on(ROUTE, { status: 503, problem: { status: 503, code: 'UNAVAILABLE' } });
    renderChart([]);

    expect(await screen.findByRole('status')).toHaveTextContent('Bars unavailable. Nothing to draw');
  });

  it('keeps the last bars when a later fetch fails', async () => {
    let calls = 0;
    fakeServer().on(ROUTE, () =>
      ++calls === 1
        ? { status: 200, json: answer(BARS, '7', '7') }
        : { status: 503, problem: { status: 503, code: 'UNAVAILABLE' } },
    );
    const chart = renderChart([trade(7, '101.00', 1, '01:20')]);
    await waitFor(() => expect(liveSeries('Candlestick')?.data).toHaveLength(2));

    await act(() => chart.client.refetchQueries());
    expect(calls).toBe(2);
    await waitFor(() => expect(chart.client.getQueryState(['candles', NOVA, '2h'])?.status).toBe('error'));
    expect(liveSeries('Candlestick')?.data).toHaveLength(2);
    expect(screen.queryByText(/Bars unavailable/)).not.toBeInTheDocument();
  });

  it('empties the series it hides, so they do not stretch the time axis', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '7') });
    renderChart();
    await waitFor(() => expect(liveSeries('Candlestick')?.data).toHaveLength(2));

    fireEvent.click(within(screen.getByRole('group', { name: 'Chart type' })).getByRole('button', { name: 'Line' }));
    await waitFor(() => expect(liveSeries('Line')?.data).toHaveLength(2));
    expect(hiddenSeries().map((series) => series.data.length)).toEqual([0, 0]);
  });

  it('fits the view to a new range or bar size, but not to each live update', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '8') });
    const chart = renderChart([]);
    await waitFor(() => expect(liveSeries('Candlestick')?.data).toHaveLength(2));
    const fitted = chartState.fits;

    chart.frame([trade(8, '103.00', 5, '01:30')]);
    await waitFor(() => expect(values('Histogram')).toEqual([10, 25]));
    expect(chartState.fits).toBe(fitted);

    fireEvent.click(within(screen.getByRole('group', { name: 'Bar size' })).getByRole('button', { name: '5 min' }));
    await waitFor(() => expect(chartState.fits).toBeGreaterThan(fitted));
  });

  it('shows the bar under the pointer in text, and the newest one otherwise', async () => {
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '7') });
    renderChart();
    await waitFor(() => expect(screen.getByText('C 101.00')).toBeInTheDocument());

    act(() => chartState.hover?.({ time: T0 }));
    expect(screen.getByText('C 100.50')).toBeInTheDocument();
    act(() => chartState.hover?.({}));
    expect(screen.getByText('C 101.00')).toBeInTheDocument();
  });

  it('colours each volume bar by whether its bar rose or fell', async () => {
    document.documentElement.style.setProperty('--buy', '#00aa00');
    document.documentElement.style.setProperty('--sell', '#cc0000');
    const fell = candle('02', '101.00', '101.00', '99.00', '100.00', 5, '500.00', 8);
    fakeServer().on(ROUTE, { status: 200, json: answer([...BARS, fell], '8', '8') });
    renderChart();

    await waitFor(() => expect(liveSeries('Histogram')?.data).toHaveLength(3));
    expect(liveSeries('Histogram')?.data.map((point) => (point as { color: string }).color)).toEqual([
      '#00aa0080',
      '#00aa0080',
      '#cc000080',
    ]);
  });
});

describe('PriceChart colours', () => {
  const paint = (colours: Record<string, string>) => {
    for (const [name, value] of Object.entries(colours)) {
      document.documentElement.style.setProperty(name, value);
    }
  };

  it('takes the new scheme colours when the theme changes, without creating the chart again', async () => {
    paint({ '--buy': '#00aa00', '--sell': '#cc0000', '--secondary': '#5b3fa0', '--surface': '#ffffff', '--muted': '#615c6e', '--line': '#dedbe5' });
    fakeServer().on(ROUTE, { status: 200, json: answer(BARS, '7', '7') });
    renderChart();
    await waitFor(() => expect(liveSeries('Histogram')?.data).toHaveLength(2));
    expect(liveSeries('Candlestick')?.options).toMatchObject({ upColor: '#00aa00', downColor: '#cc0000' });

    paint({ '--buy': '#4cc27b', '--sell': '#f0646d', '--secondary': '#9b82e0', '--surface': '#1b1822', '--muted': '#a39fb0', '--line': '#322e3d' });
    await act(async () => {
      document.documentElement.dataset.theme = 'dark';
    });

    await waitFor(() => expect(liveSeries('Candlestick')?.options).toMatchObject({ upColor: '#4cc27b', wickDownColor: '#f0646d' }));
    const layouts = chartState.options.filter((options) => 'layout' in (options as object));
    expect(layouts.at(-1)).toEqual({
      layout: { background: { color: '#1b1822' }, textColor: '#a39fb0' },
      grid: { vertLines: { color: '#322e3d' }, horzLines: { color: '#322e3d' } },
    });
    expect(liveSeries('Candlestick')?.options).toMatchObject({
      upColor: '#4cc27b',
      downColor: '#f0646d',
      borderUpColor: '#4cc27b',
      borderDownColor: '#f0646d',
      wickUpColor: '#4cc27b',
      wickDownColor: '#f0646d',
    });
    expect(hiddenSeries().find((series) => series.kind === 'Line')?.options).toMatchObject({ color: '#9b82e0' });
    expect(hiddenSeries().find((series) => series.kind === 'Area')?.options).toMatchObject({
      lineColor: '#9b82e0',
      topColor: '#9b82e066',
      bottomColor: '#9b82e00a',
    });
    // The volume bars are coloured in their data, so they are drawn again in the new colours.
    expect(liveSeries('Histogram')?.data.map((point) => (point as { color: string }).color)).toEqual([
      '#4cc27b80',
      '#4cc27b80',
    ]);
    expect(createChart).toHaveBeenCalledTimes(1);
  });
});

describe('readSettings', () => {
  it('ignores anything it does not know', () => {
    expect(readSettings(new URLSearchParams('chart=pie&range=1y&bars=3m'))).toEqual({
      type: 'candle',
      range: '2h',
      bars: '1m',
    });
  });

  it('narrows a bar size the range cannot hold two of', () => {
    expect(readSettings(new URLSearchParams('range=30m&bars=1h')).bars).toBe('15m');
  });
});
