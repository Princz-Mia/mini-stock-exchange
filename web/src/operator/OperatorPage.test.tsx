import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetSession } from '../api/client';
import type { MarketStatus } from '../api/types';
import { signedIn } from '../test/fakeServer';
import { OperatorPage } from './OperatorPage';

const NOVA = {
  instrumentId: '00000000-0000-0000-0000-000000000001',
  symbol: 'NOVA',
  venueState: 'SETUP',
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
  generatedAt: '2026-11-05T09:00:00Z',
};

function market(state: MarketStatus['state']): MarketStatus {
  return state === 'NOT_SET_UP'
    ? { state, simulationId: null, openedAt: null, commandSequence: null }
    : {
        state,
        simulationId: '10000000-0000-0000-0000-000000000001',
        openedAt: state === 'SETUP' ? null : '2026-11-05T09:30:00Z',
        commandSequence: '7',
      };
}

/** A stateful stand-in: the market moves when a transition is accepted, so refetches show the new state. */
function exchange(initial: MarketStatus['state'], instruments = [NOVA]) {
  const state = { market: market(initial), bots: 0 };
  const server = signedIn('operator', ['OPERATOR'])
    .on('GET /api/v1/market', () => ({ status: 200, json: state.market }))
    .on('GET /api/v1/instruments', { status: 200, json: { instruments, feed: { state: 'LIVE', lag: 0 } } })
    .on('GET /api/v1/admin/accounts', { status: 200, json: { accounts: [], nextCursor: null } })
    .on('GET /api/v1/admin/bots', () => ({
      status: 200,
      json: { population: state.bots, maxPopulation: 1000, maxPerCommand: 100 },
    }));
  return { server, state };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <OperatorPage />
    </QueryClientProvider>,
  );
}

const button = (name: string) => screen.findByRole('button', { name });

describe('the operator screen', () => {
  beforeEach(() => resetSession());

  it('offers only the transition the exchange would allow, and says why the others are not', async () => {
    exchange('SETUP');
    renderPage();

    expect(await button('Open market')).toBeEnabled();
    expect(await button('Halt trading')).toBeDisabled();
    expect(await button('Halt trading')).toHaveAccessibleDescription('Only an open market can be halted.');
    expect(await button('Resume trading')).toHaveAccessibleDescription('Only a halted market can be resumed.');
    expect(screen.getByText('Opening freezes the instruments and every account\'s endowment.')).toBeInTheDocument();
  });

  it('cannot open a market without instruments', async () => {
    exchange('NOT_SET_UP', []);
    renderPage();

    expect(await button('Open market')).toHaveAccessibleDescription(
      'Add an instrument first; that creates the market.',
    );
  });

  it('cannot open a market in setup until it has an instrument', async () => {
    exchange('SETUP', []);
    renderPage();

    expect(await button('Open market')).toBeDisabled();
    expect(await button('Open market')).toHaveAccessibleDescription('Add at least one instrument first.');
  });

  it('opens the market with a key and shows the committed state', async () => {
    const { server, state } = exchange('SETUP');
    server.on('POST /api/v1/admin/market/open', () => {
      state.market = market('OPEN');
      return { status: 200, json: { state: 'OPEN', commandSequence: '8' } };
    });
    renderPage();

    fireEvent.click(await button('Open market'));

    expect(await screen.findByText('The market is open.')).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/admin/market/open');
    expect(sent?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => expect(screen.getByText('Open', { selector: '.state-badge' })).toBeInTheDocument());
    expect(await button('Halt trading')).toBeEnabled();
    expect(screen.getByText('The catalogue is frozen once the market opens.')).toBeInTheDocument();
  });

  it('retries a busy halt under the same key, so it cannot apply twice', async () => {
    const { server, state } = exchange('OPEN');
    let calls = 0;
    server.on('POST /api/v1/admin/market/halt', () => {
      calls += 1;
      if (calls === 1) {
        return { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } };
      }
      state.market = market('HALTED');
      return { status: 200, json: { state: 'HALTED', commandSequence: '9' } };
    });
    renderPage();

    fireEvent.click(await button('Halt trading'));
    expect(await screen.findByText(/Not confirmed/)).toBeInTheDocument();
    expect(await button('Halt trading')).toBeDisabled();
    fireEvent.click(await button('Retry'));

    expect(await screen.findByText('Trading is halted. Cancels still work.')).toBeInTheDocument();
    const keys = server.requests
      .filter((request) => request.url === '/api/v1/admin/market/halt')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('adds an instrument from a starting-scenario suggestion and keeps the input after a refusal', async () => {
    const { server } = exchange('SETUP');
    let calls = 0;
    server.on('POST /api/v1/admin/instruments', () => {
      calls += 1;
      return calls === 1
        ? { status: 409, problem: { status: 409, code: 'SYMBOL_TAKEN', detail: 'An instrument with symbol ORBT already exists' } }
        : { status: 201, json: { instrumentId: 'x', symbol: 'ORBT', referencePrice: '50.00', commandSequence: '9' } };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Add an instrument' });
    expect(within(form).queryByRole('button', { name: 'NOVA 100.00' })).toBeNull();
    fireEvent.click(within(form).getByRole('button', { name: 'ORBT 50.00' }));
    fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));

    expect(await screen.findByText('An instrument with symbol ORBT already exists')).toBeInTheDocument();
    expect(within(form).getByLabelText('Symbol')).toHaveValue('ORBT');
    expect(server.requests.at(-1)?.body).toEqual({ symbol: 'ORBT', referencePrice: '50.00' });

    fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));
    expect(await screen.findByText(/Added ORBT at 50.00/)).toBeInTheDocument();
    const keys = server.requests
      .filter((request) => request.url === '/api/v1/admin/instruments')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('funds a trader with the default endowment for every instrument', async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/accounts', {
      status: 201,
      json: { accountId: 'a', username: 'trader', cash: '100000.00', holdings: [], commandSequence: '9' },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Fund a trader' });
    expect(await within(form).findByLabelText('NOVA shares')).toHaveValue('1000');
    fireEvent.change(within(form).getByLabelText("Trader's username"), { target: { value: 'trader' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Fund account' }));

    expect(await screen.findByText('Funded trader with 100,000.00.')).toBeInTheDocument();
    expect(server.requests.find((request) => request.url === '/api/v1/admin/accounts' && request.method === 'POST')?.body)
      .toEqual({
        username: 'trader',
        cash: '100000.00',
        holdings: [{ instrumentId: NOVA.instrumentId, quantity: 1000 }],
      });
  });

  it('lists funded accounts with what they started with', async () => {
    const { server } = exchange('OPEN');
    server.on('GET /api/v1/admin/accounts', {
      status: 200,
      json: {
        accounts: [
          {
            accountId: 'a',
            username: 'trader',
            kind: 'TRADER',
            cash: '100000.00',
            holdings: [{ instrumentId: NOVA.instrumentId, symbol: 'NOVA', quantity: 1000 }],
          },
          { accountId: 'b', username: 'bot-0001', kind: 'BOT', cash: '5000.00', holdings: [] },
        ],
        nextCursor: null,
      },
    });
    renderPage();

    const row = await screen.findByRole('row', { name: /trader/ });
    expect(row).toHaveTextContent('100,000.00');
    expect(row).toHaveTextContent('NOVA 1,000');
    expect(within(row).queryByText('bot')).toBeNull();
    const bot = screen.getByRole('row', { name: /bot-0001/ });
    expect(within(bot).getByText('bot')).toHaveClass('tag');
    expect(screen.getByText(/Endowments are frozen once the market opens/)).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Fund a trader' })).toBeNull();
  });

  it('creates bots with the default endowment and shows the new population', async () => {
    const { server, state } = exchange('SETUP');
    server.on('POST /api/v1/admin/bots', () => {
      state.bots = 10;
      return {
        status: 201,
        json: { created: 10, firstBot: 'bot-0001', lastBot: 'bot-0010', population: 10, commandSequence: '9' },
      };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    expect(await screen.findByText('0 of 1,000 bots')).toBeInTheDocument();
    expect(within(form).getByLabelText('Number of bots')).toHaveValue('10');
    expect(await within(form).findByLabelText('NOVA shares')).toHaveValue('1000');
    expect(within(form).getByText(/Up to 100 at a time, 1,000 more in all/)).toBeInTheDocument();
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(await screen.findByText('Created bot-0001 to bot-0010. 10 bots in all.')).toBeInTheDocument();
    expect(await screen.findByText('10 of 1,000 bots')).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/admin/bots' && request.method === 'POST');
    expect(sent?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent?.body).toEqual({
      count: 10,
      cash: '100000.00',
      holdings: [{ instrumentId: NOVA.instrumentId, quantity: 1000 }],
    });
  });

  it("shows a refused bot request in the exchange's words and keeps the input", async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/bots', {
      status: 409,
      problem: { status: 409, code: 'BOT_POPULATION_LIMIT', detail: 'At most 1000 bots; 990 exist' },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '20' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(await screen.findByText('At most 1000 bots; 990 exist')).toBeInTheDocument();
    expect(within(form).getByLabelText('Number of bots')).toHaveValue('20');
  });

  it('cannot create bots once the population is full, and says why', async () => {
    const { state } = exchange('SETUP');
    state.bots = 1000;
    renderPage();

    const create = await button('Create bots');
    await waitFor(() => expect(create).toBeDisabled());
    expect(create).toHaveAccessibleDescription('The population is at its limit; no more bots can be created.');
  });

  it('fixes the bot population once the market opens', async () => {
    const { state } = exchange('OPEN');
    state.bots = 12;
    renderPage();

    expect(await screen.findByText('12 of 1,000 bots')).toBeInTheDocument();
    expect(screen.getByText(/the population is fixed after that/)).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Create bots' })).toBeNull();
  });
});
