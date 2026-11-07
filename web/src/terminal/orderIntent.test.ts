import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetSession } from '../api/client';
import type { OrderRequest } from '../api/types';
import { fakeServer } from '../test/fakeServer';
import { useOrderIntent } from './orderIntent';

const BUY: OrderRequest = {
  instrumentId: '00000000-0000-0000-0000-000000000001',
  side: 'BUY',
  type: 'LIMIT',
  timeInForce: 'GTC',
  quantity: 10,
  limitPrice: '100.00',
};

const ORDER = {
  orderId: '00000000-0000-0000-0000-0000000000aa',
  state: 'PARTIALLY_FILLED',
  reason: null,
  filledQuantity: 4,
  workingQuantity: 6,
  canceledQuantity: 0,
  commandSequence: '9',
};

function keys() {
  let n = 0;
  return () => `00000000-0000-0000-0000-00000000000${++n}`;
}

function server() {
  return fakeServer().on('GET /api/v1/auth/csrf', { status: 200, json: { headerName: 'X-CSRF-TOKEN', token: 't' } });
}

function placements(recorded: ReturnType<typeof fakeServer>['requests']) {
  return recorded.filter((request) => request.method === 'POST' && request.url === '/api/v1/orders');
}

describe('order intent', () => {
  beforeEach(() => resetSession());

  it('settles an accepted order, and gives the next intent a new key', async () => {
    const fake = server().on('POST /api/v1/orders', { status: 201, json: ORDER });
    const newKey = keys();
    const { result } = renderHook(() => useOrderIntent({ newKey }));

    await act(() => result.current.submit(BUY));
    await act(() => result.current.submit(BUY));

    expect(result.current.outcome).toEqual({ kind: 'accepted', order: ORDER });
    expect(placements(fake.requests).map((request) => request.headers['Idempotency-Key'])).toEqual([
      '00000000-0000-0000-0000-000000000001',
      '00000000-0000-0000-0000-000000000002',
    ]);
  });

  it('reports a committed rejection with its reason', async () => {
    server().on('POST /api/v1/orders', {
      status: 422,
      problem: { status: 422, code: 'INSUFFICIENT_CASH', orderId: ORDER.orderId, commandSequence: '3' },
    });
    const newKey = keys();
    const { result } = renderHook(() => useOrderIntent({ newKey }));

    await act(() => result.current.submit(BUY));

    expect(result.current.outcome).toEqual({ kind: 'rejected', code: 'INSUFFICIENT_CASH' });
  });

  it('retries a busy refusal with the same key and the same request', async () => {
    let busy = true;
    const fake = server().on('POST /api/v1/orders', () =>
      busy ? { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY' } } : { status: 201, json: ORDER },
    );
    const newKey = keys();
    const { result } = renderHook(() => useOrderIntent({ newKey }));

    await act(() => result.current.submit(BUY));
    expect(result.current.outcome.kind).toBe('busy');
    busy = false;
    await act(() => result.current.submit({ ...BUY, quantity: 99 }));

    const sent = placements(fake.requests);
    expect(sent.map((request) => request.headers['Idempotency-Key'])).toEqual([
      '00000000-0000-0000-0000-000000000001',
      '00000000-0000-0000-0000-000000000001',
    ]);
    expect(sent[1]?.body).toEqual(BUY);
    expect(result.current.outcome.kind).toBe('accepted');
  });

  it('retries a rate-limited refusal with the same key and the same request', async () => {
    let limited = true;
    const fake = server().on('POST /api/v1/orders', () =>
      limited ? { status: 429, problem: { status: 429, code: 'RATE_LIMITED' } } : { status: 201, json: ORDER },
    );
    const newKey = keys();
    const { result } = renderHook(() => useOrderIntent({ newKey }));

    await act(() => result.current.submit(BUY));
    expect(result.current.outcome.kind).toBe('limited');
    expect(result.current.pendingRequest()).toEqual(BUY);
    limited = false;
    await act(() => result.current.submit({ ...BUY, quantity: 99 }));

    const sent = placements(fake.requests);
    expect(sent.map((request) => request.headers['Idempotency-Key'])).toEqual([
      '00000000-0000-0000-0000-000000000001',
      '00000000-0000-0000-0000-000000000001',
    ]);
    expect(sent[1]?.body).toEqual(BUY);
    expect(result.current.outcome.kind).toBe('accepted');
  });

  it('resolves a timeout by looking the key up, without sending the order again', async () => {
    const fake = server()
      .on('POST /api/v1/orders', { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN' } })
      .on('GET /api/v1/commands/00000000-0000-0000-0000-000000000001', { status: 201, json: ORDER });
    const newKey = keys();
    const { result } = renderHook(() => useOrderIntent({ newKey, lookupDelay: 1 }));

    await act(() => result.current.submit(BUY));

    expect(result.current.outcome).toEqual({ kind: 'accepted', order: ORDER });
    expect(placements(fake.requests)).toHaveLength(1);
  });

  it('after a timeout that committed nothing, retries safely under the same key', async () => {
    let timeout = true;
    const fake = server()
      .on('POST /api/v1/orders', () =>
        timeout ? { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN' } } : { status: 201, json: ORDER },
      )
      .on('GET /api/v1/commands/00000000-0000-0000-0000-000000000001', {
        status: 404,
        problem: { status: 404, code: 'COMMAND_NOT_FOUND' },
      });
    const newKey = keys();
    const { result } = renderHook(() => useOrderIntent({ newKey, lookupDelay: 1 }));

    await act(() => result.current.submit(BUY));
    expect(result.current.outcome.kind).toBe('notCommitted');
    timeout = false;
    await act(() => result.current.submit(BUY));

    expect(placements(fake.requests).map((request) => request.headers['Idempotency-Key'])).toEqual([
      '00000000-0000-0000-0000-000000000001',
      '00000000-0000-0000-0000-000000000001',
    ]);
  });

  it('ignores a second submit while the first is in flight', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const fake = server().on('POST /api/v1/orders', { status: 201, json: ORDER });
    const realFetch = globalThis.fetch;
    globalThis.fetch = (async (...args: Parameters<typeof fetch>) => {
      await gate;
      return realFetch(...args);
    }) as typeof fetch;
    const newKey = keys();
    const { result } = renderHook(() => useOrderIntent({ newKey }));

    let first: Promise<void> = Promise.resolve();
    act(() => {
      first = result.current.submit(BUY);
      void result.current.submit(BUY);
    });
    release();
    await act(() => first);

    await waitFor(() => expect(result.current.outcome.kind).toBe('accepted'));
    expect(placements(fake.requests)).toHaveLength(1);
  });
});
