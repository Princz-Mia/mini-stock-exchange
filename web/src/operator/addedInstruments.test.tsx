import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';

import { ApiError } from '../api/client';
import type { InstrumentCreated } from '../api/types';
import {
  type AddedInstrument,
  addedInstruments,
  readQuickly,
  takingLonger,
  useAddedInstruments,
  WAITING_FAST_FOR_MS,
  WAITING_POLL_MS,
  waitingFor,
} from './addedInstruments';
import { instrumentPace, LIST_POLL_MS } from './OperatorPage';

const FIRST = '10000000-0000-0000-0000-000000000001';
const SECOND = '10000000-0000-0000-0000-000000000002';

const created = (instrumentId: string, symbol: string): InstrumentCreated => ({
  instrumentId,
  symbol,
  displayName: null,
  referencePrice: '50.00',
  commandSequence: '9',
});

const added = (instrumentId: string, scope: string | null, answeredAt: number): AddedInstrument => ({
  instrumentId,
  symbol: instrumentId.toUpperCase(),
  displayName: null,
  referencePrice: '50.00',
  scope,
  answeredAt,
});

function withClient() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

describe('instruments added and not listed yet', () => {
  it('are those of the simulation on screen, or of none, that the list does not have', () => {
    const all = [added('a', FIRST, 0), added('b', SECOND, 0), added('c', null, 0)];
    expect(waitingFor(all, FIRST, []).map((each) => each.instrumentId)).toEqual(['a', 'c']);
    expect(waitingFor(all, FIRST, [{ instrumentId: 'a' }]).map((each) => each.instrumentId)).toEqual(['c']);
    expect(waitingFor(all, SECOND, [{ instrumentId: 'c' }]).map((each) => each.instrumentId)).toEqual(['b']);
  });

  it('are read for quickly for a while after the answer, and then at the usual pace', () => {
    const now = 1_000_000;
    const at = (age: number) => added('a', FIRST, now - age);
    expect(takingLonger(at(0), now)).toBe(false);
    expect(takingLonger(at(WAITING_FAST_FOR_MS - 1), now)).toBe(false);
    expect(takingLonger(at(WAITING_FAST_FOR_MS), now)).toBe(true);
    expect(readQuickly([], now)).toBe(false);
    expect(readQuickly([at(WAITING_FAST_FOR_MS + 5), at(100)], now)).toBe(true);
    expect(readQuickly([at(WAITING_FAST_FOR_MS + 5)], now)).toBe(false);
  });

  it('set how often the setup list is read, from what is cached for them as it is at each read', () => {
    const { client } = withClient();
    const pace = instrumentPace(client, FIRST);
    const read = (instruments: readonly { instrumentId: string }[], error: unknown = null) =>
      pace({ state: { data: { instruments, feed: { state: 'LIVE', lag: 0 } } as never, error } });
    expect(read([])).toBe(LIST_POLL_MS);

    client.setQueryData(['instruments-added'], [added('a', FIRST, Date.now() - 1_000)]);
    expect(read([])).toBe(WAITING_POLL_MS);
    expect(read([{ instrumentId: 'a' }])).toBe(LIST_POLL_MS);
    // Another simulation's add says nothing of this list; one sent with no market created this one.
    client.setQueryData(['instruments-added'], [added('a', SECOND, Date.now() - 1_000)]);
    expect(read([])).toBe(LIST_POLL_MS);
    client.setQueryData(['instruments-added'], [added('a', null, Date.now() - 1_000)]);
    expect(read([])).toBe(WAITING_POLL_MS);
    client.setQueryData(['instruments-added'], [added('a', FIRST, Date.now() - WAITING_FAST_FOR_MS - 1)]);
    expect(read([])).toBe(LIST_POLL_MS);
    // A refused read is not asked again, whatever waits.
    client.setQueryData(['instruments-added'], [added('a', FIRST, Date.now())]);
    expect(pace({ state: { data: undefined, error: new ApiError(403, null) } })).toBe(false);
  });

  it('take the simulation an add with no market created, and go once the list has them', async () => {
    const { client, wrapper } = withClient();
    const { result, rerender } = renderHook(
      ({ simulation, listed }: { simulation: string | null; listed: { instrumentId: string }[] }) =>
        useAddedInstruments(simulation, listed),
      { wrapper, initialProps: { simulation: null as string | null, listed: [] as { instrumentId: string }[] } },
    );

    act(() => result.current.add(created('nova', 'NOVA'), null));
    await waitFor(() => expect(result.current.waiting.map((each) => each.symbol)).toEqual(['NOVA']));

    // The add created the market the exchange now names: it is that simulation's, and no later one's.
    rerender({ simulation: FIRST, listed: [] });
    await waitFor(() => expect(addedInstruments(client)[0]?.scope).toBe(FIRST));
    expect(result.current.waiting.map((each) => each.symbol)).toEqual(['NOVA']);
    rerender({ simulation: SECOND, listed: [] });
    expect(result.current.waiting).toEqual([]);

    rerender({ simulation: FIRST, listed: [{ instrumentId: 'nova' }] });
    expect(result.current.waiting).toEqual([]);
    await waitFor(() => expect(addedInstruments(client)).toEqual([]));
  });

  it('keep one record for an add answered twice under its key', () => {
    const { client, wrapper } = withClient();
    const { result } = renderHook(() => useAddedInstruments(FIRST, []), { wrapper });
    act(() => result.current.add(created('orbt', 'ORBT'), FIRST));
    act(() => result.current.add(created('orbt', 'ORBT'), FIRST));
    expect(addedInstruments(client)).toHaveLength(1);
  });
});
