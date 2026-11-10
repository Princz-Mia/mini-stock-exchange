import type {
  AccountCreated,
  AccountPage,
  AccountRequest,
  BotPopulation,
  BotRunList,
  BotRunRequest,
  BotRunStarted,
  BotRunStopping,
  BotsCreated,
  BotsRequest,
  Candles,
  Cancellation,
  CsrfToken,
  Identity,
  InstrumentCreated,
  InstrumentRequest,
  Instruments,
  MarketStatus,
  MarketTransition,
  Order,
  OrderPage,
  OrderRequest,
  OwnTradePage,
  Portfolio,
  Problem,
  TokenRevoked,
} from './types';

/** Which end of a paged list to start from. */
export type ListOrder = 'newest' | 'oldest';

/** A response other than 2xx. `code` is the contract's stable reason when the server sent one. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | null;

  constructor(status: number, problem: Problem | null) {
    super(problem?.detail ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }

  get code(): string | undefined {
    return this.problem?.code;
  }
}

/**
 * A 4xx answer: the request itself was refused, so repeating it unchanged gets the same answer. Retrying and polling
 * stop on these; a 5xx or a network failure may recover and is retried.
 */
export function isRefusal(error: unknown): boolean {
  return error instanceof ApiError && error.status >= 400 && error.status < 500;
}

/** A polling interval that stops once the server has refused the request, such as a user with no account. */
export function pollEvery(milliseconds: number) {
  return (query: { state: { error: unknown } }): number | false =>
    isRefusal(query.state.error) ? false : milliseconds;
}

/** The session's CSRF token, fetched on first use and again after login rotates it. */
let csrf: CsrfToken | null = null;

async function csrfToken(refresh = false): Promise<CsrfToken> {
  if (csrf === null || refresh) {
    csrf = await request<CsrfToken>('GET', '/api/v1/auth/csrf');
  }
  return csrf;
}

/** Forgets the token, for logout and for tests. */
export function resetSession(): void {
  csrf = null;
}

interface Options {
  body?: unknown;
  idempotencyKey?: string;
}

async function request<T>(method: 'GET' | 'POST', path: string, options: Options = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') {
    const token = await csrfToken();
    headers[token.headerName] = token.token;
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (options.idempotencyKey !== undefined) {
    headers['Idempotency-Key'] = options.idempotencyKey;
  }
  const init: RequestInit = { method, headers, credentials: 'same-origin' };
  if (options.body !== undefined) {
    init.body = JSON.stringify(options.body);
  }
  const response = await fetch(path, init);
  const type = response.headers.get('Content-Type') ?? '';
  const data: unknown = type.includes('json') ? await response.json() : undefined;
  if (!response.ok) {
    throw new ApiError(response.status, type.includes('problem+json') ? (data as Problem) : null);
  }
  return data as T;
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined) {
      search.set(name, String(value));
    }
  }
  const text = search.toString();
  return text === '' ? '' : `?${text}`;
}

export const api = {
  me: () => request<Identity>('GET', '/api/v1/me'),

  /** Login rotates the session and its CSRF token, so the token is fetched again afterwards. */
  login: async (username: string, password: string) => {
    await csrfToken(true);
    const identity = await request<Identity>('POST', '/api/v1/auth/login', { body: { username, password } });
    await csrfToken(true);
    return identity;
  },

  logout: async () => {
    try {
      await request<void>('POST', '/api/v1/auth/logout');
    } finally {
      resetSession();
    }
  },

  instruments: () => request<Instruments>('GET', '/api/v1/instruments'),

  /** One-minute bars with from <= start < to, oldest first, and how far the analytics are behind the tape. */
  candles: (instrumentId: string, from: string, to: string) =>
    request<Candles>('GET', `/api/v1/instruments/${instrumentId}/candles${query({ from, to })}`),

  portfolio: () => request<Portfolio>('GET', '/api/v1/portfolio'),

  /** `filters` narrows to an instrument (`instrumentId`) or a side (`side`). */
  ownTrades: (cursor?: string, limit = 50, order?: ListOrder, filters: Readonly<Record<string, string>> = {}) =>
    request<OwnTradePage>('GET', `/api/v1/portfolio/trades${query({ ...filters, cursor, limit, order })}`),

  /** Committed when it returns; the same key with the same request returns the first answer again. */
  placeOrder: (order: OrderRequest, idempotencyKey: string) =>
    request<Order>('POST', '/api/v1/orders', { body: order, idempotencyKey }),

  cancelOrder: (orderId: string, idempotencyKey: string) =>
    request<Cancellation>('POST', `/api/v1/orders/${orderId}/cancel`, { idempotencyKey }),

  /** The first committed answer for a key; a 404 means nothing committed, so retrying with that key is safe. */
  placement: (idempotencyKey: string) => request<Order>('GET', `/api/v1/commands/${idempotencyKey}`),

  /** The authoritative trading phase, readable by anyone. */
  market: () => request<MarketStatus>('GET', '/api/v1/market'),

  /** Operator commands: each is committed when it returns, and a retry with its key returns the first answer. */
  controlMarket: (action: 'open' | 'halt' | 'resume', idempotencyKey: string) =>
    request<MarketTransition>('POST', `/api/v1/admin/market/${action}`, { idempotencyKey }),

  addInstrument: (instrument: InstrumentRequest, idempotencyKey: string) =>
    request<InstrumentCreated>('POST', '/api/v1/admin/instruments', { body: instrument, idempotencyKey }),

  provisionAccount: (account: AccountRequest, idempotencyKey: string) =>
    request<AccountCreated>('POST', '/api/v1/admin/accounts', { body: account, idempotencyKey }),

  accounts: (cursor?: string, limit = 50, order?: ListOrder) =>
    request<AccountPage>('GET', `/api/v1/admin/accounts${query({ cursor, limit, order })}`),

  botPopulation: () => request<BotPopulation>('GET', '/api/v1/admin/bots'),

  provisionBots: (bots: BotsRequest, idempotencyKey: string) =>
    request<BotsCreated>('POST', '/api/v1/admin/bots', { body: bots, idempotencyKey }),

  /** The newest runs first, with what their workers last reported. */
  botRuns: () => request<BotRunList>('GET', `/api/v1/admin/bot-runs${query({ limit: 10 })}`),

  /** Every run, a page at a time. */
  botRunPage: (cursor: string | undefined, limit: number, order: ListOrder) =>
    request<BotRunList>('GET', `/api/v1/admin/bot-runs${query({ cursor, limit, order })}`),

  startBotRun: (run: BotRunRequest, idempotencyKey: string) =>
    request<BotRunStarted>('POST', '/api/v1/admin/bot-runs', { body: run, idempotencyKey }),

  stopBotRun: (runId: string, idempotencyKey: string) =>
    request<BotRunStopping>('POST', `/api/v1/admin/bot-runs/${runId}/stop`, { idempotencyKey }),

  revokeBotToken: (accountId: string, idempotencyKey: string) =>
    request<TokenRevoked>('POST', `/api/v1/admin/bots/${accountId}/revoke-token`, { idempotencyKey }),

  /** With status=all, `filters` narrows to an instrument (`instrumentId`), a side (`side`) or a state (`state`). */
  orders: (
    status: 'all' | 'active',
    cursor?: string,
    limit?: number,
    order?: ListOrder,
    filters: Readonly<Record<string, string>> = {},
  ) =>
    request<OrderPage>(
      'GET',
      `/api/v1/orders${query({ ...filters, status, cursor, limit: status === 'all' ? (limit ?? 50) : undefined, order })}`,
    ),
};
