import type {
  Cancellation,
  CsrfToken,
  Identity,
  Instruments,
  Order,
  OrderPage,
  OrderRequest,
  OwnTradePage,
  Portfolio,
  Problem,
} from './types';

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

  portfolio: () => request<Portfolio>('GET', '/api/v1/portfolio'),

  ownTrades: (cursor?: string) =>
    request<OwnTradePage>('GET', `/api/v1/portfolio/trades${query({ cursor, limit: 50 })}`),

  /** Committed when it returns; the same key with the same request returns the first answer again. */
  placeOrder: (order: OrderRequest, idempotencyKey: string) =>
    request<Order>('POST', '/api/v1/orders', { body: order, idempotencyKey }),

  cancelOrder: (orderId: string, idempotencyKey: string) =>
    request<Cancellation>('POST', `/api/v1/orders/${orderId}/cancel`, { idempotencyKey }),

  /** The first committed answer for a key; a 404 means nothing committed, so retrying with that key is safe. */
  placement: (idempotencyKey: string) => request<Order>('GET', `/api/v1/commands/${idempotencyKey}`),

  orders: (status: 'all' | 'active', cursor?: string) =>
    request<OrderPage>('GET', `/api/v1/orders${query({ status, cursor, limit: status === 'all' ? 50 : undefined })}`),
};
