import { type APIRequestContext, request } from '@playwright/test';

import type { CsrfToken } from '../src/api/types';

// Runs once, before any test file. The browser tests' traders are added as the operator adds them on the setup
// screen, while the fresh stack's market is still in SETUP: the dev trader, a counterparty to trade against, and a
// newcomer, who stays without an account for the tour test. Every user but the newcomer has seen the tour, so it
// never covers the page a test drives. A run again on the same stack finds them there already.

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`Set ${name}`);
  }
  return value;
}

/** A session that holds its CSRF token, issued after login. */
async function signedIn(username: string, password: string): Promise<{ api: APIRequestContext; csrf: CsrfToken }> {
  const api = await request.newContext({ baseURL: required('E2E_BASE_URL') });
  const before = (await (await api.get('/api/v1/auth/csrf')).json()) as CsrfToken;
  const login = await api.post('/api/v1/auth/login', {
    headers: { [before.headerName]: before.token },
    data: { username, password },
  });
  if (login.status() !== 200) {
    throw new Error(`${username} could not sign in: ${login.status()} ${await login.text()}`);
  }
  const csrf = (await (await api.get('/api/v1/auth/csrf')).json()) as CsrfToken;
  return { api, csrf };
}

/** Marks the tour seen at the highest version, so no test's page is covered by it. */
async function seeTour(username: string, password: string): Promise<void> {
  const { api, csrf } = await signedIn(username, password);
  const seen = await api.put('/api/v1/me/tour', { headers: { [csrf.headerName]: csrf.token }, data: { seenVersion: 1000 } });
  if (seen.status() !== 200) {
    throw new Error(`${username}'s tour: ${seen.status()} ${await seen.text()}`);
  }
  await api.dispose();
}

export default async function globalSetup(): Promise<void> {
  const operatorPassword = required('E2E_OPERATOR_PASSWORD');
  const traderPassword = required('E2E_TRADER_PASSWORD');
  const counterpartyPassword = required('E2E_COUNTERPARTY_PASSWORD');
  const operator = await signedIn('operator', operatorPassword);
  const traders: [string, string][] = [
    ['trader', traderPassword],
    ['counterparty', counterpartyPassword],
    ['newcomer', counterpartyPassword],
  ];
  for (const [username, password] of traders) {
    const added = await operator.api.post('/api/v1/admin/traders', {
      headers: { [operator.csrf.headerName]: operator.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
      data: { username, password },
    });
    // 409 on a second run: the login is taken, or the market has opened since. Signing in below shows the trader and
    // the counterparty are there; the tour test signs in as the newcomer itself.
    if (added.status() !== 201 && added.status() !== 409) {
      throw new Error(`adding ${username}: ${added.status()} ${await added.text()}`);
    }
  }
  await operator.api.dispose();
  await seeTour('operator', operatorPassword);
  await seeTour('trader', traderPassword);
  await seeTour('counterparty', counterpartyPassword);
}
