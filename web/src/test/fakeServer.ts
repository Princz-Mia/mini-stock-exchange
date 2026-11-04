import { vi } from 'vitest';

export interface Recorded {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: unknown;
}

type Reply = { status: number; json?: unknown; problem?: unknown };
type Handler = (request: Recorded) => Reply;

/**
 * Stands in for the backend in component tests: routes `METHOD /path` (query string ignored) to handlers and
 * records every request, so tests can check what the client really sent.
 */
export function fakeServer() {
  const routes = new Map<string, Handler>();
  const requests: Recorded[] = [];

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const headers = Object.fromEntries(Object.entries((init?.headers ?? {}) as Record<string, string>));
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined;
    const request = { method, url, headers, body };
    requests.push(request);
    const handler = routes.get(`${method} ${url.split('?')[0]}`);
    const reply = handler === undefined ? { status: 404, problem: { status: 404, code: 'NOT_MOCKED' } } : handler(request);
    if (reply.problem !== undefined) {
      return new Response(JSON.stringify(reply.problem), {
        status: reply.status,
        headers: { 'Content-Type': 'application/problem+json' },
      });
    }
    if (reply.json !== undefined) {
      return new Response(JSON.stringify(reply.json), {
        status: reply.status,
        headers: { 'Content-Type': 'application/json' },
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
