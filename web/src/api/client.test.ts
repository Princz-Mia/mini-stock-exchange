import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fakeServer } from '../test/fakeServer';
import {
  ApiError,
  api,
  CLOSE_DEADLINE_MS,
  COMMAND_DEADLINE_MS,
  isRefusal,
  NoAnswerError,
  pollEvery,
  READ_DEADLINE_MS,
  resetSession,
} from './client';

/** A handler that never answers: the request stays on its way until the client stops waiting. */
const never = () => new Promise<never>(() => undefined);

/** Whether a promise has settled yet, without waiting for it. */
async function settled(promise: Promise<unknown>): Promise<boolean> {
  let done = false;
  promise.then(
    () => (done = true),
    () => (done = true),
  );
  await vi.advanceTimersByTimeAsync(0);
  return done;
}

describe('api client', () => {
  beforeEach(() => resetSession());
  afterEach(() => {
    resetSession();
    vi.useRealTimers();
  });

  it('sends reads without a CSRF token', async () => {
    const server = fakeServer().on('GET /api/v1/instruments', {
      status: 200,
      json: { instruments: [], feed: { state: 'LIVE', lag: 0 } },
    });

    await api.instruments();

    expect(server.requests).toHaveLength(1);
    expect(server.requests[0]?.headers['X-CSRF-TOKEN']).toBeUndefined();
  });

  it('fetches a fresh token after login, because login rotates it', async () => {
    let issued = 0;
    const server = fakeServer()
      .on('GET /api/v1/auth/csrf', () => ({ status: 200, json: { headerName: 'X-CSRF-TOKEN', token: `t${++issued}` } }))
      .on('POST /api/v1/auth/login', { status: 200, json: { username: 'trader', roles: ['TRADER'] } })
      .on('POST /api/v1/auth/logout', { status: 204 });

    await api.login('trader', 'secret');
    await api.logout();

    const login = server.requests.find((request) => request.url === '/api/v1/auth/login');
    const logout = server.requests.find((request) => request.url === '/api/v1/auth/logout');
    expect(login?.headers['X-CSRF-TOKEN']).toBe('t1');
    expect(login?.body).toEqual({ username: 'trader', password: 'secret' });
    expect(logout?.headers['X-CSRF-TOKEN']).toBe('t2');
  });

  it("reads the exchange's clock from an answer about the bot runs, and falls back to the browser's", async () => {
    vi.setSystemTime(new Date('2026-11-06T10:06:02.500Z'));
    const reply = { status: 200, json: { runs: [], nextCursor: null } };
    const server = fakeServer().on('GET /api/v1/admin/bot-runs', {
      ...reply,
      headers: { Date: 'Fri, 06 Nov 2026 10:00:02 GMT' },
    });
    expect(await api.botRuns()).toEqual({ runs: [], nextCursor: null, answeredAt: Date.parse('2026-11-06T10:00:02Z') });

    server.on('GET /api/v1/admin/bot-runs', reply);
    expect((await api.botRuns()).answeredAt).toBe(Date.parse('2026-11-06T10:06:02.500Z'));
    server.on('GET /api/v1/admin/bot-runs', { ...reply, headers: { Date: 'not a date' } });
    expect((await api.botRuns()).answeredAt).toBe(Date.parse('2026-11-06T10:06:02.500Z'));
  });

  it('turns a problem response into an ApiError with its code', async () => {
    fakeServer().on('GET /api/v1/portfolio', {
      status: 403,
      problem: { status: 403, code: 'NO_TRADING_ACCOUNT', detail: 'This user has no trading account' },
    });

    const error = await api.portfolio().catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
    expect((error as ApiError).code).toBe('NO_TRADING_ACCOUNT');
    expect((error as ApiError).message).toBe('This user has no trading account');
  });

  it('keeps a bodiless refusal as an error without a problem', async () => {
    fakeServer().on('GET /api/v1/me', { status: 401 });

    const error = (await api.me().catch((caught: unknown) => caught)) as ApiError;

    expect(error.status).toBe(401);
    expect(error.problem).toBeNull();
  });

  it('pages with the cursor the server gave', async () => {
    const server = fakeServer().on('GET /api/v1/portfolio/trades', {
      status: 200,
      json: { trades: [], nextCursor: null },
    });

    await api.ownTrades('djE6MTB8Mg');

    expect(server.requests[0]?.url).toBe('/api/v1/portfolio/trades?cursor=djE6MTB8Mg&limit=50');
  });

  it('stops polling and retrying once the server refuses, but not after an outage', () => {
    const refused = new ApiError(403, { status: 403, code: 'NO_TRADING_ACCOUNT' });
    const outage = new ApiError(503, { status: 503, code: 'TRADING_UNAVAILABLE' });
    const poll = pollEvery(5_000);

    expect(isRefusal(refused)).toBe(true);
    expect(isRefusal(outage)).toBe(false);
    expect(isRefusal(new TypeError('Failed to fetch'))).toBe(false);
    expect(poll({ state: { error: refused } })).toBe(false);
    expect(poll({ state: { error: outage } })).toBe(5_000);
    expect(poll({ state: { error: null } })).toBe(5_000);
  });

  describe('deadlines', () => {
    beforeEach(() => vi.useFakeTimers());

    it('gives up on a command with no answer after its deadline, and says it was not answered', async () => {
      const server = fakeServer()
        .on('GET /api/v1/auth/csrf', { status: 200, json: { headerName: 'X-CSRF-TOKEN', token: 't' } })
        .on('POST /api/v1/admin/market/halt', never);

      const halt = api.controlMarket('halt', 'k-1').catch((caught: unknown) => caught);
      await vi.advanceTimersByTimeAsync(COMMAND_DEADLINE_MS - 1);
      expect(await settled(halt)).toBe(false);
      await vi.advanceTimersByTimeAsync(1);

      const error = await halt;
      expect(error).toBeInstanceOf(NoAnswerError);
      expect((error as NoAnswerError).waited).toBe(COMMAND_DEADLINE_MS);
      const sent = server.requests.find((request) => request.method === 'POST');
      expect(sent?.signal?.aborted).toBe(true);
    });

    it('waits longer for a close than the exchange does, and no longer than the proxy', async () => {
      fakeServer()
        .on('GET /api/v1/auth/csrf', { status: 200, json: { headerName: 'X-CSRF-TOKEN', token: 't' } })
        .on('POST /api/v1/admin/market/close', never);

      const close = api.closeMarket('k-2').catch((caught: unknown) => caught);
      await vi.advanceTimersByTimeAsync(45_000);
      expect(await settled(close)).toBe(false);
      await vi.advanceTimersByTimeAsync(CLOSE_DEADLINE_MS - 45_000);

      expect(await close).toBeInstanceOf(NoAnswerError);
      expect(CLOSE_DEADLINE_MS).toBeGreaterThan(45_000);
      expect(CLOSE_DEADLINE_MS).toBeLessThan(60_000);
    });

    it('reads a plain abort at the deadline as no answer, as older browsers give no reason', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(
          (_: RequestInfo | URL, init?: RequestInit) =>
            new Promise<never>((__, reject) =>
              init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))),
            ),
        ),
      );

      const read = api.portfolio().catch((caught: unknown) => caught);
      await vi.advanceTimersByTimeAsync(READ_DEADLINE_MS);

      expect(await read).toBeInstanceOf(NoAnswerError);
    });

    it('gives up on a read sooner than on a command', async () => {
      fakeServer().on('GET /api/v1/market', never);

      const read = api.market().catch((caught: unknown) => caught);
      await vi.advanceTimersByTimeAsync(READ_DEADLINE_MS);

      expect(await read).toBeInstanceOf(NoAnswerError);
      expect(READ_DEADLINE_MS).toBeLessThan(COMMAND_DEADLINE_MS);
    });

    it('stops a read when its caller stops it, which is not a missing answer', async () => {
      const server = fakeServer().on('GET /api/v1/admin/bot-runs', never);
      const caller = new AbortController();

      const read = api.botRuns({ signal: caller.signal }).catch((caught: unknown) => caught);
      caller.abort();

      const error = await read;
      expect(error).not.toBeInstanceOf(NoAnswerError);
      expect(server.requests[0]?.signal?.aborted).toBe(true);
      // Nothing is left to time out afterwards.
      expect(vi.getTimerCount()).toBe(0);
    });

    it('stops the request of a query that is cancelled, so it does not hold a connection', async () => {
      const server = fakeServer().on('GET /api/v1/market', never);
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

      const read = client.fetchQuery({ queryKey: ['market'], queryFn: api.market }).catch(() => undefined);
      await vi.advanceTimersByTimeAsync(0);
      await client.cancelQueries({ queryKey: ['market'] });
      await read;

      expect(server.requests[0]?.signal?.aborted).toBe(true);
    });
  });
});
