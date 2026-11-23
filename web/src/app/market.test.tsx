import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetSession } from '../api/client';
import type { MarketStatus } from '../api/types';
import { fakeServer } from '../test/fakeServer';
import { listedSimulation, useInstruments, useRefreshOnMarketChange } from './market';

const FIRST = '10000000-0000-0000-0000-000000000001';
const SECOND = '10000000-0000-0000-0000-000000000002';

function status(simulationId: string | null, state: MarketStatus['state'] = 'OPEN'): MarketStatus {
  return {
    state,
    simulationId,
    openedAt: '2026-11-05T09:30:00Z',
    closedAt: state === 'CLOSED' ? '2026-11-05T16:00:00Z' : null,
    commandSequence: '7',
  };
}

const read = (data: MarketStatus) => ({ data, isPending: false });
const WAITING = { data: undefined, isPending: true };
const FAILED = { data: undefined, isPending: false };

/** Market Data, answering each simulation with an instrument named after it; without one, as the simulation "own". */
function marketData() {
  return fakeServer().on('GET /api/v1/instruments', (request) => {
    const simulationId = new URLSearchParams(request.url.split('?')[1] ?? '').get('simulationId');
    return {
      status: 200,
      json: {
        simulationId,
        instruments: [{ instrumentId: `i-${simulationId ?? 'own'}`, symbol: `OF-${simulationId ?? 'own'}` }],
        feed: { state: 'LIVE', lag: 0 },
      },
    };
  });
}

function withClient(client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

const symbols = (list: { instruments: readonly { symbol: string }[] }) => list.instruments.map((each) => each.symbol);

describe('whose instruments are listed', () => {
  it('is not known until the status is read, then the simulation it names, or none', () => {
    expect(listedSimulation(WAITING)).toBeUndefined();
    expect(listedSimulation(read(status(FIRST)))).toBe(FIRST);
    expect(listedSimulation(read(status(FIRST, 'CLOSED')))).toBe(FIRST);
    expect(listedSimulation(read(status(null, 'NOT_SET_UP')))).toBeNull();
    // A status read once stays the answer through a later failed read.
    expect(listedSimulation({ data: status(FIRST), isPending: false })).toBe(FIRST);
    expect(listedSimulation(FAILED)).toBe('current');
  });
});

describe('the instrument list', () => {
  beforeEach(() => resetSession());

  it('is not asked for before the status is known, then by the simulation the status names', async () => {
    const server = marketData();
    const { wrapper } = withClient();
    const { result, rerender } = renderHook(({ market }) => useInstruments(market), {
      wrapper,
      initialProps: { market: WAITING as Parameters<typeof useInstruments>[0] },
    });

    // Long enough for a request to have gone out, had one been made.
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(server.requests).toHaveLength(0);
    expect(result.current).toEqual({ instruments: [], feed: undefined, isPending: true, isError: false });

    rerender({ market: read(status(FIRST)) });
    await waitFor(() => expect(symbols(result.current)).toEqual([`OF-${FIRST}`]));
    expect(server.requests.map((request) => request.url)).toEqual([`/api/v1/instruments?simulationId=${FIRST}`]);
    expect(result.current.feed).toEqual({ state: 'LIVE', lag: 0 });
    expect(result.current.isPending).toBe(false);
  });

  it('is read again when the simulation changes, with nothing of the one before shown meanwhile', async () => {
    const server = marketData();
    const { wrapper } = withClient();
    const seen: string[][] = [];
    const { result, rerender } = renderHook(
      ({ market }) => {
        const list = useInstruments(market);
        seen.push(symbols(list));
        return list;
      },
      { wrapper, initialProps: { market: read(status(FIRST, 'CLOSED')) } },
    );
    await waitFor(() => expect(symbols(result.current)).toEqual([`OF-${FIRST}`]));

    seen.length = 0;
    rerender({ market: read(status(SECOND, 'SETUP')) });
    expect(result.current.isPending).toBe(true);
    await waitFor(() => expect(symbols(result.current)).toEqual([`OF-${SECOND}`]));
    // From the first render under the new simulation on: empty, then its own list.
    expect(seen.every((each) => each.length === 0 || each[0] === `OF-${SECOND}`)).toBe(true);
    expect(server.requests.map((request) => request.url)).toEqual([
      `/api/v1/instruments?simulationId=${FIRST}`,
      `/api/v1/instruments?simulationId=${SECOND}`,
    ]);
  });

  it('keeps each simulation under its own key, so a refresh of the list reaches whichever is on screen', async () => {
    const server = marketData();
    const { client, wrapper } = withClient();
    const { result } = renderHook(() => useInstruments(read(status(FIRST))), { wrapper });
    await waitFor(() => expect(result.current.isPending).toBe(false));

    expect(client.getQueryData(['instruments', FIRST])).toBeDefined();
    expect(client.getQueryData(['instruments'])).toBeUndefined();
    await act(() => client.invalidateQueries({ queryKey: ['instruments'] }));
    expect(server.requests).toHaveLength(2);
  });

  it('asks for nothing while the exchange has no simulation, and is not left loading', async () => {
    const server = marketData();
    const { wrapper } = withClient();
    const { result } = renderHook(() => useInstruments(read(status(null, 'NOT_SET_UP'))), { wrapper });

    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(server.requests).toHaveLength(0);
    expect(result.current).toEqual({ instruments: [], feed: undefined, isPending: false, isError: false });
  });

  it("asks Market Data for its own current simulation only when the exchange's status could not be read", async () => {
    const server = marketData();
    const { wrapper } = withClient();
    const { result, rerender } = renderHook(({ market }) => useInstruments(market), {
      wrapper,
      initialProps: { market: FAILED as Parameters<typeof useInstruments>[0] },
    });
    await waitFor(() => expect(symbols(result.current)).toEqual(['OF-own']));
    expect(server.requests.map((request) => request.url)).toEqual(['/api/v1/instruments']);

    // Once the exchange answers, the list is its simulation's, asked for by id.
    rerender({ market: read(status(FIRST)) });
    await waitFor(() => expect(symbols(result.current)).toEqual([`OF-${FIRST}`]));
    expect(server.requests.at(-1)?.url).toBe(`/api/v1/instruments?simulationId=${FIRST}`);
  });

  it('says when the list could not be read', async () => {
    fakeServer().on('GET /api/v1/instruments', { status: 503, problem: { status: 503, code: 'UNAVAILABLE' } });
    const { wrapper } = withClient();
    const { result } = renderHook(() => useInstruments(read(status(FIRST))), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.instruments).toEqual([]);
  });
});

describe('reading again when the market changes', () => {
  const KEYS = [['orders'], ['portfolio']] as const;

  /** A page with three queries on screen, and how often each was read. */
  function page() {
    const { wrapper } = withClient();
    const reads = { orders: 0, portfolio: 0, other: 0 };
    const useWatched = (market: MarketStatus | undefined) => {
      useQuery({ queryKey: ['orders'], queryFn: () => ++reads.orders });
      useQuery({ queryKey: ['portfolio'], queryFn: () => ++reads.portfolio });
      useQuery({ queryKey: ['other'], queryFn: () => ++reads.other });
      useRefreshOnMarketChange(market, KEYS);
    };
    return { wrapper, reads, useWatched };
  }

  it('reads the given queries again when the market closes, and when it becomes another simulation', async () => {
    const { wrapper, reads, useWatched } = page();
    const { rerender } = renderHook(({ market }) => useWatched(market), {
      wrapper,
      initialProps: { market: undefined as MarketStatus | undefined },
    });
    await waitFor(() => expect(reads).toEqual({ orders: 1, portfolio: 1, other: 1 }));

    // The first status is no change: the page is reading everything for the first time.
    rerender({ market: status(FIRST) });
    // The same market, halted and resumed, or just a later command: nothing of it is per simulation.
    rerender({ market: status(FIRST, 'HALTED') });
    rerender({ market: { ...status(FIRST), commandSequence: '8' } });
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(reads).toEqual({ orders: 1, portfolio: 1, other: 1 });

    rerender({ market: status(FIRST, 'CLOSED') });
    await waitFor(() => expect(reads).toEqual({ orders: 2, portfolio: 2, other: 1 }));

    rerender({ market: status(SECOND, 'SETUP') });
    await waitFor(() => expect(reads).toEqual({ orders: 3, portfolio: 3, other: 1 }));
  });

  it('reads nothing again for a page that opens on a market already closed', async () => {
    const { wrapper, reads, useWatched } = page();
    renderHook(() => useWatched(status(FIRST, 'CLOSED')), { wrapper });
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(reads).toEqual({ orders: 1, portfolio: 1, other: 1 });
  });
});
