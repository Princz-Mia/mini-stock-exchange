import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { resetSession } from '../api/client';
import { FillWatch } from '../app/FillNotice';
import { TOURS } from '../help/tours';
import { signedIn } from '../test/fakeServer';
import { fakeStreams, frame } from '../test/fakeStream';
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
    },
  ],
  equity: '20000.00',
  accountVersion: '1',
  asOfCommandSequence: '1',
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

function renderTerminal(at = '/terminal', watch: ((order: { orderId: string; filledQuantity: number }) => void) | null = null) {
  const streams = fakeStreams();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
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
  return streams;
}

describe('terminal', () => {
  beforeEach(() => resetSession());

  it('pauses new orders while the market is halted, but still lets the trader cancel', async () => {
    const server = market().on('GET /api/v1/market', {
      status: 200,
      json: { state: 'HALTED', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', commandSequence: '9' },
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
      json: { state: 'OPEN', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', commandSequence: '9' },
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
      json: { state: 'SETUP', simulationId: 's', openedAt: null, commandSequence: '2' },
    });
    renderTerminal();
    await screen.findByRole('region', { name: 'Order · NOVA' });
    expect(await screen.findByText('Not open yet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'What is halted?' })).toBeNull();
  });

  it('takes orders once the market is open', async () => {
    market().on('GET /api/v1/market', {
      status: 200,
      json: { state: 'OPEN', simulationId: 's', openedAt: '2026-11-04T09:00:00Z', commandSequence: '9' },
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

  it('switches the stream when another instrument is chosen', async () => {
    market();
    const streams = renderTerminal();
    fireEvent.click(await screen.findByRole('button', { name: /^ORBIT/ }));

    await screen.findByRole('heading', { name: 'ORBIT', level: 1 });
    expect(streams.opened[0]?.closed).toBe(true);
    expect(streams.latest()?.url).toContain(ORBIT);
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
