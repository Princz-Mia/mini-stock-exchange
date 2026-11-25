import { heardFromExchange } from '../app/closeTime';
import type {
  AccountCreated,
  AccountPage,
  AccountRequest,
  BotJob,
  BotJobRequest,
  BotPopulation,
  BotRunDetail,
  BotRunList,
  BotRunRequest,
  BotRunStarted,
  BotRunStopping,
  BotsCreated,
  BotsRequest,
  Candles,
  HealthSummary,
  Cancellation,
  CsrfToken,
  Identity,
  InstrumentCreated,
  InstrumentDeleted,
  InstrumentEdited,
  InstrumentEditRequest,
  InstrumentRequest,
  Instruments,
  MarketClosed,
  MarketQuality,
  MarketStatus,
  MarketTransition,
  Order,
  OrderDetail,
  OrderPage,
  OrderRequest,
  OwnTradePage,
  PasswordChangeRequest,
  Portfolio,
  Problem,
  SimulationRequest,
  SimulationStarted,
  TokenRevoked,
  TourSeen,
  TraderAdded,
  TraderPage,
  TraderRequest,
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

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

async function request<T>(method: Method, path: string, options: Options = {}): Promise<T> {
  return (await answer<T>(method, path, options)).data;
}

/**
 * When the exchange sent a response, on its own clock: the Date header, in epoch milliseconds to the whole second. The
 * browser's clock stands in where there is none. The times in an answer are the exchange's, and a browser's clock can
 * be minutes away from it, so what is counted from them is counted to this.
 */
function sentAt(response: Response): number {
  const sent = Date.parse(response.headers.get('Date') ?? '');
  return Number.isNaN(sent) ? Date.now() : sent;
}

async function answer<T>(method: Method, path: string, options: Options = {}): Promise<{ data: T; sentAt: number }> {
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
  return { data: data as T, sentAt: sentAt(response) };
}

function query(params: Record<string, string | number | boolean | undefined>): string {
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
  tourSeen: () => request<TourSeen>('GET', '/api/v1/me/tour'),
  seeTour: (seenVersion: number) => request<TourSeen>('PUT', '/api/v1/me/tour', { body: { seenVersion } }),

  /** A trader's own password; both passwords go in the body only, and are never kept here. */
  changePassword: (change: PasswordChangeRequest) => request<void>('POST', '/api/v1/me/password', { body: change }),

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

  /**
   * One simulation's instruments: the one named, as the exchange's market status names it, or else whichever Market
   * Data takes to be current.
   */
  instruments: (simulationId?: string) =>
    request<Instruments>('GET', `/api/v1/instruments${query({ simulationId })}`),

  /** An instrument's book minute by minute over a window of command sequences, with its spread and volatility. */
  marketQuality: (instrumentId: string, fromSequence: string, toSequence: string) =>
    request<MarketQuality>(
      'GET',
      `/api/v1/instruments/${instrumentId}/market-quality${query({ fromSequence, toSequence })}`,
    ),

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

  /** One of the caller's own orders, in whatever state it is in now. */
  order: (orderId: string) => request<OrderDetail>('GET', `/api/v1/orders/${orderId}`),

  cancelOrder: (orderId: string, idempotencyKey: string) =>
    request<Cancellation>('POST', `/api/v1/orders/${orderId}/cancel`, { idempotencyKey }),

  /** The first committed answer for a key; a 404 means nothing committed, so retrying with that key is safe. */
  placement: (idempotencyKey: string) => request<Order>('GET', `/api/v1/commands/${idempotencyKey}`),

  /**
   * The authoritative trading phase, readable by anyone. Its answer also sets the exchange's clock as this page counts
   * it, for a countdown to the market's close time.
   */
  market: async () => {
    const { data, sentAt } = await answer<MarketStatus>('GET', '/api/v1/market');
    heardFromExchange(sentAt);
    return data;
  },

  /**
   * Operator commands: each is committed when it returns, and a retry with its key returns the first answer. An opening
   * may give a duration in minutes, after which the exchange closes the market itself; without one it sends no body,
   * and the market stays open until it is closed.
   */
  controlMarket: (action: 'open' | 'halt' | 'resume', idempotencyKey: string, durationMinutes?: number) =>
    request<MarketTransition>(
      'POST',
      `/api/v1/admin/market/${action}`,
      durationMinutes === undefined ? { idempotencyKey } : { body: { durationMinutes }, idempotencyKey },
    ),

  /**
   * Closes the market for good. It answers once no order rests, which on a full market can take longer than other
   * commands; a wait that runs out is answered 504, and the same key then returns the close's answer.
   */
  closeMarket: (idempotencyKey: string) =>
    request<MarketClosed>('POST', '/api/v1/admin/market/close', { idempotencyKey }),

  /**
   * Starts a new simulation, in setup, once the current one has closed, or the first one when there is none. A refusal
   * is its key's answer for good; a wait that runs out is answered 504, and the same key then returns the start's answer.
   */
  startSimulation: (simulation: SimulationRequest, idempotencyKey: string) =>
    request<SimulationStarted>('POST', '/api/v1/admin/simulations', { body: simulation, idempotencyKey }),

  addInstrument: (instrument: InstrumentRequest, idempotencyKey: string) =>
    request<InstrumentCreated>('POST', '/api/v1/admin/instruments', { body: instrument, idempotencyKey }),

  /** Only while the market is being set up. The instrument list shows the new price once market data has it. */
  editInstrument: (instrumentId: string, edit: InstrumentEditRequest, idempotencyKey: string) =>
    request<InstrumentEdited>('PUT', `/api/v1/admin/instruments/${instrumentId}`, { body: edit, idempotencyKey }),
  /** Only while the market is being set up, and only while no account holds it and no order names it. */
  deleteInstrument: (instrumentId: string, idempotencyKey: string) =>
    request<InstrumentDeleted>('DELETE', `/api/v1/admin/instruments/${instrumentId}`, { idempotencyKey }),

  provisionAccount: (account: AccountRequest, idempotencyKey: string) =>
    request<AccountCreated>('POST', '/api/v1/admin/accounts', { body: account, idempotencyKey }),

  accounts: (cursor?: string, limit = 50, order?: ListOrder) =>
    request<AccountPage>('GET', `/api/v1/admin/accounts${query({ cursor, limit, order })}`),

  /** Only while the market is being set up. The password goes in the body only, and is never kept here. */
  addTrader: (trader: TraderRequest, idempotencyKey: string) =>
    request<TraderAdded>('POST', '/api/v1/admin/traders', { body: trader, idempotencyKey }),

  /** Trader logins, with {@code funded: false} only those still without an account in this market. */
  traders: (cursor?: string, limit = 50, order?: ListOrder, funded?: boolean) =>
    request<TraderPage>('GET', `/api/v1/admin/traders${query({ funded, cursor, limit, order })}`),

  botPopulation: () => request<BotPopulation>('GET', '/api/v1/admin/bots'),

  /** How trading is keeping up: the writer, the last minute of commands, and the outbox. */
  health: () => request<HealthSummary>('GET', '/api/v1/admin/health'),

  provisionBots: (bots: BotsRequest, idempotencyKey: string) =>
    request<BotsCreated>('POST', '/api/v1/admin/bots', { body: bots, idempotencyKey }),

  /** Starts creating any number of bots in the background; answered with the job before any bot is made. */
  startBotJob: (job: BotJobRequest, idempotencyKey: string) =>
    request<BotJob>('POST', '/api/v1/admin/bot-jobs', { body: job, idempotencyKey }),

  /** The current simulation's newest bot job, whatever its state; null while it has had none. */
  currentBotJob: async (): Promise<BotJob | null> =>
    (await request<BotJob | undefined>('GET', '/api/v1/admin/bot-jobs/current')) ?? null,

  stopBotJob: (jobId: string, idempotencyKey: string) =>
    request<BotJob>('POST', `/api/v1/admin/bot-jobs/${jobId}/stop`, { idempotencyKey }),

  /**
   * The newest runs first, with what their workers last reported, and when the exchange answered, on its own clock:
   * how long a run has been going is counted to that.
   */
  botRuns: async (): Promise<BotRunList & { answeredAt: number }> => {
    const { data, sentAt } = await answer<BotRunList>('GET', `/api/v1/admin/bot-runs${query({ limit: 10 })}`);
    return { ...data, answeredAt: sentAt };
  },

  /** Every run, a page at a time. */
  botRunPage: (cursor: string | undefined, limit: number, order: ListOrder) =>
    request<BotRunList>('GET', `/api/v1/admin/bot-runs${query({ cursor, limit, order })}`),

  /** One run with its figures, null until it is finished and they are worked out. */
  botRun: (runId: string) => request<BotRunDetail>('GET', `/api/v1/admin/bot-runs/${runId}`),

  startBotRun: (run: BotRunRequest, idempotencyKey: string) =>
    request<BotRunStarted>('POST', '/api/v1/admin/bot-runs', { body: run, idempotencyKey }),

  stopBotRun: (runId: string, idempotencyKey: string) =>
    request<BotRunStopping>('POST', `/api/v1/admin/bot-runs/${runId}/stop`, { idempotencyKey }),

  revokeBotToken: (accountId: string, idempotencyKey: string) =>
    request<TokenRevoked>('POST', `/api/v1/admin/bots/${accountId}/revoke-token`, { idempotencyKey }),

  /**
   * `active` is every open order; `all` the history in pages; `finished` the most recently ended, one page. `filters`
   * narrows to an instrument (`instrumentId`) or a side (`side`), and the history also to a state (`state`).
   */
  orders: (
    status: 'all' | 'active' | 'finished',
    cursor?: string,
    limit?: number,
    order?: ListOrder,
    filters: Readonly<Record<string, string>> = {},
  ) =>
    request<OrderPage>(
      'GET',
      `/api/v1/orders${query({ ...filters, status, cursor, limit: status === 'active' ? undefined : (limit ?? 50), order })}`,
    ),
};
