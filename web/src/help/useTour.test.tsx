import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { App } from '../App';
import { resetSession } from '../api/client';
import { signedIn } from '../test/fakeServer';
import { TOUR_VERSION, TOURS } from './tours';

const NOVA = '00000000-0000-0000-0000-000000000001';

const INSTRUMENT = {
  instrumentId: NOVA,
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
  sourceCommandSequence: '1',
  generatedAt: '2026-11-04T12:00:00Z',
};

const MARKET = { state: 'OPEN', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', commandSequence: '9' };

/** A trader's exchange, with the tour seen up to `seen`, or the read failing. */
function trader(seen: number | 'fails') {
  return signedIn()
    .on('GET /api/v1/me/tour', seen === 'fails' ? { status: 503, problem: { status: 503 } } : { status: 200, json: { seenVersion: seen } })
    .on('PUT /api/v1/me/tour', (request) => ({ status: 200, json: request.body }))
    .on('GET /api/v1/instruments', { status: 200, json: { instruments: [INSTRUMENT], feed: { state: 'LIVE', lag: 0 } } })
    .on('GET /api/v1/market', { status: 200, json: MARKET })
    .on('GET /api/v1/portfolio', {
      status: 200,
      json: {
        cash: { total: '10000.00', available: '10000.00', reserved: '0.00' },
        holdings: [],
        equity: '10000.00',
        accountVersion: '1',
        asOfCommandSequence: '1',
      },
    })
    .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } })
    .on('GET /api/v1/portfolio/trades', { status: 200, json: { trades: [], nextCursor: null } });
}

function openAt(path: string) {
  window.history.pushState({}, '', path);
  return render(<App />);
}

const tourPuts = (server: ReturnType<typeof trader>) =>
  server.requests.filter((request) => request.method === 'PUT' && request.url === '/api/v1/me/tour');

beforeEach(() => resetSession());
afterEach(() => resetSession());

describe('the first-login tour', () => {
  it('shows once on the start page, and skipping it records the version as seen', async () => {
    const server = trader(0);
    openAt('/terminal');

    expect(await screen.findByRole('dialog', { name: TOURS.TRADER.welcome.title })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Skip tour' }));

    await waitFor(() => expect(tourPuts(server)).toHaveLength(1));
    expect(tourPuts(server)[0]!.body).toEqual({ seenVersion: TOUR_VERSION });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('records the version when the tour is finished too', async () => {
    const server = trader(0);
    openAt('/terminal');
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByRole('button', { name: 'Start the tour' }));
    for (let step = 1; step < TOURS.TRADER.steps.length; step++) {
      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    }
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(tourPuts(server)).toHaveLength(1));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('does not show once this version is seen, nor when the server cannot say', async () => {
    trader(TOUR_VERSION);
    const seen = openAt('/terminal');
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    expect(screen.queryByRole('dialog')).toBeNull();
    seen.unmount();

    trader('fails');
    openAt('/terminal');
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows a newer version to someone who saw an older one', async () => {
    trader(TOUR_VERSION - 1);
    openAt('/terminal');
    expect(await screen.findByRole('dialog', { name: TOURS.TRADER.welcome.title })).toBeInTheDocument();
  });

  it('stays closed for the visit when the server does not take the record', async () => {
    const server = trader(0).on('PUT /api/v1/me/tour', { status: 503, problem: { status: 503 } });
    openAt('/terminal');
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByRole('button', { name: 'Skip tour' }));
    await waitFor(() => expect(tourPuts(server)).toHaveLength(1));
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(screen.getByRole('link', { name: 'Portfolio' }));
    fireEvent.click(screen.getByRole('link', { name: 'Terminal' }));
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('waits for the start page rather than showing over another page', async () => {
    trader(0);
    openAt('/portfolio');
    await screen.findByRole('heading', { name: 'Holdings' });
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(screen.getByRole('link', { name: 'Terminal' }));
    expect(await screen.findByRole('dialog', { name: TOURS.TRADER.welcome.title })).toBeInTheDocument();
  });

  it('replays from Help, from any page, by going to the start page first', async () => {
    trader(TOUR_VERSION);
    openAt('/portfolio');
    await screen.findByRole('heading', { name: 'Holdings' });

    const help = screen.getByRole('button', { name: 'Help' });
    expect(help).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(help);
    expect(help).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Take the tour' }));

    expect(await screen.findByRole('dialog', { name: TOURS.TRADER.welcome.title })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/terminal');
    expect(screen.queryByRole('button', { name: 'Take the tour' })).toBeNull();
  });

  it('closes a replayed tour on Skip, with focus back on Help', async () => {
    const server = trader(TOUR_VERSION);
    openAt('/terminal');
    const help = await screen.findByRole('button', { name: 'Help' });
    fireEvent.click(help);
    fireEvent.click(screen.getByRole('button', { name: 'Take the tour' }));
    await screen.findByRole('dialog');

    fireEvent.click(screen.getByRole('button', { name: 'Skip tour' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(help).toHaveFocus();
    await waitFor(() => expect(tourPuts(server)).toHaveLength(1));
  });

  it('gives focus to Help when the first tour ends, rather than to the top of the page', async () => {
    trader(0);
    openAt('/terminal');
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByRole('button', { name: 'Skip tour' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Help' })).toHaveFocus());
  });

  it('closes for the visit, unrecorded, when the page is left mid-tour, and does not come back on its own', async () => {
    const server = trader(0);
    openAt('/terminal');
    await screen.findByRole('dialog');

    // The browser's Back button, which a modal dialog does not stop.
    act(() => {
      window.history.pushState({}, '', '/portfolio');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await screen.findByRole('heading', { name: 'Holdings' });
    expect(screen.queryByRole('dialog')).toBeNull();

    fireEvent.click(screen.getByRole('link', { name: 'Terminal' }));
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(tourPuts(server)).toHaveLength(0);
  });

  it("shows the operator's own tour on the operator screen", async () => {
    signedIn('operator', ['OPERATOR'])
      .on('GET /api/v1/me/tour', { status: 200, json: { seenVersion: 0 } })
      .on('GET /api/v1/market', { status: 200, json: { state: 'SETUP', simulationId: 's', openedAt: null, commandSequence: '1' } })
      .on('GET /api/v1/instruments', { status: 200, json: { instruments: [], feed: { state: 'LIVE', lag: 0 } } })
      .on('GET /api/v1/admin/accounts', { status: 200, json: { accounts: [], nextCursor: null } })
      .on('GET /api/v1/admin/bots', { status: 200, json: { population: 0, maxPopulation: 1000, maxPerCommand: 100 } })
      .on('GET /api/v1/admin/bot-runs', { status: 200, json: { runs: [], nextCursor: null } });
    openAt('/operator');
    expect(await screen.findByText(TOURS.OPERATOR.welcome.body)).toBeInTheDocument();
  });
});

describe('the Help menu', () => {
  it('closes when the page changes, and when focus moves away from it', async () => {
    trader(TOUR_VERSION);
    openAt('/terminal');
    const help = await screen.findByRole('button', { name: 'Help' });

    fireEvent.click(help);
    expect(help).toHaveAttribute('aria-controls');
    act(() => {
      window.history.pushState({}, '', '/portfolio');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await screen.findByRole('heading', { name: 'Holdings' });
    expect(screen.queryByRole('button', { name: 'Take the tour' })).toBeNull();
    expect(help).toHaveAttribute('aria-expanded', 'false');
    expect(help).not.toHaveAttribute('aria-controls');

    fireEvent.click(help);
    fireEvent.blur(screen.getByRole('button', { name: 'Take the tour' }), {
      relatedTarget: screen.getByRole('link', { name: 'Terminal' }),
    });
    expect(screen.queryByRole('button', { name: 'Take the tour' })).toBeNull();
  });

  it('closes on Escape, giving focus back to its button, and on a click elsewhere', async () => {
    trader(TOUR_VERSION);
    openAt('/terminal');
    const help = await screen.findByRole('button', { name: 'Help' });

    fireEvent.click(help);
    fireEvent.keyDown(screen.getByRole('button', { name: 'Take the tour' }), { key: 'Escape' });
    expect(screen.queryByRole('button', { name: 'Take the tour' })).toBeNull();
    expect(help).toHaveFocus();

    fireEvent.click(help);
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('button', { name: 'Take the tour' })).toBeNull();
  });
});
