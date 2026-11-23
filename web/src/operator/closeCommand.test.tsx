import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetSession } from '../api/client';
import { signedIn } from '../test/fakeServer';
import { useCloseCommand } from './closeCommand';

const SIMULATION = '10000000-0000-0000-0000-000000000001';
const CLOSE = '/api/v1/admin/market/close';

function withClient() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

describe('the close command', () => {
  beforeEach(() => resetSession());

  it('sends one close however often it is asked for while that one is on its way', async () => {
    type Reply = { status: number; json?: unknown };
    let answer: (reply: Reply) => void = () => {};
    const later = new Promise<Reply>((resolve) => {
      answer = resolve;
    });
    const server = signedIn('operator', ['OPERATOR']).on(`POST ${CLOSE}`, () => later);
    const closes = () => server.requests.filter((request) => request.url === CLOSE);
    const { wrapper } = withClient();
    const { result } = renderHook(() => useCloseCommand(SIMULATION), { wrapper });
    expect(result.current.outcome).toEqual({ kind: 'idle' });

    act(() => {
      result.current.run();
      result.current.run();
    });
    await waitFor(() => expect(closes()).toHaveLength(1));
    expect(result.current.outcome).toEqual({ kind: 'sending' });
    // Asked for again from another place on the page, or by a second press that got through.
    act(() => result.current.run());
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(closes()).toHaveLength(1);

    const closed = { state: 'CLOSED', commandSequence: '8', canceledOrders: 3, endedRuns: 0 };
    answer({ status: 200, json: closed });
    await waitFor(() => expect(result.current.outcome).toEqual({ kind: 'done', result: closed }));
    expect(result.current.askedBefore).toBe(false);
  });

  it('sends nothing while the exchange has no market to close', async () => {
    const server = signedIn('operator', ['OPERATOR']);
    const { wrapper } = withClient();
    const { result } = renderHook(() => useCloseCommand(null), { wrapper });

    act(() => result.current.run());
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(server.requests).toHaveLength(0);
    expect(result.current.outcome).toEqual({ kind: 'idle' });
  });

  it('keeps a close with no answer, and forgets only one that has its answer', async () => {
    const answers = [
      { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } },
      { status: 409, problem: { status: 409, code: 'MARKET_NOT_SET_UP', detail: 'No market has been set up yet' } },
    ];
    const server = signedIn('operator', ['OPERATOR']);
    const closes = () => server.requests.filter((request) => request.url === CLOSE);
    server.on(`POST ${CLOSE}`, () => answers[closes().length - 1]!);
    const { wrapper } = withClient();
    const { result } = renderHook(() => useCloseCommand(SIMULATION), { wrapper });

    act(() => result.current.run());
    await waitFor(() => expect(result.current.outcome.kind).toBe('unresolved'));
    // Another command cannot take its place: its key is still owed an answer.
    act(() => result.current.forget());
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(result.current.outcome.kind).toBe('unresolved');

    act(() => result.current.run());
    await waitFor(() => expect(result.current.outcome.kind).toBe('refused'));
    expect(result.current.outcome).toMatchObject({ code: 'MARKET_NOT_SET_UP', message: 'No market has been set up yet' });
    expect(result.current.askedBefore).toBe(true);
    expect(closes().map((request) => request.headers['Idempotency-Key'])).toEqual([
      closes()[0]?.headers['Idempotency-Key'],
      closes()[0]?.headers['Idempotency-Key'],
    ]);

    act(() => result.current.forget());
    await waitFor(() => expect(result.current.outcome).toEqual({ kind: 'idle' }));
  });
});
