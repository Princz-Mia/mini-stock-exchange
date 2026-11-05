import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { App } from './App';
import { resetSession } from './api/client';
import { fakeServer, signedIn } from './test/fakeServer';

function openAt(path: string) {
  window.history.pushState({}, '', path);
  return render(<App />);
}

const INSTRUMENTS = {
  instruments: [
    {
      instrumentId: '00000000-0000-0000-0000-000000000001',
      symbol: 'NOVA',
      venueState: 'OPEN',
      referencePrice: '100.00',
      tick: '0.01',
      lotSize: 1,
      minQuantity: 1,
      maxQuantity: 10000,
      maxPrice: '1000000.00',
      lastPrice: null,
      bestBid: { price: '99.50', quantity: 10, orderCount: 1 },
      bestAsk: null,
      lastTradeSequence: '0',
      sourceCommandSequence: '3',
      generatedAt: '2026-11-04T12:00:00Z',
    },
  ],
  feed: { state: 'STALE', lag: 4 },
};

describe('signing in', () => {
  beforeEach(() => resetSession());
  afterEach(() => resetSession());

  it('sends a visitor without a session to the login page', async () => {
    fakeServer().on('GET /api/v1/me', { status: 401 });

    openAt('/portfolio');

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('signs in and lands on the terminal, with the simulation context visible', async () => {
    let session = false;
    let issued = 0;
    fakeServer()
      .on('GET /api/v1/me', () => (session ? { status: 200, json: { username: 'trader', roles: ['TRADER'] } } : { status: 401 }))
      .on('GET /api/v1/auth/csrf', () => ({ status: 200, json: { headerName: 'X-CSRF-TOKEN', token: `t${++issued}` } }))
      .on('POST /api/v1/auth/login', () => {
        session = true;
        return { status: 200, json: { username: 'trader', roles: ['TRADER'] } };
      })
      .on('GET /api/v1/instruments', { status: 200, json: INSTRUMENTS });
    openAt('/login');

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'trader' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'NOVA', level: 1 })).toBeInTheDocument();
    expect(screen.getByText('Simulation · Virtual funds')).toBeInTheDocument();
    expect(screen.getByText('trader')).toBeInTheDocument();
  });

  it('keeps the typed username after wrong credentials', async () => {
    fakeServer()
      .on('GET /api/v1/me', { status: 401 })
      .on('GET /api/v1/auth/csrf', { status: 200, json: { headerName: 'X-CSRF-TOKEN', token: 't' } })
      .on('POST /api/v1/auth/login', {
        status: 401,
        problem: { status: 401, code: 'INVALID_CREDENTIALS', detail: 'Invalid username or password.' },
      });
    openAt('/login');

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'trader' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong username or password.');
    expect(screen.getByLabelText('Username')).toHaveValue('trader');
  });

  it('asks for both fields before sending anything', async () => {
    const server = fakeServer().on('GET /api/v1/me', { status: 401 });
    openAt('/login');

    fireEvent.click(await screen.findByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Enter your username')).toBeInTheDocument();
    expect(server.requests.some((request) => request.method === 'POST')).toBe(false);
  });

  it('takes an operator who opens a trader page to the operator screen', async () => {
    signedIn('operator', ['OPERATOR'])
      .on('GET /api/v1/market', {
        status: 200,
        json: { state: 'NOT_SET_UP', simulationId: null, openedAt: null, commandSequence: null },
      })
      .on('GET /api/v1/instruments', { status: 200, json: { instruments: [], feed: { state: 'LIVE', lag: 0 } } })
      .on('GET /api/v1/admin/accounts', { status: 200, json: { accounts: [], nextCursor: null } });

    openAt('/terminal');

    expect(await screen.findByRole('heading', { name: 'Market' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Operator' })).toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Terminal' })).toBeNull();
  });

  it('tells a user with no role that there is no access', async () => {
    signedIn('nobody', []);

    openAt('/terminal');

    expect(await screen.findByRole('alert')).toHaveTextContent('no access to the exchange');
  });

  it('logs out back to the sign-in page', async () => {
    signedIn()
      .on('GET /api/v1/instruments', { status: 200, json: INSTRUMENTS })
      .on('POST /api/v1/auth/logout', { status: 204 });
    openAt('/terminal');

    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });
});

describe('terminal at a glance', () => {
  beforeEach(() => resetSession());

  it('labels a price with no trades yet as the reference, and says the data is stale', async () => {
    signedIn().on('GET /api/v1/instruments', { status: 200, json: INSTRUMENTS });

    openAt('/terminal');

    const item = await screen.findByRole('button', { name: /^NOVA/ });
    expect(within(item).getByText('ref')).toBeInTheDocument();
    expect(screen.getByText('Market data: Stale · 4 behind')).toBeInTheDocument();
  });

  it('explains an empty market', async () => {
    signedIn().on('GET /api/v1/instruments', {
      status: 200,
      json: { instruments: [], feed: { state: 'LIVE', lag: 0 } },
    });

    openAt('/terminal');

    expect(await screen.findByText(/An operator sets up the market/)).toBeInTheDocument();
  });
});
