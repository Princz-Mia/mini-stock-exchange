import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { App } from '../App';
import { resetSession } from '../api/client';
import { TOURS } from '../help/tours';
import { fakeServer, signedIn } from '../test/fakeServer';
import { noTrades } from '../test/fakeStream';

const BOTH = ['OPERATOR', 'TRADER'];
const NO_ACCOUNT = { status: 403, problem: { status: 403, code: 'NO_TRADING_ACCOUNT' } };

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
      bestBid: null,
      bestAsk: null,
      lastTradeSequence: '0',
      sourceCommandSequence: '3',
      generatedAt: '2026-11-04T12:00:00Z',
      session: noTrades('100.00'),
    },
  ],
  feed: { state: 'LIVE', lag: 0 },
};

/** Every stream the page opens, and whether it is still open. */
const streams: { url: string; open: boolean }[] = [];
class RecordingEventSource {
  onerror: ((event: Event) => void) | null = null;
  private readonly entry: { url: string; open: boolean };
  constructor(url: string) {
    this.entry = { url, open: true };
    streams.push(this.entry);
  }
  addEventListener() {}
  close() {
    this.entry.open = false;
  }
}
const accountStreamOpen = () => streams.some((stream) => stream.url === '/api/v1/account-events' && stream.open);

/** The operator screen and the terminal, for a user with no trading account yet. */
function both(server = signedIn('operator', BOTH)) {
  return server
    .on('GET /api/v1/market', {
      status: 200,
      json: { state: 'NOT_SET_UP', simulationId: null, openedAt: null, closedAt: null, commandSequence: null },
    })
    .on('GET /api/v1/instruments', { status: 200, json: INSTRUMENTS })
    .on('GET /api/v1/admin/accounts', { status: 200, json: { accounts: [], nextCursor: null } })
    .on('GET /api/v1/portfolio', NO_ACCOUNT)
    .on('GET /api/v1/orders', NO_ACCOUNT);
}

function openAt(path: string) {
  window.history.pushState({}, '', path);
  return render(<App />);
}

const modeSwitch = () => screen.getByRole('group', { name: 'Mode' });
const current = () => within(modeSwitch()).getByRole('link', { current: true });
const navLinks = () =>
  within(screen.getByRole('navigation', { name: 'Main' }))
    .getAllByRole('link')
    .map((link) => link.textContent);

describe('one user with both roles', () => {
  const silent = globalThis.EventSource;
  beforeEach(() => {
    resetSession();
    streams.length = 0;
    vi.stubGlobal('EventSource', RecordingEventSource);
  });
  afterEach(() => {
    resetSession();
    vi.stubGlobal('EventSource', silent);
  });

  it('signs in to the operator screen, operating, with no account stream', async () => {
    let session = false;
    both(
      fakeServer()
        .on('GET /api/v1/me', () => (session ? { status: 200, json: { username: 'operator', roles: BOTH } } : { status: 401 }))
        .on('GET /api/v1/auth/csrf', { status: 200, json: { headerName: 'X-CSRF-TOKEN', token: 't' } })
        .on('POST /api/v1/auth/login', () => {
          session = true;
          return { status: 200, json: { username: 'operator', roles: BOTH } };
        }),
    );
    openAt('/login');

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'operator' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Market' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/operator');
    expect(current()).toHaveTextContent('Operating');
    expect(navLinks()).toEqual(['Operator']);
    expect(accountStreamOpen()).toBe(false);
    // Its password comes from configuration: the name is no link to an account page.
    expect(screen.queryByRole('link', { name: /account/ })).toBeNull();
    expect(screen.getByText('operator')).toBeInTheDocument();
  });

  it('switches to trading and back, the stream following the mode', async () => {
    both();
    openAt('/operator');
    await screen.findByRole('heading', { name: 'Market' });

    fireEvent.click(within(modeSwitch()).getByRole('link', { name: 'Trading' }));

    expect(await screen.findByRole('heading', { name: 'NOVA', level: 1 })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/terminal');
    expect(current()).toHaveTextContent('Trading');
    expect(navLinks()).toEqual(['Terminal', 'Portfolio']);
    expect(accountStreamOpen()).toBe(true);

    fireEvent.click(within(modeSwitch()).getByRole('link', { name: 'Operating' }));

    expect(await screen.findByRole('heading', { name: 'Market' })).toBeInTheDocument();
    expect(current()).toHaveTextContent('Operating');
    expect(accountStreamOpen()).toBe(false);
  });

  it('shows the market without a ticket until it has an account, and says where to get one', async () => {
    both();
    openAt('/terminal');

    expect(await screen.findByRole('heading', { name: 'No trading account' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'NOVA', level: 1 })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /^Order · / })).toBeNull();
    expect(screen.getByRole('link', { name: 'operator screen' })).toHaveAttribute('href', '/operator');
  });

  it('keeps the mode it came from on the help page', async () => {
    both();
    openAt('/terminal');
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });

    window.history.pushState({}, '', '/help');
    window.dispatchEvent(new PopStateEvent('popstate'));

    expect(await screen.findByRole('heading', { level: 1, name: /How/ })).toBeInTheDocument();
    expect(current()).toHaveTextContent('Trading');
    expect(navLinks()).toEqual(['Terminal', 'Portfolio']);
  });

  it("replays the operating mode's tour from the help page", async () => {
    both();
    openAt('/operator');
    await screen.findByRole('heading', { name: 'Market' });
    window.history.pushState({}, '', '/help');
    window.dispatchEvent(new PopStateEvent('popstate'));
    await screen.findByRole('heading', { level: 1, name: /How/ });

    fireEvent.click(screen.getByRole('button', { name: 'Help' }));
    fireEvent.click(screen.getByRole('button', { name: 'Take the tour' }));

    expect(await screen.findByRole('dialog', { name: TOURS.OPERATOR.welcome.title })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/operator');
  });

  it("points the tour at the panel in the ticket's place, not at a missing ticket", async () => {
    both();
    openAt('/terminal');
    await screen.findByRole('heading', { name: 'No trading account' });

    fireEvent.click(screen.getByRole('button', { name: 'Help' }));
    fireEvent.click(screen.getByRole('button', { name: 'Take the tour' }));
    const tour = await screen.findByRole('dialog', { name: TOURS.TRADER.welcome.title });
    fireEvent.click(within(tour).getByRole('button', { name: 'Start the tour' }));
    while (within(tour).queryByRole('heading', { name: /^Order ticket/ }) === null) {
      fireEvent.click(within(tour).getByRole('button', { name: 'Next' }));
    }

    expect(within(tour).getByText(/^Buy or sell/)).not.toHaveTextContent('once there is an instrument');
  });

  it('returns to the page it came from after signing in again', async () => {
    let session = false;
    both(
      fakeServer()
        .on('GET /api/v1/me', () => (session ? { status: 200, json: { username: 'operator', roles: BOTH } } : { status: 401 }))
        .on('GET /api/v1/auth/csrf', { status: 200, json: { headerName: 'X-CSRF-TOKEN', token: 't' } })
        .on('POST /api/v1/auth/login', () => {
          session = true;
          return { status: 200, json: { username: 'operator', roles: BOTH } };
        }),
    );
    openAt('/terminal');

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'operator' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'No trading account' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/terminal');
    expect(current()).toHaveTextContent('Trading');
  });

  it('starts on the operator screen after someone else signed out on a trading page', async () => {
    let user: { username: string; roles: string[] } | null = { username: 'trader', roles: ['TRADER'] };
    both(
      fakeServer()
        .on('GET /api/v1/me', () => (user ? { status: 200, json: user } : { status: 401 }))
        .on('GET /api/v1/auth/csrf', { status: 200, json: { headerName: 'X-CSRF-TOKEN', token: 't' } })
        .on('POST /api/v1/auth/logout', () => {
          user = null;
          return { status: 204 };
        })
        .on('POST /api/v1/auth/login', () => {
          user = { username: 'operator', roles: BOTH };
          return { status: 200, json: user };
        }),
    );
    openAt('/terminal');
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }));
    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'operator' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Market' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/operator');
  });

  it('has no account page: the address goes to the operator screen', async () => {
    both();
    openAt('/account');

    expect(await screen.findByRole('heading', { name: 'Market' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/operator');
  });

  it('starts an unknown address on the operator screen', async () => {
    both();
    openAt('/nowhere');

    expect(await screen.findByRole('heading', { name: 'Market' })).toBeInTheDocument();
  });
});

describe('a user with one role', () => {
  beforeEach(() => resetSession());
  afterEach(() => resetSession());

  it('shows no mode switch to a trader, whose name links to the account page', async () => {
    signedIn()
      .on('GET /api/v1/instruments', { status: 200, json: INSTRUMENTS })
      .on('GET /api/v1/portfolio', NO_ACCOUNT)
      .on('GET /api/v1/orders', NO_ACCOUNT);
    openAt('/nowhere');

    expect(await screen.findByRole('heading', { name: 'No trading account' })).toBeInTheDocument();
    expect(screen.getByText(/An operator creates accounts/)).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Mode' })).toBeNull();
    expect(screen.getByRole('link', { name: 'trader, account' })).toHaveAttribute('href', '/account');
  });

  it('shows no mode switch to an operator', async () => {
    both(signedIn('operator', ['OPERATOR']));
    openAt('/operator');

    expect(await screen.findByRole('heading', { name: 'Market' })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Mode' })).toBeNull();
  });
});
