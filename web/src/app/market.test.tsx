import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiError, resetSession } from '../api/client';
import type { MarketStatus } from '../api/types';
import { fakeServer } from '../test/fakeServer';
import { fundingMoment, listedSimulation, useAskAgainWhenFunded, useInstruments, useRefreshOnMarketChange } from './market';

const FIRST = '10000000-0000-0000-0000-000000000001';
const SECOND = '10000000-0000-0000-0000-000000000002';
const THIRD = '10000000-0000-0000-0000-000000000003';

function status(simulationId: string | null, state: MarketStatus['state'] = 'OPEN'): MarketStatus {
  return {
    state,
    simulationId,
    name: null,
    openedAt: '2026-11-05T09:30:00Z',
    closesAt: null,
    closedAt: state === 'CLOSED' ? '2026-11-05T16:00:00Z' : null,
    closedOnSchedule: false,
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

  it("goes on from Market Data's own list when the status then names the same simulation, and from no other's", async () => {
    // Market Data says which simulation its own current list is of.
    const server = fakeServer().on('GET /api/v1/instruments', (request) => {
      const asked = new URLSearchParams(request.url.split('?')[1] ?? '').get('simulationId');
      return {
        status: 200,
        json: {
          simulationId: asked ?? FIRST,
          instruments: [{ instrumentId: `i-${asked ?? 'own'}`, symbol: asked === null ? 'OWN' : `OF-${asked}` }],
          feed: { state: 'LIVE', lag: 0 },
        },
      };
    });
    for (const named of [FIRST, SECOND]) {
      server.requests.length = 0;
      const { wrapper } = withClient();
      const seen: { pending: boolean; symbols: string[] }[] = [];
      const { result, rerender, unmount } = renderHook(
        ({ market }) => {
          const list = useInstruments(market);
          seen.push({ pending: list.isPending, symbols: symbols(list) });
          return list;
        },
        { wrapper, initialProps: { market: FAILED as Parameters<typeof useInstruments>[0] } },
      );
      await waitFor(() => expect(symbols(result.current)).toEqual(['OWN']));

      seen.length = 0;
      rerender({ market: read(status(named)) });
      await waitFor(() => expect(symbols(result.current)).toEqual([`OF-${named}`]));
      expect(server.requests.map((request) => request.url)).toEqual([
        '/api/v1/instruments',
        `/api/v1/instruments?simulationId=${named}`,
      ]);
      if (named === FIRST) {
        // The same simulation: its list stays on screen while it is asked for again by id, and nothing waits.
        expect(seen.every((each) => !each.pending)).toBe(true);
        expect(seen[0]?.symbols).toEqual(['OWN']);
      } else {
        // Another simulation: nothing of Market Data's own list is shown under its name.
        expect(seen[0]).toEqual({ pending: true, symbols: [] });
        expect(seen.some((each) => each.symbols.includes('OWN'))).toBe(false);
      }
      unmount();
    }
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

    // Another simulation with nothing else different: one closed and the next set up between two reads of the status.
    rerender({ market: status(THIRD, 'SETUP') });
    await waitFor(() => expect(reads).toEqual({ orders: 4, portfolio: 4, other: 1 }));
  });

  it('drops what the simulation before showed and counts a generation, but keeps a closed market on screen', async () => {
    const { wrapper } = withClient();
    let reads = 0;
    // Each read of the list answers with the simulation the status named when it was asked.
    let named = 'first';
    const seen: { generation: number; orders: string | undefined }[] = [];
    const { result, rerender } = renderHook(
      ({ market }) => {
        const orders = useQuery({ queryKey: ['orders'], queryFn: () => new Promise<string>((resolve) => {
          reads += 1;
          const answer = named;
          setTimeout(() => resolve(answer), 5);
        }) });
        const generation = useRefreshOnMarketChange(market, KEYS);
        seen.push({ generation, orders: orders.data });
        return { generation, orders: orders.data };
      },
      { wrapper, initialProps: { market: status(FIRST) as MarketStatus | undefined } },
    );
    await waitFor(() => expect(result.current).toEqual({ generation: 0, orders: 'first' }));

    // A close is the same market: what it showed stays while it is read again, and nothing is drawn anew.
    seen.length = 0;
    named = 'first, closed';
    rerender({ market: status(FIRST, 'CLOSED') });
    await waitFor(() => expect(result.current).toEqual({ generation: 0, orders: 'first, closed' }));
    expect(seen.every((each) => each.orders !== undefined)).toBe(true);

    // Another simulation: nothing of the one before is shown with the new generation while its own is read.
    seen.length = 0;
    named = 'second';
    rerender({ market: status(SECOND, 'SETUP') });
    await waitFor(() => expect(result.current).toEqual({ generation: 1, orders: 'second' }));
    expect(seen.filter((each) => each.generation === 1).map((each) => each.orders)).not.toContain('first, closed');
    expect(reads).toBe(3);
  });

  it('goes on with the same page when the first simulation of a market that had none starts', async () => {
    const { wrapper } = withClient();
    const { result, rerender } = renderHook(
      ({ market }) => {
        const orders = useQuery({ queryKey: ['orders'], queryFn: () => 'kept' });
        return { generation: useRefreshOnMarketChange(market, KEYS), orders: orders.data };
      },
      { wrapper, initialProps: { market: status(null, 'NOT_SET_UP') as MarketStatus | undefined } },
    );
    await waitFor(() => expect(result.current.orders).toBe('kept'));
    rerender({ market: status(FIRST, 'SETUP') });
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(result.current).toEqual({ generation: 0, orders: 'kept' });
    // The next one is another simulation, as any after it.
    rerender({ market: status(SECOND, 'SETUP') });
    await waitFor(() => expect(result.current.generation).toBe(1));
  });

  it('reads nothing again for a page that opens on a market already closed', async () => {
    const { wrapper, reads, useWatched } = page();
    renderHook(() => useWatched(status(FIRST, 'CLOSED')), { wrapper });
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(reads).toEqual({ orders: 1, portfolio: 1, other: 1 });
  });

  it('drops what was cached of the simulation before for a page opened after the next one started', async () => {
    const { wrapper } = withClient();
    let named = 'first';
    const seen: { generation: number; orders: string | undefined }[] = [];
    const usePage = (market: MarketStatus) => {
      const orders = useQuery({
        queryKey: ['orders'],
        queryFn: () => {
          const answer = named;
          return new Promise<string>((resolve) => setTimeout(() => resolve(answer), 5));
        },
      });
      const generation = useRefreshOnMarketChange(market, KEYS);
      seen.push({ generation, orders: orders.data });
      return { generation, orders: orders.data };
    };
    const first = renderHook(() => usePage(status(FIRST, 'CLOSED')), { wrapper });
    await waitFor(() => expect(first.result.current.orders).toBe('first'));
    first.unmount();

    // On another page meanwhile, help say, while the top bar reads the next simulation's status: the page opens on it,
    // so it sees no change of its own, but what is cached for it is still the closed market's.
    named = 'second';
    seen.length = 0;
    const second = renderHook(() => usePage(status(SECOND, 'SETUP')), { wrapper });
    await waitFor(() => expect(second.result.current).toEqual({ generation: 1, orders: 'second' }));
    expect(seen.filter((each) => each.generation === 1).map((each) => each.orders)).not.toContain('first');
    second.unmount();

    // Opened again on the same simulation: what is cached is its own, and stays on screen while it is read again.
    seen.length = 0;
    const third = renderHook(() => usePage(status(SECOND, 'SETUP')), { wrapper });
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(seen.every((each) => each.generation === 0 && each.orders === 'second')).toBe(true);
    third.unmount();
  });
});

describe('asking again once an account may have been created', () => {
  const KEYS = [['portfolio'], ['orders']] as const;
  const refused = () => Promise.reject(new ApiError(403, { status: 403, code: 'NO_TRADING_ACCOUNT', detail: 'No account' }));

  it('changes with every command while the market is set up, with its opening, and with another simulation only', () => {
    const setup = status(FIRST, 'SETUP');
    expect(fundingMoment(undefined)).toBeUndefined();
    expect(fundingMoment({ ...setup, commandSequence: '8' })).not.toBe(fundingMoment(setup));
    expect(fundingMoment(status(FIRST, 'OPEN'))).not.toBe(fundingMoment(setup));
    expect(fundingMoment({ ...status(FIRST, 'OPEN'), commandSequence: '8' })).toBe(fundingMoment(status(FIRST, 'OPEN')));
    expect(fundingMoment(status(SECOND, 'OPEN'))).not.toBe(fundingMoment(status(FIRST, 'OPEN')));
    expect(fundingMoment({ ...status(null, 'NOT_SET_UP'), commandSequence: '1' })).not.toBe(
      fundingMoment({ ...status(null, 'NOT_SET_UP'), commandSequence: null }),
    );
  });

  it('reads again only what the exchange refused, at those moments, and not on every order of an open market', async () => {
    const { wrapper } = withClient();
    const reads = { portfolio: 0, orders: 0 };
    let funded = false;
    const { result, rerender } = renderHook(
      ({ market }) => {
        const portfolio = useQuery({
          queryKey: ['portfolio'],
          queryFn: () => {
            reads.portfolio += 1;
            return funded ? Promise.resolve('account') : refused();
          },
        });
        useQuery({ queryKey: ['orders'], queryFn: () => ++reads.orders });
        useAskAgainWhenFunded(market, KEYS);
        return portfolio;
      },
      { wrapper, initialProps: { market: status(FIRST, 'SETUP') } },
    );
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(reads).toEqual({ portfolio: 1, orders: 1 });

    // Another command while it is set up: the refused one is asked again, the one answered is left to its own polling.
    rerender({ market: { ...status(FIRST, 'SETUP'), commandSequence: '8' } });
    await waitFor(() => expect(reads.portfolio).toBe(2));
    expect(reads.orders).toBe(1);

    // Its opening, after the last funding came in between two reads of the status.
    funded = true;
    rerender({ market: { ...status(FIRST, 'OPEN'), commandSequence: '9' } });
    await waitFor(() => expect(result.current.data).toBe('account'));
    expect(reads).toEqual({ portfolio: 3, orders: 1 });

    // In an open market accounts are not created: orders come and go, and nothing is asked again for them.
    funded = false;
    await act(() => result.current.refetch());
    rerender({ market: { ...status(FIRST, 'OPEN'), commandSequence: '10' } });
    rerender({ market: { ...status(FIRST, 'OPEN'), commandSequence: '11' } });
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(reads).toEqual({ portfolio: 4, orders: 1 });
  });
});
