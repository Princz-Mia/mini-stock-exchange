import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { fakeServer } from '../test/fakeServer';
import { ApiError, api, isRefusal, pollEvery, resetSession } from './client';

describe('api client', () => {
  beforeEach(() => resetSession());
  afterEach(() => resetSession());

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
});
