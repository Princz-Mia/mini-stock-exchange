import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { resetSession } from '../api/client';
import { FillWatch } from '../app/FillNotice';
import { TOURS } from '../help/tours';
import { signedIn } from '../test/fakeServer';
import { fakeStreams, frame, noTrades } from '../test/fakeStream';
import { closedOrdersText } from './OrderTicket';
import { StreamContext, TerminalPage } from './TerminalPage';

const NOVA = '00000000-0000-0000-0000-000000000001';
const ORBIT = '00000000-0000-0000-0000-000000000002';

function instrument(instrumentId: string, symbol: string, lastPrice: string | null) {
  return {
    instrumentId,
    symbol,
    venueState: 'OPEN',
    referencePrice: '100.00',
    tick: '0.01',
    lotSize: 1,
    minQuantity: 1,
    maxQuantity: 10000,
    maxPrice: '1000000.00',
    lastPrice,
    bestBid: null,
    bestAsk: null,
    lastTradeSequence: '0',
    sourceCommandSequence: '1',
    generatedAt: '2026-11-04T12:00:00Z',
    session: noTrades('100.00'),
  };
}

const PORTFOLIO = {
  cash: { total: '10000.00', available: '9500.00', reserved: '500.00' },
  holdings: [
    {
      instrumentId: NOVA,
      symbol: 'NOVA',
      total: 100,
      available: 90,
      reserved: 10,
      mark: { price: '100.00', source: 'REFERENCE', at: null },
      value: '10000.00',
      changePercent: '0.00',
      weight: '50.00',
      averageCost: '100.00',
      unrealized: '0.00',
      unrealizedPercent: '0.00',
    },
  ],
  equity: '20000.00',
  accountVersion: '1',
  asOfCommandSequence: '1',
  costBasis: '10000.00',
  unrealized: '0.00',
  unrealizedPercent: '0.00',
};

const OPEN_ORDER = {
  orderId: '00000000-0000-0000-0000-0000000000cc',
  instrumentId: NOVA,
  symbol: 'NOVA',
  side: 'SELL',
  type: 'LIMIT',
  timeInForce: 'GTC',
  limitPrice: '105.00',
  protectionPrice: null,
  requestedQuantity: 10,
  filledQuantity: 0,
  workingQuantity: 10,
  canceledQuantity: 0,
  state: 'OPEN',
  reason: null,
  version: '1',
  createdAt: '2026-11-04T12:00:00Z',
  updatedAt: '2026-11-04T12:00:00Z',
};

function market() {
  return signedIn()
    .on('GET /api/v1/instruments', {
      status: 200,
      json: { instruments: [instrument(NOVA, 'NOVA', null), instrument(ORBIT, 'ORBIT', '40.00')], feed: { state: 'LIVE', lag: 0 } },
    })
    .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
    .on('GET /api/v1/orders', { status: 200, json: { orders: [OPEN_ORDER], nextCursor: null } });
}

function renderTerminal(
  at = '/terminal',
  watch: ((order: { orderId: string; filledQuantity: number }) => void) | null = null,
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  const streams = fakeStreams();
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[at]}>
        <StreamContext.Provider value={streams.create}>
          <FillWatch.Provider value={watch}>
            <TerminalPage />
          </FillWatch.Provider>
        </StreamContext.Provider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return Object.assign(view, streams);
}

describe('terminal', () => {
  beforeEach(() => resetSession());

  it('pauses new orders while the market is halted, but still lets the trader cancel', async () => {
    const server = market().on('GET /api/v1/market', {
      status: 200,
      json: { state: 'HALTED', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', closedAt: null, commandSequence: '9' },
    });
    renderTerminal();

    const ticket = await screen.findByRole('region', { name: 'Order · NOVA' });
    const buy = within(ticket).getByRole('button', { name: 'Buy NOVA' });
    await waitFor(() => expect(buy).toBeDisabled());
    expect(buy).toHaveAccessibleDescription(
      'Trading is halted. New orders are paused; you can still cancel open orders.',
    );
    expect(screen.getByText('Trading halted')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'What is halted?' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Cancel sell NOVA at 105.00' })).toBeEnabled();
    expect(server.requests.some((request) => request.method === 'POST')).toBe(false);
  });

  it("has a part for every step of the trader's tour, but the Portfolio link, which is in the top bar", async () => {
    market().on('GET /api/v1/market', {
      status: 200,
      json: { state: 'OPEN', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', closedAt: null, commandSequence: '9' },
    });
    renderTerminal();
    await screen.findByRole('region', { name: 'Order · NOVA' });
    for (const step of TOURS.TRADER.steps.filter((each) => each.title !== 'Portfolio')) {
      expect(document.querySelector(step.anchor), step.title).not.toBeNull();
    }
  });

  it('explains a halt beside its badge, but not a market that has not opened yet', async () => {
    market().on('GET /api/v1/market', {
      status: 200,
      json: { state: 'SETUP', simulationId: 's', openedAt: null, closedAt: null, commandSequence: '2' },
    });
    renderTerminal();
    await screen.findByRole('region', { name: 'Order · NOVA' });
    expect(await screen.findByText('Not open yet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'What is halted?' })).toBeNull();
  });

  const CLOSED = {
    state: 'CLOSED',
    simulationId: 's',
    openedAt: '2026-11-04T09:00:00Z',
    closedAt: '2026-11-04T17:00:00Z',
    commandSequence: '12',
  };
  const OPEN = { state: 'OPEN', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', closedAt: null, commandSequence: '9' };
  const CANCELED_BY_CLOSE = {
    ...OPEN_ORDER,
    state: 'CANCELED',
    reason: 'MARKET_CLOSED',
    workingQuantity: 0,
    canceledQuantity: 10,
    updatedAt: '2026-11-04T17:00:00Z',
  };
  /** The trader's orders as the exchange lists them once a close has cancelled the open one. */
  const afterClose = (request: { url: string }) => ({
    status: 200,
    json: { orders: request.url.includes('status=active') ? [] : [CANCELED_BY_CLOSE], nextCursor: null },
  });

  it("gives the ticket's place to a statement that the market has closed, and takes no order", async () => {
    const server = market().on('GET /api/v1/market', { status: 200, json: CLOSED }).on('GET /api/v1/orders', afterClose);
    renderTerminal();

    const closed = await screen.findByRole('region', { name: 'Market closed' });
    expect(within(closed).getByRole('status')).toHaveTextContent(
      'The market closed on 4 Nov 2026, 17:00:00 UTC and takes no more orders.',
    );
    expect(closed).toHaveTextContent(
      'Any order of yours that was still open was cancelled, and what it had reserved was released.',
    );
    expect(within(closed).getByRole('link', { name: 'portfolio' })).toHaveAttribute('href', '/portfolio');
    // No ticket at all: nothing to type into, nothing to press.
    expect(screen.queryByRole('region', { name: /^Order ·/ })).toBeNull();
    expect(screen.queryByLabelText('Shares')).toBeNull();
    expect(screen.queryByRole('button', { name: /^(Buy|Sell)/ })).toBeNull();
    // It stands where the ticket stood, which is where the tour points.
    expect(document.querySelector('section.ticket')).toBe(closed);

    expect(screen.getByText('Market closed', { selector: '.state-badge' })).toBeInTheDocument();
    // A close is not a halt: the tip that explains halts stays away, and nothing says trading will come back.
    expect(screen.queryByText('Trading halted')).toBeNull();
    expect(screen.queryByRole('button', { name: 'What is halted?' })).toBeNull();
    expect(screen.getByRole('button', { name: 'What is closed?' })).toBeInTheDocument();
    // The market itself stays to read.
    expect(screen.getByRole('heading', { name: 'NOVA', level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Watchlist' })).toBeInTheDocument();
    expect(server.requests.some((request) => request.method === 'POST')).toBe(false);
  });

  it('says the close cancelled what was open, and lists those orders as cancelled by it', async () => {
    market().on('GET /api/v1/market', { status: 200, json: CLOSED }).on('GET /api/v1/orders', afterClose);
    renderTerminal();

    expect(await screen.findByRole('tab', { name: 'Open orders (0)' })).toBeInTheDocument();
    expect(
      await screen.findByText(
        'No open orders. The market has closed: any order that was still open was cancelled by the close, and is listed under Recent orders.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Cancel / })).toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: 'Recent orders' }));
    const recent = await screen.findByRole('table', { name: 'Recent orders' });
    expect(within(recent).getByRole('row', { name: /NOVA/ })).toHaveTextContent('Canceled · the market closed');
  });

  it('does not say the close cancelled orders still listed as open, and offers no cancel of them', async () => {
    // Closed, and the close has not got to this trader's order: it is still at it, or the exchange stopped half way.
    const server = market().on('GET /api/v1/market', { status: 200, json: CLOSED });
    renderTerminal();

    const closed = await screen.findByRole('region', { name: 'Market closed' });
    await waitFor(() =>
      expect(closed).toHaveTextContent(
        '1 order of yours is still open, with what it reserves. The close cancels it: it is doing so now, or does when the exchange is restarted if it has stopped.',
      ),
    );
    expect(closed).not.toHaveTextContent(/was cancelled|was released|as the market left them/);
    expect(closed).toHaveTextContent('Your portfolio and your history of orders and trades stay to read.');
    expect(within(closed).getByRole('link', { name: 'portfolio' })).toHaveAttribute('href', '/portfolio');

    expect(screen.getByRole('tab', { name: 'Open orders (1)' })).toBeInTheDocument();
    const open = screen.getByRole('table', { name: 'Open orders' });
    expect(within(open).getByRole('row', { name: /NOVA/ })).toHaveTextContent('Waiting for the close to cancel it');
    expect(
      screen.getByText(/^The market has closed, and the close cancels these: it is doing so now, or does when the exchange is restarted/),
    ).toBeInTheDocument();
    // A closed market takes no cancel, so none is offered: it could only fail.
    expect(screen.queryByRole('button', { name: /^Cancel / })).toBeNull();
    expect(screen.queryByText(/any order that was still open was cancelled by the close/)).toBeNull();
    expect(server.requests.some((request) => request.method === 'POST')).toBe(false);
  });

  it('says nothing of what the close did to orders it could not read', async () => {
    market()
      .on('GET /api/v1/market', { status: 200, json: CLOSED })
      .on('GET /api/v1/orders', { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } });
    renderTerminal();

    const closed = await screen.findByRole('region', { name: 'Market closed' });
    // What a close does, in place of what this one has done: the list that would tell has not been read.
    expect(closed).toHaveTextContent(
      'The close cancels any order of yours that was still open, and releases what it had reserved.',
    );
    expect(await screen.findByText('No open orders.')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByText(/was cancelled|still open, with what/)).toBeNull(),
    );
  });

  it('says what the close did with open orders by how many are still listed', () => {
    // Before the list is read, what a close does; that they were cancelled only once none is left.
    expect(closedOrdersText(undefined)).toBe(
      'The close cancels any order of yours that was still open, and releases what it had reserved.',
    );
    expect(closedOrdersText(0)).toBe(
      'Any order of yours that was still open was cancelled, and what it had reserved was released.',
    );
    expect(closedOrdersText(1)).toMatch(/^1 order of yours is still open, with what it reserves\. The close cancels it: /);
    expect(closedOrdersText(1200)).toMatch(
      /^1,200 orders of yours are still open, with what they reserve\. The close cancels them: /,
    );
  });

  it('says nothing of a close under an empty list of open orders while the market is open', async () => {
    signedIn()
      .on('GET /api/v1/market', { status: 200, json: OPEN })
      .on('GET /api/v1/instruments', {
        status: 200,
        json: { instruments: [instrument(NOVA, 'NOVA', null)], feed: { state: 'LIVE', lag: 0 } },
      })
      .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    renderTerminal();

    await screen.findByRole('region', { name: 'Order · NOVA' });
    expect(await screen.findByText('No open orders.')).toBeInTheDocument();
    expect(screen.queryByText(/The market has closed/)).toBeNull();
  });

  it("reads the trader's orders and balances again as soon as the market is seen to have closed", async () => {
    let status = OPEN as typeof OPEN | typeof CLOSED;
    const server = market()
      .on('GET /api/v1/market', () => ({ status: 200, json: status }))
      .on('GET /api/v1/orders', (request) =>
        status === CLOSED ? afterClose(request) : { status: 200, json: { orders: [OPEN_ORDER], nextCursor: null } },
      );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderTerminal('/terminal', null, client);
    expect(await screen.findByRole('button', { name: 'Cancel sell NOVA at 105.00' })).toBeEnabled();
    await screen.findByRole('region', { name: 'Order · NOVA' });
    const reads = (path: string) => server.requests.filter((request) => request.url.split('?')[0] === path).length;
    const before = { orders: reads('/api/v1/orders'), portfolio: reads('/api/v1/portfolio') };

    status = CLOSED;
    await client.invalidateQueries({ queryKey: ['market'] });

    expect(await screen.findByRole('region', { name: 'Market closed' })).toBeInTheDocument();
    // At once, not at the next poll five seconds on: the order the close cancelled is no longer offered for cancelling.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Cancel sell NOVA at 105.00' })).toBeNull(), {
      timeout: 900,
    });
    expect(reads('/api/v1/orders')).toBeGreaterThan(before.orders);
    await waitFor(() => expect(reads('/api/v1/portfolio')).toBeGreaterThan(before.portfolio), { timeout: 900 });
  });

  it('keeps an order whose outcome is not known retryable through the close, then says what became of it', async () => {
    let status = OPEN as typeof OPEN | typeof CLOSED;
    let busy = true;
    const server = market()
      .on('GET /api/v1/market', () => ({ status: 200, json: status }))
      .on('POST /api/v1/orders', () =>
        busy
          ? { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY' } }
          : {
              status: 422,
              problem: { status: 422, code: 'MARKET_CLOSED', orderId: '00000000-0000-0000-0000-0000000000ad', commandSequence: '13' },
            },
      );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderTerminal('/terminal', null, client);
    const ticket = await screen.findByRole('region', { name: 'Order · NOVA' });
    await waitFor(() => expect(within(ticket).getByRole('button', { name: 'Buy NOVA' })).toBeEnabled());
    fireEvent.click(within(ticket).getByRole('button', { name: 'Buy NOVA' }));
    const retry = await screen.findByRole('button', { name: 'Retry the same order' });

    // The market closes while the order's fate is open: the ticket stays, for that one order only.
    status = CLOSED;
    busy = false;
    await client.invalidateQueries({ queryKey: ['market'] });
    expect(await screen.findByText('Market closed', { selector: '.state-badge' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Order · NOVA' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Market closed' })).toBeNull();
    expect(screen.getByLabelText('Shares')).toBeDisabled();

    fireEvent.click(retry);
    const closed = await screen.findByRole('region', { name: 'Market closed' });
    expect(within(closed).getByRole('alert')).toHaveTextContent('Rejected: The market has closed and takes no more orders.');
    expect(screen.queryByRole('region', { name: 'Order · NOVA' })).toBeNull();
    const keys = server.requests
      .filter((request) => request.method === 'POST' && request.url === '/api/v1/orders')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('shows no answer from before the close with the statement that the market closed', async () => {
    const accepted = {
      status: 201,
      json: {
        orderId: '00000000-0000-0000-0000-0000000000aa',
        state: 'OPEN',
        reason: null,
        filledQuantity: 0,
        workingQuantity: 1,
        canceledQuantity: 0,
        commandSequence: '10',
      },
    };
    const rejected = {
      status: 422,
      problem: { status: 422, code: 'INSUFFICIENT_CASH', orderId: '00000000-0000-0000-0000-0000000000ab', commandSequence: '10' },
    };
    const busy = { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY' } };
    const cases = [
      { answers: [accepted], said: 'Accepted and resting: 1 shares working, none filled yet.' },
      { answers: [rejected], said: 'Rejected: Not enough available cash for this order at its price limit.' },
      // Unresolved for a while, and settled by its retry while the market was still open: also long before the close.
      { answers: [busy, accepted], said: 'Accepted and resting: 1 shares working, none filled yet.' },
    ];
    for (const { answers, said } of cases) {
      let status = OPEN as typeof OPEN | typeof CLOSED;
      let sent = 0;
      market()
        .on('GET /api/v1/market', () => ({ status: 200, json: status }))
        .on('GET /api/v1/orders', (request) =>
          status === CLOSED ? afterClose(request) : { status: 200, json: { orders: [], nextCursor: null } },
        )
        .on('POST /api/v1/orders', () => answers[sent++]!);
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      const view = renderTerminal('/terminal', null, client);
      const ticket = await view.findByRole('region', { name: 'Order · NOVA' });
      await waitFor(() => expect(within(ticket).getByRole('button', { name: 'Buy NOVA' })).toBeEnabled());
      fireEvent.click(within(ticket).getByRole('button', { name: 'Buy NOVA' }));
      if (answers.length > 1) {
        fireEvent.click(await view.findByRole('button', { name: 'Retry the same order' }));
      }
      expect(await view.findByText(said)).toBeInTheDocument();

      // The trader stays on the instrument, and the market closes an hour later. The order that rested was cancelled
      // by the close since; what the ticket said of it when it was placed is no longer so.
      status = CLOSED;
      await client.invalidateQueries({ queryKey: ['market'] });
      const closed = await view.findByRole('region', { name: 'Market closed' });
      expect(view.queryByText(said)).toBeNull();
      expect(within(closed).queryByRole('alert')).toBeNull();
      expect(within(closed).getAllByRole('status')).toHaveLength(1);
      view.unmount();
    }
  });

  it('tells a user who never had an account that the market closed, not how to get one', async () => {
    const noAccount = { status: 403, problem: { status: 403, code: 'NO_TRADING_ACCOUNT' } };
    signedIn()
      .on('GET /api/v1/market', { status: 200, json: CLOSED })
      .on('GET /api/v1/instruments', {
        status: 200,
        json: { instruments: [instrument(NOVA, 'NOVA', null)], feed: { state: 'LIVE', lag: 0 } },
      })
      .on('GET /api/v1/portfolio', noAccount)
      .on('GET /api/v1/orders', noAccount);
    renderTerminal();

    const closed = await screen.findByRole('region', { name: 'Market closed' });
    expect(closed).toHaveTextContent('The market closed on 4 Nov 2026, 17:00:00 UTC and takes no more orders.');
    expect(closed).toHaveTextContent('You had no trading account in it.');
    expect(closed).not.toHaveTextContent('Any order of yours');
    expect(screen.queryByRole('region', { name: 'No trading account' })).toBeNull();
  });

  it('takes orders once the market is open', async () => {
    market().on('GET /api/v1/market', {
      status: 200,
      json: { state: 'OPEN', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', closedAt: null, commandSequence: '9' },
    });
    renderTerminal();

    const ticket = await screen.findByRole('region', { name: 'Order · NOVA' });
    await waitFor(() => expect(screen.queryByText('Trading halted')).toBeNull());
    expect(within(ticket).getByRole('button', { name: 'Buy NOVA' })).toBeEnabled();
    // Tips on the terms the ticket and the orders use; none on a halt that is not happening.
    expect(within(ticket).getByRole('button', { name: 'What are available and reserved?' })).toBeInTheDocument();
    expect(within(ticket).getByRole('button', { name: 'What are limit order and protected market order?' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'What is partial fill?' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'What is halted?' })).toBeNull();
  });

  it("lists the instruments of the simulation the exchange's status names, and of no other", async () => {
    const server = market().on('GET /api/v1/market', {
      status: 200,
      json: { state: 'OPEN', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', closedAt: null, commandSequence: '9' },
    });
    renderTerminal();

    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    const asked = server.requests.filter((request) => request.url.split('?')[0] === '/api/v1/instruments');
    expect(asked.length).toBeGreaterThan(0);
    expect(asked.every((request) => request.url === '/api/v1/instruments?simulationId=s')).toBe(true);
  });

  it("shows Market Data's own current simulation when the exchange cannot say which one it is", async () => {
    const server = market().on('GET /api/v1/market', { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } });
    renderTerminal();

    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    const asked = server.requests.filter((request) => request.url.split('?')[0] === '/api/v1/instruments');
    expect(asked.map((request) => request.url)).toEqual(['/api/v1/instruments']);
  });

  it('says the book and tape are not known yet until the first market frame, rather than empty', async () => {
    market();
    const streams = renderTerminal();
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });

    expect(screen.getAllByText('Waiting for the live market…')).toHaveLength(2);
    expect(screen.queryByText('No trades yet.')).not.toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Bids' })).not.toBeInTheDocument();

    act(() => streams.latest()?.emit('market', frame(NOVA, '5', { recentTrades: [] })));

    expect(screen.queryByText('Waiting for the live market…')).not.toBeInTheDocument();
    expect(screen.getByText('No trades yet.')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Bids' })).toBeInTheDocument();
  });

  it('streams the first instrument and shows its book, tape and last price', async () => {
    market();
    const streams = renderTerminal();
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    expect(streams.latest()?.url).toContain(NOVA);

    act(() =>
      streams.latest()?.emit(
        'market',
        frame(NOVA, '5', {
          lastPrice: '100.25',
          recentTrades: [
            {
              tradeId: '00000000-0000-0000-0000-0000000000d1',
              tradeSequence: '1',
              price: '100.25',
              quantity: 3,
              aggressorSide: 'SELL',
              executedAt: '2026-11-04T12:00:05Z',
            },
          ],
        }),
      ),
    );

    expect(within(screen.getByRole('table', { name: 'Bids' })).getByText('99.50')).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Asks' })).getByText('100.50')).toBeInTheDocument();
    expect(screen.getByText('Spread 1.00')).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Recent trades' })).getByText('Sell')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
  });

  it('shows the session figures from the instrument list, then from the live frame once it comes', async () => {
    market();
    const streams = renderTerminal();
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    const figure = (label: string) =>
      within(document.querySelector<HTMLElement>('.instrument-figures')!).getByText(label).nextElementSibling?.textContent;
    // Before the frame: the list's figures (no trades yet) and its best bid and ask (none).
    expect(figure('Change')).toBe('—');
    expect(figure('Reference')).toBe('100.00');
    expect(figure('Bid')).toBe('—');

    act(() =>
      streams.latest()?.emit(
        'market',
        frame(NOVA, '6', {
          session: {
            ...noTrades('100.00'),
            last: '98.00',
            change: '-2.00',
            changePercent: '-2.00',
            high: '101.00',
            low: '98.00',
            volume: 40,
            tradeCount: 3,
            vwap: '99.20',
          },
        }),
      ),
    );

    expect(figure('Change')).toBe('▼ down −2.00 (−2.00%)');
    expect(figure('Bid')).toBe('99.50');
    expect(figure('Ask')).toBe('100.50');
    expect(figure('Spread')).toBe('1.00');
    expect(figure('Volume')).toBe('40');
    expect(figure('VWAP')).toBe('99.20');
  });

  it('keeps to the polled list when the last frame is older than it, as after the stream dropped', async () => {
    market();
    const streams = renderTerminal();
    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    const figure = (label: string) =>
      within(document.querySelector<HTMLElement>('.instrument-figures')!).getByText(label).nextElementSibling?.textContent;

    // The list is at revision 1; a frame from revision 0 is behind it, whatever figures it carries.
    act(() =>
      streams.latest()?.emit(
        'market',
        frame(NOVA, '0', { lastPrice: '97.00', session: { ...noTrades('100.00'), last: '97.00', change: '-3.00', changePercent: '-3.00' } }),
      ),
    );

    expect(figure('Change')).toBe('—');
    expect(figure('Bid')).toBe('—');
    expect(document.querySelector('.last-price')).toHaveTextContent('100.00 ref');
  });

  it('switches the stream when another instrument is chosen', async () => {
    market();
    const streams = renderTerminal();
    fireEvent.click(await screen.findByRole('button', { name: /^ORBIT/ }));

    await screen.findByRole('heading', { name: 'ORBIT', level: 1 });
    expect(streams.opened[0]?.closed).toBe(true);
    expect(streams.latest()?.url).toContain(ORBIT);
  });

  it('shows the instrument the address names when this market lists it', async () => {
    market();
    const streams = renderTerminal(`/terminal?instrument=${ORBIT}`);

    await screen.findByRole('heading', { name: 'ORBIT', level: 1 });
    expect(streams.latest()?.url).toContain(ORBIT);
    expect(screen.getByRole('button', { name: /^ORBIT/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText(/is not part of this market/)).toBeNull();
  });

  it('falls back to the first listed instrument when the address names one this market does not list', async () => {
    // A bookmark from a simulation that has closed, or a tab left open over a change of simulation.
    const GONE = '00000000-0000-0000-0000-0000000000ee';
    market();
    const streams = renderTerminal(`/terminal?instrument=${GONE}&chart=line`);

    await screen.findByRole('heading', { name: 'NOVA', level: 1 });
    expect(
      screen.getByText('The instrument in this address is not part of this market. Showing NOVA instead.'),
    ).toHaveAttribute('role', 'status');
    // The whole terminal is NOVA's: the stream, the ticket, and the one pressed in the watchlist.
    expect(streams.opened.map((stream) => stream.url).every((url) => url.includes(NOVA))).toBe(true);
    expect(streams.opened.length).toBeGreaterThan(0);
    expect(await screen.findByRole('region', { name: 'Order · NOVA' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^NOVA/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /^ORBIT/ })).toHaveAttribute('aria-pressed', 'false');

    // Choosing one puts it in the address, and the note goes.
    fireEvent.click(screen.getByRole('button', { name: /^ORBIT/ }));
    await screen.findByRole('heading', { name: 'ORBIT', level: 1 });
    expect(screen.queryByText(/is not part of this market/)).toBeNull();
  });

  it('keeps the ticket and its unresolved order when the status is read after the list, for the same simulation', async () => {
    // The exchange is flapping: its status cannot be read at first, so the page starts from Market Data's own list.
    let reachable = false;
    const lists: string[] = [];
    const server = signedIn()
      .on('GET /api/v1/market', () =>
        reachable ? { status: 200, json: OPEN } : { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } },
      )
      .on('GET /api/v1/instruments', (request) => {
        lists.push(request.url);
        return {
          status: 200,
          json: { simulationId: 's', instruments: [instrument(NOVA, 'NOVA', null)], feed: { state: 'LIVE', lag: 0 } },
        };
      })
      .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } })
      .on('POST /api/v1/orders', { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY' } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderTerminal('/terminal', null, client);
    const ticket = await screen.findByRole('region', { name: 'Order · NOVA' });
    fireEvent.change(within(ticket).getByLabelText('Shares'), { target: { value: '7' } });
    fireEvent.click(within(ticket).getByRole('button', { name: 'Buy NOVA' }));
    const retry = await screen.findByRole('button', { name: 'Retry the same order' });
    expect(lists).toEqual(['/api/v1/instruments']);

    // The next read of the status succeeds, and names the simulation the list was of.
    reachable = true;
    await client.invalidateQueries({ queryKey: ['market'] });
    await waitFor(() => expect(lists).toContain('/api/v1/instruments?simulationId=s'));

    // Nothing was taken down to wait for the list by id: the very same ticket, with what was typed and the order's key.
    expect(screen.queryByText('Loading markets…')).toBeNull();
    expect(screen.getByRole('region', { name: 'Order · NOVA' })).toBe(ticket);
    expect(screen.getByRole('button', { name: 'Retry the same order' })).toBe(retry);
    expect(screen.getByLabelText('Shares')).toHaveValue('7');
    fireEvent.click(retry);
    await waitFor(() =>
      expect(server.requests.filter((request) => request.method === 'POST' && request.url === '/api/v1/orders')).toHaveLength(2),
    );
    const keys = server.requests
      .filter((request) => request.method === 'POST' && request.url === '/api/v1/orders')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys[1]).toBe(keys[0]);
  });

  it('says there is nothing to trade when the market lists no instrument, instead of showing nothing', async () => {
    signedIn()
      .on('GET /api/v1/instruments', { status: 200, json: { instruments: [], feed: { state: 'LIVE', lag: 0 } } })
      .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    const streams = renderTerminal();

    expect(await screen.findByText('This market has no instruments yet, so there is nothing to trade.')).toHaveAttribute(
      'role',
      'status',
    );
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
    expect(screen.queryByRole('region', { name: /^Order/ })).toBeNull();
    expect(streams.opened).toHaveLength(0);
    // The orders stay, whatever the market lists.
    expect(screen.getByRole('tab', { name: 'Open orders (0)' })).toBeInTheDocument();
  });

  it('says so when the address names an instrument and the market lists none', async () => {
    signedIn()
      .on('GET /api/v1/instruments', { status: 200, json: { instruments: [], feed: { state: 'LIVE', lag: 0 } } })
      .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    renderTerminal(`/terminal?instrument=${NOVA}`);

    expect(
      await screen.findByText(
        'The instrument in this address is not part of this market, and the market has no instruments yet.',
      ),
    ).toBeInTheDocument();
  });

  it('keeps the chart settings and asks for the new instrument bars when another instrument is chosen', async () => {
    const server = market();
    renderTerminal('/terminal?chart=line&range=30m&bars=5m');
    fireEvent.click(await screen.findByRole('button', { name: /^ORBIT/ }));
    await screen.findByRole('heading', { name: 'ORBIT', level: 1 });

    const chart = screen.getByRole('region', { name: 'Price' });
    for (const [group, name] of [
      ['Chart type', 'Line'],
      ['Range', '30 min'],
      ['Bar size', '5 min'],
    ] as const) {
      expect(within(within(chart).getByRole('group', { name: group })).getByRole('button', { name })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
    }
    await waitFor(() =>
      expect(server.requests.some((request) => request.url.startsWith(`/api/v1/instruments/${ORBIT}/candles?`))).toBe(true),
    );
  });

  it('tells the fill notices about an order that rests, with what filled on arrival, and not one that is done', async () => {
    let answer = { state: 'PARTIALLY_FILLED', filledQuantity: 4, workingQuantity: 6, canceledQuantity: 0 };
    market().on('POST /api/v1/orders', () => ({
      status: 201,
      json: { orderId: '00000000-0000-0000-0000-0000000000aa', reason: null, commandSequence: '9', ...answer },
    }));
    const watch = vi.fn();
    renderTerminal('/terminal', watch);
    fireEvent.change(await screen.findByLabelText('Shares'), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText('Limit price'), { target: { value: '100.25' } });
    fireEvent.click(screen.getByRole('button', { name: 'Buy NOVA' }));
    await screen.findByText('Partly filled: 4 filled, 6 still working.');
    expect(watch).toHaveBeenCalledExactlyOnceWith({ orderId: '00000000-0000-0000-0000-0000000000aa', filledQuantity: 4 });

    answer = { state: 'FILLED', filledQuantity: 10, workingQuantity: 0, canceledQuantity: 0 };
    fireEvent.click(screen.getByRole('button', { name: 'Buy NOVA' }));
    await screen.findByText('Filled: 10 shares.');
    expect(watch).toHaveBeenCalledTimes(1);
  });

  it('shows the maximum spend beside the available balances, and sends the order with a key', async () => {
    const server = market().on('POST /api/v1/orders', {
      status: 201,
      json: {
        orderId: '00000000-0000-0000-0000-0000000000aa',
        state: 'PARTIALLY_FILLED',
        reason: null,
        filledQuantity: 4,
        workingQuantity: 6,
        canceledQuantity: 0,
        commandSequence: '9',
      },
    });
    renderTerminal();
    fireEvent.change(await screen.findByLabelText('Shares'), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText('Limit price'), { target: { value: '100.25' } });

    expect(await screen.findByText('9,500.00')).toBeInTheDocument();
    expect(screen.getByText('90')).toBeInTheDocument();
    expect(screen.getByText('1,002.50')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Buy NOVA' }));

    expect(await screen.findByText('Partly filled: 4 filled, 6 still working.')).toBeInTheDocument();
    const sent = server.requests.find((request) => request.method === 'POST' && request.url === '/api/v1/orders');
    expect(sent?.body).toEqual({
      instrumentId: NOVA,
      side: 'BUY',
      type: 'LIMIT',
      timeInForce: 'GTC',
      quantity: 10,
      limitPrice: '100.25',
    });
    expect(sent?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() =>
      expect(server.requests.filter((request) => request.url === '/api/v1/portfolio').length).toBeGreaterThan(1),
    );
  });

  it('shows a rejection inline and keeps what was typed', async () => {
    market().on('POST /api/v1/orders', {
      status: 422,
      problem: { status: 422, code: 'INSUFFICIENT_CASH', orderId: '00000000-0000-0000-0000-0000000000ab', commandSequence: '4' },
    });
    renderTerminal();
    fireEvent.change(await screen.findByLabelText('Shares'), { target: { value: '500' } });
    fireEvent.click(screen.getByRole('button', { name: 'Buy NOVA' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Not enough available cash');
    expect(screen.getByLabelText('Shares')).toHaveValue('500');
  });

  it('says the market has closed when an order reaches it just after the close', async () => {
    // The page still shows the market open: the close came after its last poll.
    market().on('POST /api/v1/orders', {
      status: 422,
      problem: { status: 422, code: 'MARKET_CLOSED', orderId: '00000000-0000-0000-0000-0000000000ac', commandSequence: '9' },
    });
    renderTerminal();
    fireEvent.click(await screen.findByRole('button', { name: 'Buy NOVA' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The market has closed and takes no more orders.');
  });

  it('offers to retry the very same order when the exchange is busy, and locks the fields', async () => {
    market().on('POST /api/v1/orders', { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY' } });
    renderTerminal();
    fireEvent.click(await screen.findByRole('button', { name: 'Buy NOVA' }));

    expect(await screen.findByRole('button', { name: 'Retry the same order' })).toBeInTheDocument();
    expect(screen.getByLabelText('Shares')).toBeDisabled();
  });

  it('says when orders come too fast, and offers to retry the very same order', async () => {
    market().on('POST /api/v1/orders', { status: 429, problem: { status: 429, code: 'RATE_LIMITED' } });
    renderTerminal();
    fireEvent.click(await screen.findByRole('button', { name: 'Buy NOVA' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many orders in a moment');
    expect(screen.getByRole('button', { name: 'Retry the same order' })).toBeInTheDocument();
    expect(screen.getByLabelText('Shares')).toBeDisabled();
  });

  it('labels a protected market order and shows no spend estimate for a sale', async () => {
    market();
    renderTerminal();
    fireEvent.change(await screen.findByLabelText('Order type'), { target: { value: 'MARKET_PROTECTED' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sell' }));

    expect(screen.getByLabelText('Protection price')).toBeInTheDocument();
    expect(screen.queryByText(/Maximum spend/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sell NOVA' })).toBeInTheDocument();
  });

  it('cancels an open order with a key, and says when it was already filled', async () => {
    const server = market().on('POST /api/v1/orders/00000000-0000-0000-0000-0000000000cc/cancel', {
      status: 409,
      problem: { status: 409, code: 'ORDER_NOT_CANCELABLE' },
    });
    renderTerminal();

    fireEvent.click(await screen.findByRole('button', { name: 'Cancel sell NOVA at 105.00' }));

    expect(await screen.findByText('That order was already filled.')).toBeInTheDocument();
    const cancel = server.requests.find((request) => request.url.endsWith('/cancel'));
    expect(cancel?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('after too many commands, sends the same cancel again under the same key', async () => {
    let limited = true;
    const server = market().on('POST /api/v1/orders/00000000-0000-0000-0000-0000000000cc/cancel', () =>
      limited
        ? { status: 429, problem: { status: 429, code: 'RATE_LIMITED' } }
        : {
            status: 200,
            json: {
              orderId: '00000000-0000-0000-0000-0000000000cc',
              status: 'CANCELED',
              canceledQuantity: 2,
              commandSequence: '12',
            },
          },
    );
    renderTerminal();

    fireEvent.click(await screen.findByRole('button', { name: 'Cancel sell NOVA at 105.00' }));
    expect(await screen.findByText(/Too many orders and cancels in a moment/)).toBeInTheDocument();
    limited = false;
    fireEvent.click(screen.getByRole('button', { name: 'Cancel sell NOVA at 105.00' }));

    await waitFor(() => expect(server.requests.filter((request) => request.url.endsWith('/cancel'))).toHaveLength(2));
    const [first, second] = server.requests.filter((request) => request.url.endsWith('/cancel'));
    expect(second?.headers['Idempotency-Key']).toBe(first?.headers['Idempotency-Key']);
  });
});
