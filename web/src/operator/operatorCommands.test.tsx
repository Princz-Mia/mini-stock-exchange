import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { api, COMMAND_DEADLINE_MS, resetSession } from '../api/client';
import type { MarketTransition } from '../api/types';
import { signedIn } from '../test/fakeServer';
import { useOperatorCommand } from './operatorCommands';

const SIMULATION = '10000000-0000-0000-0000-000000000001';
const HALT = '/api/v1/admin/market/halt';
const MARKET = ['market'] as const;

type Reply = { status: number; json?: unknown; problem?: unknown };

/** An answer the test gives when it chooses to: the request stays on its way until then. */
function answerLater() {
  let answer: (reply: Reply) => void = () => {};
  const reply = new Promise<Reply>((resolve) => {
    answer = resolve;
  });
  return { reply, answer: (given: Reply) => answer(given) };
}

const halt = (_: string, key: string) => api.controlMarket('halt', key);

function withClient() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

describe('an operator command kept beside the cache', () => {
  beforeEach(() => resetSession());
  afterEach(() => vi.useRealTimers());

  it('ends in no answer at the deadline rather than sending for good, and asks again under the same key', async () => {
    vi.useFakeTimers();
    const server = signedIn('operator', ['OPERATOR']).on(`POST ${HALT}`, () => new Promise<never>(() => undefined));
    const halts = () => server.requests.filter((request) => request.url === HALT);
    const { wrapper } = withClient();
    const { result } = renderHook(() => useOperatorCommand<string, MarketTransition>('market', SIMULATION, halt), {
      wrapper,
    });

    act(() => result.current.run('halt'));
    expect(result.current.outcome.kind).toBe('sending');
    await act(() => vi.advanceTimersByTimeAsync(COMMAND_DEADLINE_MS - 1));
    expect(result.current.outcome.kind).toBe('sending');
    // The last millisecond, and the cache's own tick to tell the panel.
    await act(() => vi.advanceTimersByTimeAsync(2));

    expect(result.current.outcome).toEqual({ kind: 'unresolved', problem: null, waited: COMMAND_DEADLINE_MS });
    expect(result.current.unresolved).toBe(true);
    act(() => result.current.retry());
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(halts()).toHaveLength(2);
    expect(halts()[1]?.headers['Idempotency-Key']).toBe(halts()[0]?.headers['Idempotency-Key']);
  });

  it('is still on its way after its panel has gone, and has its answer when the panel is back', async () => {
    const later = answerLater();
    const server = signedIn('operator', ['OPERATOR']).on(`POST ${HALT}`, () => later.reply);
    const halts = () => server.requests.filter((request) => request.url === HALT);
    const { client, wrapper } = withClient();
    const panel = () =>
      renderHook(() => useOperatorCommand<string, MarketTransition>('market', SIMULATION, halt), { wrapper });
    const first = panel();
    act(() => first.result.current.run('halt'));
    await waitFor(() => expect(halts()).toHaveLength(1));
    first.unmount();
    // The operator looks at another page for a while: nothing on screen holds the command meanwhile.
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));

    const again = panel();
    expect(again.result.current.outcome.kind).toBe('sending');
    expect(again.result.current.args).toBe('halt');
    // A second press finds the first still on its way, and sends nothing.
    act(() => again.result.current.run('halt'));
    later.answer({ status: 200, json: { state: 'HALTED', commandSequence: '9' } });

    await waitFor(() =>
      expect(again.result.current.outcome).toEqual({ kind: 'done', result: { state: 'HALTED', commandSequence: '9' } }),
    );
    expect(halts()).toHaveLength(1);
    expect(client.getQueryData(['operator-command', 'market'])).toMatchObject({ scope: SIMULATION, asks: 1 });
  });

  it("keeps another simulation's command out of sight, and sends a new one under a new key", async () => {
    const server = signedIn('operator', ['OPERATOR']).on(`POST ${HALT}`, {
      status: 503,
      problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' },
    });
    const halts = () => server.requests.filter((request) => request.url === HALT);
    const { wrapper } = withClient();
    const before = renderHook(() => useOperatorCommand<string, MarketTransition>('market', 'another', halt), {
      wrapper,
    });
    act(() => before.result.current.run('halt'));
    await waitFor(() => expect(before.result.current.outcome.kind).toBe('unresolved'));
    before.unmount();

    const { result } = renderHook(() => useOperatorCommand<string, MarketTransition>('market', SIMULATION, halt), {
      wrapper,
    });
    expect(result.current.outcome).toEqual({ kind: 'idle' });
    act(() => result.current.run('halt'));
    await waitFor(() => expect(halts()).toHaveLength(2));
    expect(new Set(halts().map((request) => request.headers['Idempotency-Key'])).size).toBe(2);
  });

  it('settles one with no answer once the state shows it applied, and sends the next under a new key', async () => {
    const server = signedIn('operator', ['OPERATOR']).on(`POST ${HALT}`, {
      status: 504,
      problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'The command may still complete' },
    });
    const halts = () => server.requests.filter((request) => request.url === HALT);
    const { client, wrapper } = withClient();
    const { result, rerender } = renderHook(
      ({ halted }: { halted: boolean }) =>
        useOperatorCommand<string, MarketTransition>('market', SIMULATION, halt, { reached: () => halted }),
      { wrapper, initialProps: { halted: false } },
    );
    act(() => result.current.run('halt'));
    await waitFor(() => expect(result.current.unresolved).toBe(true));
    expect(result.current.settled).toBe(false);

    rerender({ halted: true });
    expect(result.current.settled).toBe(true);
    expect(result.current.unresolved).toBe(false);
    // Kept settled when the state moves on, so it is never offered to send again.
    await waitFor(() => expect(client.getQueryData(['operator-command', 'market'])).toMatchObject({ settled: true }));
    rerender({ halted: false });
    expect(result.current.settled).toBe(true);

    act(() => result.current.run('halt'));
    await waitFor(() => expect(halts()).toHaveLength(2));
    expect(new Set(halts().map((request) => request.headers['Idempotency-Key'])).size).toBe(2);
    await waitFor(() => expect(result.current.after).toBe('halt'));
  });

  it('holds a done command until the query that shows what it did has been read since its answer', async () => {
    signedIn('operator', ['OPERATOR']).on(`POST ${HALT}`, { status: 200, json: { state: 'HALTED', commandSequence: '9' } });
    const { client, wrapper } = withClient();
    client.setQueryData(MARKET, { state: 'OPEN' });
    const { result } = renderHook(
      () => useOperatorCommand<string, MarketTransition>('market', SIMULATION, halt, { confirmedBy: MARKET }),
      { wrapper },
    );

    act(() => result.current.run('halt'));
    await waitFor(() => expect(result.current.outcome.kind).toBe('done'));
    expect(result.current.awaitingRead).toBe(true);
    // A read that lands after the answer.
    act(() => client.setQueryData(MARKET, { state: 'HALTED' }));
    expect(result.current.awaitingRead).toBe(false);
  });
});
