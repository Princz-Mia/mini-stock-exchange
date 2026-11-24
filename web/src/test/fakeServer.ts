import { vi } from 'vitest';

export interface Recorded {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: unknown;
  /** The request's signal: aborted once the client stopped waiting for it. */
  signal?: AbortSignal;
}

type Reply = { status: number; json?: unknown; problem?: unknown; headers?: Record<string, string> };
type Handler = (request: Recorded) => Reply | Promise<Reply>;

/**
 * Stands in for the backend in component tests: routes `METHOD /path` (query string ignored) to handlers and
 * records every request, so tests can check what the client really sent. A handler may answer later, by returning a
 * promise, for a request that is to stay on its way while the test looks at the page. A request whose signal is
 * aborted fails as a browser's would, whatever its handler is still doing.
 */
export function fakeServer() {
  const routes = new Map<string, Handler>();
  const requests: Recorded[] = [];

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const headers = Object.fromEntries(Object.entries((init?.headers ?? {}) as Record<string, string>));
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined;
    const signal = init?.signal ?? undefined;
    const request: Recorded = { method, url, headers, body, ...(signal === undefined ? {} : { signal }) };
    requests.push(request);
    const handler = routes.get(`${method} ${url.split('?')[0]}`);
    const aborted = new Promise<never>((_, reject) => {
      if (signal?.aborted) {
        reject(signal.reason as Error);
      }
      signal?.addEventListener('abort', () => reject(signal.reason as Error), { once: true });
    });
    // Nobody may be waiting on it once the reply has come.
    aborted.catch(() => undefined);
    const reply =
      handler === undefined
        ? { status: 404, problem: { status: 404, code: 'NOT_MOCKED' } }
        : await Promise.race([Promise.resolve(handler(request)), aborted]);
    if (reply.problem !== undefined) {
      return new Response(JSON.stringify(reply.problem), {
        status: reply.status,
        headers: { 'Content-Type': 'application/problem+json' },
      });
    }
    if (reply.json !== undefined) {
      return new Response(JSON.stringify(reply.json), {
        status: reply.status,
        headers: { 'Content-Type': 'application/json', ...reply.headers },
      });
    }
    return new Response(null, { status: reply.status });
  });
  vi.stubGlobal('fetch', fetchMock);

  return {
    requests,
    on(route: string, handler: Handler | Reply) {
      routes.set(route, typeof handler === 'function' ? handler : () => handler);
      return this;
    },
  };
}

let tokens = 0;

/** The routes every signed-in page needs: a CSRF token (new each time, as login rotates it) and an identity. */
export function signedIn(username = 'trader', roles: string[] = ['TRADER']) {
  return fakeServer()
    .on('GET /api/v1/auth/csrf', () => ({ status: 200, json: { headerName: 'X-CSRF-TOKEN', token: `t${++tokens}` } }))
    .on('GET /api/v1/me', { status: 200, json: { username, roles } });
}
