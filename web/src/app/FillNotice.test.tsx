import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { App } from '../App';
import { resetSession } from '../api/client';
import type { OrderDetail } from '../api/types';
import { type Recorded, signedIn } from '../test/fakeServer';
import { FakeStream } from '../test/fakeStream';
import { FillNotice, RETRY_MS, useFillNotices } from './FillNotice';
import {
  EMPTY_LEDGER,
  type Fill,
  GONE_KEPT,
  afterCheck,
  describeFills,
  fillsSince,
  mergeFills,
  withPlacements,
} from './fills';

const NOVA = '00000000-0000-0000-0000-000000000001';

function order(id: string, overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    orderId: `00000000-0000-0000-0000-0000000000${id}`,
    instrumentId: NOVA,
    symbol: 'NOVA',
    side: 'BUY',
    type: 'LIMIT',
    timeInForce: 'GTC',
    limitPrice: '101.00',
    protectionPrice: null,
    requestedQuantity: 10,
    filledQuantity: 0,
    workingQuantity: 10,
    canceledQuantity: 0,
    state: 'OPEN',
    reason: null,
    version: '1',
    createdAt: '2026-11-12T10:00:00Z',
    updatedAt: '2026-11-12T10:00:00Z',
    ...overrides,
  };
}

const filled = (detail: OrderDetail, shares: number): OrderDetail => ({
  ...detail,
  filledQuantity: shares,
  workingQuantity: detail.requestedQuantity - shares,
  state: shares === detail.requestedQuantity ? 'FILLED' : 'PARTIALLY_FILLED',
});

const canceled = (detail: OrderDetail, shares: number): OrderDetail => ({
  ...detail,
  filledQuantity: shares,
  workingQuantity: 0,
  canceledQuantity: detail.requestedQuantity - shares,
  state: 'CANCELED',
});

const seen = (detail: OrderDetail) => ({ orderId: detail.orderId, filledQuantity: detail.filledQuantity });

describe('fills between two looks at the open orders', () => {
  const resting = order('a1');

  it('finds the shares a resting order filled, at its limit price, with its symbol and side as read now', () => {
    const now = new Map([[resting.orderId, filled(resting, 4)]]);
    expect(fillsSince([seen(resting)], now)).toEqual([
      {
        orderId: resting.orderId,
        symbol: 'NOVA',
        side: 'BUY',
        price: '101.00',
        shares: 4,
        filled: 4,
        requested: 10,
        ended: null,
      },
    ]);
  });

  it('finds nothing for an order that filled no more, one not known now, one placed in between, or one cancelled', () => {
    const placed = filled(order('a2'), 3);
    expect(fillsSince([seen(resting)], new Map([[resting.orderId, resting]]))).toEqual([]);
    expect(fillsSince([seen(resting)], new Map())).toEqual([]);
    expect(fillsSince([seen(resting)], new Map([[placed.orderId, placed]]))).toEqual([]);
    expect(fillsSince([seen(filled(resting, 2))], new Map([[resting.orderId, canceled(resting, 2)]]))).toEqual([]);
  });

  it('says how an order ended: filled, or cancelled after more fills', () => {
    expect(fillsSince([seen(resting)], new Map([[resting.orderId, filled(resting, 10)]]))[0]?.ended).toBe('FILLED');
    expect(fillsSince([seen(resting)], new Map([[resting.orderId, canceled(resting, 6)]]))[0]).toMatchObject({
      shares: 6,
      filled: 6,
      ended: 'CANCELED',
    });
  });

  it('adds new fills of an order already in the notice to its line, and new orders after it', () => {
    const first: Fill[] = fillsSince([seen(resting)], new Map([[resting.orderId, filled(resting, 4)]]));
    const other = order('a3', { side: 'SELL', symbol: 'ORBIT', limitPrice: '40.00', requestedQuantity: 5 });
    const later = [
      ...fillsSince([seen(filled(resting, 4))], new Map([[resting.orderId, filled(resting, 10)]])),
      ...fillsSince([seen(other)], new Map([[other.orderId, filled(other, 5)]])),
    ];
    const merged = mergeFills(first, later);
    expect(merged.map((fill) => [fill.symbol, fill.shares, fill.filled, fill.ended])).toEqual([
      ['NOVA', 10, 10, 'FILLED'],
      ['ORBIT', 5, 5, 'FILLED'],
    ]);
    expect(first[0]?.shares, 'the notice shown is not changed in place').toBe(4);
  });

  it('says what filled, at what price, and how much of the order', () => {
    const fill = (overrides: Partial<Fill>): Fill => ({
      orderId: 'x',
      symbol: 'NOVA',
      side: 'BUY',
      price: '1234.50',
      shares: 4,
      filled: 4,
      requested: 10,
      ended: null,
      ...overrides,
    });
    expect(describeFills([fill({})])).toEqual({
      title: 'Order partly filled',
      lines: [{ orderId: 'x', text: 'Bought 4 NOVA at 1,234.50, 4 of 10 filled.' }],
      more: 0,
    });
    expect(describeFills([fill({ side: 'SELL', shares: 1500, filled: 2000, requested: 2000, ended: 'FILLED' })])).toEqual(
      {
        title: 'Order filled',
        lines: [{ orderId: 'x', text: 'Sold 1,500 NOVA at 1,234.50, the order is filled.' }],
        more: 0,
      },
    );
    expect(describeFills([fill({ filled: 6, ended: 'CANCELED' })])).toEqual({
      title: 'Order partly filled',
      lines: [{ orderId: 'x', text: 'Bought 4 NOVA at 1,234.50, 6 of 10 filled; the rest was cancelled.' }],
      more: 0,
    });
    const four = ['a', 'b', 'c', 'd'].map((orderId) => fill({ orderId, ended: 'FILLED' }));
    expect(describeFills(four)).toMatchObject({ title: '4 orders filled', more: 1 });
    expect(describeFills(four).lines.map((line) => line.orderId)).toEqual(['a', 'b', 'c']);
    expect(describeFills([fill({ orderId: 'a', ended: 'FILLED' }), fill({ orderId: 'b' })]).title).toBe(
      'Fills on 2 orders',
    );
  });
});

describe('following orders across checks and late reports from the ticket', () => {
  const a = order('a1');

  it('follows what each check sees resting, and an order seen first on its own starts provisional', () => {
    const ledger = afterCheck(EMPTY_LEDGER, new Map(), new Map([[a.orderId, filled(a, 5)]]));
    expect(ledger.open.get(a.orderId)).toEqual({ filled: 5, firstSeen: 5 });
    const next = afterCheck(ledger, withPlacements(ledger, []), new Map([[a.orderId, filled(a, 7)]]));
    expect(next.open.get(a.orderId), 'still provisional after a later check').toEqual({ filled: 7, firstSeen: 5 });
  });

  it('adds back what filled before a check first saw the order, once, however late the ticket reports it', () => {
    // Seen first at 5, then a check reported 5 to 7; the ticket now says 2 filled on arrival: 3 shares were never seen.
    const seenOnce = afterCheck(EMPTY_LEDGER, new Map(), new Map([[a.orderId, filled(a, 5)]]));
    const seenTwice = afterCheck(seenOnce, withPlacements(seenOnce, []), new Map([[a.orderId, filled(a, 7)]]));
    const before = withPlacements(seenTwice, [{ orderId: a.orderId, filledQuantity: 2 }]);
    expect(before.get(a.orderId)).toEqual({ filled: 4, firstSeen: null });
    expect(fillsSince([{ orderId: a.orderId, filledQuantity: 4 }], new Map([[a.orderId, filled(a, 7)]]))[0]?.shares).toBe(3);
    // Reported again (a retried request), it changes nothing.
    const after = afterCheck(seenTwice, before, new Map([[a.orderId, filled(a, 7)]]));
    expect(withPlacements(after, [{ orderId: a.orderId, filledQuantity: 2 }]).get(a.orderId)).toEqual({
      filled: 7,
      firstSeen: null,
    });
  });

  it('starts an order no check has seen from what filled on arrival', () => {
    expect(withPlacements(EMPTY_LEDGER, [{ orderId: a.orderId, filledQuantity: 2 }]).get(a.orderId)).toEqual({
      filled: 2,
      firstSeen: null,
    });
  });

  it('keeps an order that ended for a late report, and an order read on its own that still rests', () => {
    const seen = afterCheck(EMPTY_LEDGER, new Map(), new Map([[a.orderId, filled(a, 5)]]));
    const ended = afterCheck(seen, withPlacements(seen, []), new Map([[a.orderId, filled(a, 10)]]));
    expect(ended.open.has(a.orderId)).toBe(false);
    expect(ended.gone.get(a.orderId)).toEqual({ filled: 10, firstSeen: 5 });
    expect(withPlacements(ended, [{ orderId: a.orderId, filledQuantity: 1 }]).get(a.orderId)).toEqual({
      filled: 6,
      firstSeen: null,
    });
    // Missing from the open list, read on its own, and still resting: still followed.
    const readAlone = afterCheck(seen, withPlacements(seen, []), new Map([[a.orderId, filled(a, 6)]]));
    expect(readAlone.open.get(a.orderId)).toEqual({ filled: 6, firstSeen: 5 });
  });

  it('never moves a count back, nor brings back an order that ended, when the open list read is an older one', () => {
    const seen = afterCheck(EMPTY_LEDGER, new Map(), new Map([[a.orderId, filled(a, 6)]]));
    const stale = afterCheck(seen, withPlacements(seen, []), new Map([[a.orderId, filled(a, 3)]]));
    expect(stale.open.get(a.orderId)?.filled).toBe(6);
    const ended = afterCheck(seen, withPlacements(seen, []), new Map([[a.orderId, filled(a, 10)]]));
    const older = afterCheck(ended, withPlacements(ended, []), new Map([[a.orderId, filled(a, 4)]]));
    expect(older.open.has(a.orderId)).toBe(false);
    expect(older.gone.get(a.orderId)).toEqual({ filled: 10, firstSeen: 6 });
  });

  it('keeps an order still waiting for the report from the ticket longer than one already accounted for', () => {
    const waiting = new Map([['waiting', { filled: 1, firstSeen: 1 }]]);
    const accounted = new Map(
      Array.from({ length: GONE_KEPT + 1 }, (_, index) => [`o${index}`, { filled: 1, firstSeen: null }] as const),
    );
    const ledger = afterCheck(afterCheck(EMPTY_LEDGER, waiting, new Map()), accounted, new Map());
    expect(ledger.gone.size).toBe(GONE_KEPT);
    expect(ledger.gone.has('waiting'), 'the oldest, but still waiting').toBe(true);
    expect(ledger.gone.has('o0')).toBe(false);
    expect(ledger.gone.has('o1')).toBe(false);
  });

  it('keeps only the most recent orders that ended', () => {
    const many = new Map(
      Array.from({ length: GONE_KEPT + 5 }, (_, index) => [`o${index}`, { filled: 1, firstSeen: null }] as const),
    );
    const ledger = afterCheck(EMPTY_LEDGER, many, new Map());
    expect(ledger.gone.size).toBe(GONE_KEPT);
    expect(ledger.gone.has('o0')).toBe(false);
    expect(ledger.gone.has(`o${GONE_KEPT + 4}`)).toBe(true);
  });
});

/** The notice's status region, there whenever a trader is signed in. */
const status = () => document.querySelector<HTMLElement>('.fill-notice-text');

describe('the fill notice', () => {
  let streams: FakeStream[];
  let open: OrderDetail[];
  let ended: Map<string, OrderDetail>;
  let ordersDown: boolean;
  let lookupsDown: number;

  beforeEach(() => {
    resetSession();
    streams = [];
    open = [];
    ended = new Map();
    ordersDown = false;
    lookupsDown = 0;
    vi.stubGlobal(
      'EventSource',
      class extends FakeStream {
        constructor(url: string) {
          super(url);
          streams.push(this);
        }
      },
    );
  });
  afterEach(() => resetSession());

  function serve() {
    const server = signedIn()
      .on('GET /api/v1/portfolio', {
        status: 200,
        json: {
          cash: { total: '0.00', available: '0.00', reserved: '0.00' },
          holdings: [],
          equity: '0.00',
          accountVersion: '1',
          asOfCommandSequence: '1',
          costBasis: null,
          unrealized: null,
          unrealizedPercent: null,
        },
      })
      .on('GET /api/v1/portfolio/trades', { status: 200, json: { trades: [], nextCursor: null } })
      .on('GET /api/v1/orders', (request) =>
        request.url.includes('status=active')
          ? ordersDown
            ? { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } }
            : { status: 200, json: { orders: open, nextCursor: null } }
          : { status: 200, json: { orders: [], nextCursor: null } },
      );
    for (const id of ['a1', 'a2', 'a3', 'a4']) {
      const orderId = order(id).orderId;
      server.on(`GET /api/v1/orders/${orderId}`, () => {
        if (lookupsDown > 0) {
          lookupsDown--;
          return { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } };
        }
        const detail = ended.get(orderId);
        return detail === undefined
          ? { status: 404, problem: { status: 404, code: 'ORDER_NOT_FOUND' } }
          : { status: 200, json: detail };
      });
    }
    return server;
  }

  const looks = (requests: Recorded[]) => requests.filter((request) => request.url.includes('status=active')).length;

  /** An account frame, and the debounced refetch and check that follow it. */
  async function accountChanged(server: ReturnType<typeof serve>) {
    const before = looks(server.requests);
    streams.findLast((stream) => stream.url === '/api/v1/account-events')!.emit('account', {
      accountVersion: '2',
      commandSequence: '9',
    });
    await waitFor(() => expect(looks(server.requests)).toBeGreaterThan(before));
    // Let the check finish with what it read, including any order it reads on its own.
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
  }

  async function startOnPortfolio() {
    const server = serve();
    window.history.pushState({}, '', '/portfolio');
    render(<App />);
    await screen.findByRole('region', { name: 'Summary' });
    await waitFor(() => expect(streams.some((stream) => stream.url === '/api/v1/account-events')).toBe(true));
    return server;
  }

  it('tells the trader when a resting order fills, in a polite status, and keeps one notice as more fills come', async () => {
    // One order was already partly filled before the trader signed in: that is not news.
    const resting = filled(order('a1'), 2);
    const other = order('a2', { side: 'SELL', symbol: 'ORBIT', limitPrice: '40.00', requestedQuantity: 5 });
    open = [resting, other];
    const server = await startOnPortfolio();
    expect(status()).toHaveAttribute('role', 'status');
    expect(status()).toBeEmptyDOMElement();
    expect(status()?.parentElement).toHaveClass('visually-hidden');
    await accountChanged(server);
    expect(screen.queryByRole('region', { name: 'Fills' })).toBeNull();
    expect(status()).toBeEmptyDOMElement();

    // Two more shares.
    open = [filled(resting, 4), other];
    await accountChanged(server);
    const notice = await screen.findByRole('region', { name: 'Fills' });
    expect(notice).toHaveClass('fill-notice');
    expect(within(notice).getByRole('status')).toHaveTextContent('Order partly filledBought 2 NOVA at 101.00, 4 of 10 filled.');
    expect(document.documentElement).toHaveClass('fill-notice-open');

    // The rest fills and the order leaves the open list; the other is cancelled, and a new order (placed elsewhere)
    // is seen for the first time, already partly filled.
    const placed = filled(order('a3'), 3);
    open = [placed];
    ended.set(resting.orderId, filled(resting, 10));
    ended.set(other.orderId, canceled(other, 0));
    await accountChanged(server);
    await waitFor(() =>
      expect(within(notice).getByRole('status')).toHaveTextContent('Order filledBought 8 NOVA at 101.00, the order is filled.'),
    );
    expect(notice).not.toHaveTextContent('ORBIT');

    // Once seen, the new order's later fills are news.
    open = [filled(placed, 7)];
    await accountChanged(server);
    await waitFor(() => expect(within(notice).getByRole('status')).toHaveTextContent('Bought 4 NOVA at 101.00, 7 of 10 filled.'));
    expect(within(notice).getByRole('status')).toHaveTextContent(/^Fills on 2 orders/);

    // Dismissed, the notice and the room kept for it go; the status region stays, empty, for the next one.
    fireEvent.click(within(notice).getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByRole('region', { name: 'Fills' })).toBeNull();
    expect(status()).toBeEmptyDOMElement();
    expect(document.documentElement).not.toHaveClass('fill-notice-open');
  });

  it('groups the fills of several orders into one notice, with a link to Portfolio that closes it', async () => {
    const a = order('a1');
    const b = order('a2', { side: 'SELL', limitPrice: '102.00', requestedQuantity: 2 });
    open = [a, b];
    window.history.pushState({}, '', '/terminal');
    const server = serve()
      .on('GET /api/v1/instruments', { status: 200, json: { instruments: [] } })
      .on('GET /api/v1/market', { status: 200, json: { state: 'OPEN', simulationId: null, changedAt: null } });
    render(<App />);
    await waitFor(() => expect(streams.some((stream) => stream.url === '/api/v1/account-events')).toBe(true));
    await accountChanged(server);

    open = [filled(a, 6)];
    ended.set(b.orderId, filled(b, 2));
    await accountChanged(server);
    const notice = await screen.findByRole('region', { name: 'Fills' });
    expect(within(notice).getByRole('status')).toHaveTextContent(
      'Fills on 2 ordersBought 6 NOVA at 101.00, 6 of 10 filled.Sold 2 NOVA at 102.00, the order is filled.',
    );
    const link = within(notice).getByRole('link', { name: 'View in Portfolio' });
    // Opened in another tab, the notice stays in this one.
    fireEvent.click(link, { ctrlKey: true });
    fireEvent.click(link, { metaKey: true });
    fireEvent.click(link, { shiftKey: true });
    fireEvent.click(link, { button: 1 });
    expect(screen.getByRole('region', { name: 'Fills' })).toBe(notice);
    link.focus();
    fireEvent.click(link);
    await screen.findByRole('region', { name: 'Summary' });
    expect(window.location.pathname).toBe('/portfolio');
    expect(screen.queryByRole('region', { name: 'Fills' })).toBeNull();
    expect(document.activeElement, 'focus moves to the page, not to nothing').toBe(document.querySelector('main'));
  });

  it('closes on Escape inside it, with focus on the page for that moment only', async () => {
    const resting = order('a1');
    open = [resting];
    const server = await startOnPortfolio();
    const main = document.querySelector('main')!;
    expect(main).not.toHaveAttribute('tabindex');
    await accountChanged(server);
    open = [filled(resting, 1)];
    await accountChanged(server);
    const notice = await screen.findByRole('region', { name: 'Fills' });
    const dismiss = within(notice).getByRole('button', { name: 'Dismiss' });
    dismiss.focus();
    const focus = vi.spyOn(main, 'focus');
    fireEvent.keyDown(dismiss, { key: 'Escape' });
    expect(screen.queryByRole('region', { name: 'Fills' })).toBeNull();
    expect(document.activeElement).toBe(main);
    expect(focus, 'without scrolling the page').toHaveBeenCalledWith({ preventScroll: true });
    // Once focus moves on, the page cannot take focus from a click again.
    screen.getByRole('link', { name: 'Terminal' }).focus();
    expect(main).not.toHaveAttribute('tabindex');
  });

  it('leaves focus where it was when the notice is closed from elsewhere', async () => {
    const resting = order('a1');
    open = [resting];
    const server = await startOnPortfolio();
    await accountChanged(server);
    open = [filled(resting, 1)];
    await accountChanged(server);
    const notice = await screen.findByRole('region', { name: 'Fills' });
    const elsewhere = screen.getByRole('link', { name: 'Terminal' });
    elsewhere.focus();
    fireEvent.click(within(notice).getByRole('button', { name: 'Dismiss' }));
    expect(document.activeElement).toBe(elsewhere);
  });

  it('tries a failed look again by itself, and reports everything since the last look that worked', async () => {
    const resting = order('a1');
    const lost = order('a4');
    open = [resting, lost];
    const server = await startOnPortfolio();
    await accountChanged(server);

    // The look after this change fails. No other change comes, but the retry reads the orders once they are back.
    ordersDown = true;
    open = [filled(resting, 2), lost];
    await accountChanged(server);
    expect(screen.queryByRole('region', { name: 'Fills' })).toBeNull();
    ordersDown = false;
    open = [filled(resting, 5)];
    const notice = await screen.findByRole('region', { name: 'Fills' }, { timeout: RETRY_MS + 2_000 });
    // Compared with the last look that worked: all five shares, and nothing about the order that is gone for good.
    expect(within(notice).getByRole('status')).toHaveTextContent('Order partly filledBought 5 NOVA at 101.00, 5 of 10 filled.');
  });

  it('tries again when the very first look fails, so the fill that follows is still news', async () => {
    const resting = order('a1');
    open = [resting];
    ordersDown = true;
    const server = await startOnPortfolio();
    await accountChanged(server);
    // The orders come back and the retry learns what is open; then a fill.
    ordersDown = false;
    await waitFor(() => expect(looks(server.requests)).toBeGreaterThanOrEqual(2), { timeout: RETRY_MS + 2_000 });
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    open = [filled(resting, 3)];
    await accountChanged(server);
    expect(await screen.findByRole('region', { name: 'Fills' })).toHaveTextContent('Bought 3 NOVA at 101.00');
  });

  it('tries again when an order that left the list cannot be read yet, rather than losing its fill', async () => {
    const resting = order('a1');
    open = [resting];
    const server = await startOnPortfolio();
    await accountChanged(server);
    open = [];
    ended.set(resting.orderId, filled(resting, 10));
    lookupsDown = 1;
    await accountChanged(server);
    const notice = await screen.findByRole('region', { name: 'Fills' }, { timeout: RETRY_MS + 2_000 });
    expect(within(notice).getByRole('status')).toHaveTextContent('Order filledBought 10 NOVA at 101.00, the order is filled.');
  });

  /** The terminal, with NOVA to trade, an open market and the account stream connected. */
  async function startOnTerminal(server: ReturnType<typeof serve>) {
    server
      .on('GET /api/v1/instruments', {
        status: 200,
        json: {
          instruments: [
            {
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
              generatedAt: '2026-11-12T10:00:00Z',
              // Session figures, as the instrument list carries them once they ship; ignored before.
              session: {
                open: '100.00',
                last: null,
                change: null,
                changePercent: null,
                high: null,
                low: null,
                volume: 0,
                tradeCount: 0,
                vwap: null,
                trend: [],
                feed: { state: 'LIVE', lag: 0 },
              },
            },
          ],
          feed: { state: 'LIVE', lag: 0 },
        },
      })
      .on('GET /api/v1/market', { status: 200, json: { state: 'OPEN', simulationId: 's', openedAt: null, commandSequence: '1' } });
    window.history.pushState({}, '', '/terminal');
    render(<App />);
    await waitFor(() => expect(streams.some((stream) => stream.url === '/api/v1/account-events')).toBe(true));
  }

  it('watches an order placed from the ticket, so a bot filling it before the next account change is still news', async () => {
    const placed = order('a1');
    const server = serve().on('POST /api/v1/orders', {
      status: 201,
      json: {
        orderId: placed.orderId,
        state: 'OPEN',
        reason: null,
        filledQuantity: 0,
        workingQuantity: 10,
        canceledQuantity: 0,
        commandSequence: '9',
      },
    });
    await startOnTerminal(server);
    await accountChanged(server);
    // By the time anything reads the open orders, the market maker has filled it and it has left the list.
    ended.set(placed.orderId, filled(placed, 10));
    fireEvent.change(await screen.findByLabelText('Shares'), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText('Limit price'), { target: { value: '101.00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Buy NOVA' }));
    const notice = await screen.findByRole('region', { name: 'Fills' });
    expect(within(notice).getByRole('status')).toHaveTextContent('Order filledBought 10 NOVA at 101.00, the order is filled.');
  });

  it('keeps a fill while the first-login tour has the page, and shows it once the tour closes', async () => {
    const resting = order('a1');
    open = [resting];
    const server = serve()
      .on('GET /api/v1/me/tour', { status: 200, json: { seenVersion: 0 } })
      .on('PUT /api/v1/me/tour', (request) => ({ status: 200, json: request.body }));
    await startOnTerminal(server);
    await screen.findByRole('button', { name: 'Skip tour' });
    await accountChanged(server);
    open = [filled(resting, 4)];
    await accountChanged(server);
    expect(screen.queryByRole('region', { name: 'Fills' })).toBeNull();
    expect(status()).toBeEmptyDOMElement();
    fireEvent.click(screen.getByRole('button', { name: 'Skip tour' }));
    expect(await screen.findByRole('region', { name: 'Fills' })).toHaveTextContent('Bought 4 NOVA at 101.00, 4 of 10 filled.');
  });

  it('is not there for an operator, who has no orders', async () => {
    signedIn('operator', ['OPERATOR']);
    window.history.pushState({}, '', '/operator');
    render(<App />);
    await screen.findByRole('navigation', { name: 'Main' });
    expect(streams.some((stream) => stream.url === '/api/v1/account-events')).toBe(false);
    expect(status()).toBeNull();
  });
});

describe('an order placed from the ticket', () => {
  beforeEach(() => resetSession());
  afterEach(() => resetSession());

  /** The notices with a button standing in for the ticket, which reports an order it placed. */
  function Harness() {
    const notices = useFillNotices();
    return (
      <>
        <button type="button" onClick={() => notices.check()}>
          Account changed
        </button>
        <button type="button" onClick={() => notices.placed({ orderId: order('a1').orderId, filledQuantity: 2 })}>
          Placed
        </button>
        <FillNotice fills={notices.fills} onDismiss={notices.dismiss} />
      </>
    );
  }

  it('is news when a bot fills it before any look has seen it, compared with what filled on arrival', async () => {
    const placed = order('a1');
    let open: OrderDetail[] = [];
    const server = signedIn()
      .on('GET /api/v1/orders', () => ({ status: 200, json: { orders: open, nextCursor: null } }))
      .on(`GET /api/v1/orders/${placed.orderId}`, { status: 200, json: filled(placed, 10) });
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <Harness />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    // The first look: nothing open.
    fireEvent.click(screen.getByRole('button', { name: 'Account changed' }));
    await waitFor(() => expect(server.requests).toHaveLength(1));
    // The order rested with 2 filled on arrival; by the next look the rest has filled and it has left the list.
    open = [];
    fireEvent.click(screen.getByRole('button', { name: 'Placed' }));
    expect(await screen.findByRole('region', { name: 'Fills' })).toHaveTextContent(
      'Order filledBought 8 NOVA at 101.00, the order is filled.',
    );
  });

  it('counts a fill reported through the ticket once, also when later looks find nothing new', async () => {
    const placed = order('a1');
    const server = signedIn()
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } })
      .on(`GET /api/v1/orders/${placed.orderId}`, { status: 200, json: filled(placed, 10) });
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <Harness />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Placed' }));
    const notice = await screen.findByRole('region', { name: 'Fills' });
    expect(notice).toHaveTextContent('Bought 8 NOVA at 101.00, the order is filled.');
    const reads = server.requests.length;
    fireEvent.click(screen.getByRole('button', { name: 'Account changed' }));
    await waitFor(() => expect(server.requests.length).toBeGreaterThan(reads));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(notice).toHaveTextContent('Bought 8 NOVA at 101.00, the order is filled.');
  });

  it('adds what filled before a look first saw the order once the ticket reports it late, without counting twice', async () => {
    const placed = order('a1');
    let open: OrderDetail[] = [];
    const server = signedIn().on('GET /api/v1/orders', () => ({ status: 200, json: { orders: open, nextCursor: null } }));
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <Harness />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const look = async (orders: OrderDetail[]) => {
      open = orders;
      const reads = server.requests.length;
      fireEvent.click(screen.getByRole('button', { name: 'Account changed' }));
      await waitFor(() => expect(server.requests.length).toBeGreaterThan(reads));
      await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    };
    await look([]);
    await look([filled(placed, 5)]);
    await look([filled(placed, 7)]);
    const notice = await screen.findByRole('region', { name: 'Fills' });
    expect(notice).toHaveTextContent('Bought 2 NOVA at 101.00, 7 of 10 filled.');
    // The ticket's answer, late after a timeout: 2 filled on arrival. Shares 2 to 5 were never reported; 5 to 7 were.
    fireEvent.click(screen.getByRole('button', { name: 'Placed' }));
    await waitFor(() => expect(notice).toHaveTextContent('Bought 5 NOVA at 101.00, 7 of 10 filled.'));
  });

  it('tries a failed look again after 1 s, doubling, from 1 s again after one works, and stops on leaving', async () => {
    vi.useFakeTimers();
    try {
      let down = true;
      const server = signedIn().on('GET /api/v1/orders', () =>
        down
          ? { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } }
          : { status: 200, json: { orders: [], nextCursor: null } },
      );
      const reads = () => server.requests.filter((request) => request.url.startsWith('/api/v1/orders')).length;
      const client = new QueryClient();
      const { result, unmount } = renderHook(() => useFillNotices(), {
        wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
      });
      const after = (milliseconds: number) => act(() => vi.advanceTimersByTimeAsync(milliseconds));
      act(() => result.current.check());
      await after(0);
      expect(reads()).toBe(1);
      await after(RETRY_MS - 1);
      expect(reads()).toBe(1);
      await after(1);
      expect(reads(), 'after 1 s').toBe(2);
      await after(2 * RETRY_MS);
      expect(reads(), 'after 2 s more').toBe(3);
      await after(4 * RETRY_MS - 1);
      expect(reads()).toBe(3);
      down = false;
      await after(1);
      expect(reads(), 'after 4 s more, and it works').toBe(4);
      await after(60_000);
      expect(reads(), 'nothing more once it works').toBe(4);

      // A new failure starts again at 1 s; an account change in the meantime replaces the pending retry.
      down = true;
      act(() => result.current.check());
      await after(0);
      expect(reads()).toBe(5);
      await after(RETRY_MS / 2);
      act(() => result.current.check());
      await after(0);
      expect(reads()).toBe(6);
      await after(2 * RETRY_MS - 1);
      expect(reads(), 'the replaced retry does not fire').toBe(6);
      await after(1);
      expect(reads(), 'the next waits 2 s').toBe(7);

      unmount();
      await after(60_000);
      expect(reads(), 'no retries after leaving').toBe(7);
    } finally {
      vi.useRealTimers();
    }
  });

  it('reserves room under the page as tall as the card and a gap, measured again when it grows', () => {
    const observed: Element[] = [];
    let resized = () => undefined as void;
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: () => void) {
          resized = callback;
        }
        observe(element: Element) {
          observed.push(element);
        }
        disconnect() {}
      },
    );
    const fill: Fill = {
      orderId: 'x',
      symbol: 'NOVA',
      side: 'BUY',
      price: '101.00',
      shares: 1,
      filled: 1,
      requested: 2,
      ended: null,
    };
    const { unmount } = render(
      <MemoryRouter>
        <FillNotice fills={[fill]} onDismiss={() => undefined} />
      </MemoryRouter>,
    );
    const root = document.documentElement;
    expect(root).toHaveClass('fill-notice-open');
    // jsdom lays nothing out, so the card is 0 px tall: the room is the 16 px gap.
    expect(root.style.getPropertyValue('--fill-notice-room')).toBe('16px');
    const card = screen.getByRole('region', { name: 'Fills' });
    expect(observed).toEqual([card]);
    // The card grows as fills join: measured again.
    Object.defineProperty(card, 'offsetHeight', { configurable: true, value: 120 });
    act(() => resized());
    expect(root.style.getPropertyValue('--fill-notice-room')).toBe('136px');
    unmount();
    expect(root).not.toHaveClass('fill-notice-open');
    expect(root.style.getPropertyValue('--fill-notice-room')).toBe('');
  });

  it('counts from what filled on arrival once, even when a look saw the order first', async () => {
    const placed = order('a1');
    let open: OrderDetail[] = [];
    const server = signedIn().on('GET /api/v1/orders', () => ({ status: 200, json: { orders: open, nextCursor: null } }));
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <Harness />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Account changed' }));
    await waitFor(() => expect(server.requests).toHaveLength(1));
    // A look sees the new order with 5 filled before the ticket's answer arrives: on its own, not news yet.
    open = [filled(placed, 5)];
    fireEvent.click(screen.getByRole('button', { name: 'Account changed' }));
    await waitFor(() => expect(server.requests).toHaveLength(2));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(screen.queryByRole('region', { name: 'Fills' })).toBeNull();
    // The ticket's answer says 2 filled on arrival; by the next look 7 have: 5 shares since, counted once.
    open = [filled(placed, 7)];
    fireEvent.click(screen.getByRole('button', { name: 'Placed' }));
    expect(await screen.findByRole('region', { name: 'Fills' })).toHaveTextContent(
      'Order partly filledBought 5 NOVA at 101.00, 7 of 10 filled.',
    );
  });

  it('keeps fills while a tour has the page, and shows them when it closes', () => {
    const fill: Fill = {
      orderId: 'x',
      symbol: 'NOVA',
      side: 'BUY',
      price: '101.00',
      shares: 1,
      filled: 1,
      requested: 2,
      ended: null,
    };
    const { rerender } = render(
      <MemoryRouter>
        <FillNotice fills={[fill]} onDismiss={() => undefined} held />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('region', { name: 'Fills' })).toBeNull();
    expect(status()).toBeEmptyDOMElement();
    expect(document.documentElement, 'no room taken while nothing shows').not.toHaveClass('fill-notice-open');
    rerender(
      <MemoryRouter>
        <FillNotice fills={[fill]} onDismiss={() => undefined} held={false} />
      </MemoryRouter>,
    );
    expect(screen.getByRole('region', { name: 'Fills' })).toHaveTextContent('Bought 1 NOVA at 101.00, 1 of 2 filled.');
  });

  it('leaves a notice already showing as it is while a tour opens, and adds the fills that came meanwhile after', () => {
    const fill = (orderId: string, symbol: string): Fill => ({
      orderId,
      symbol,
      side: 'BUY',
      price: '101.00',
      shares: 1,
      filled: 1,
      requested: 2,
      ended: null,
    });
    const first = [fill('x', 'NOVA')];
    const { rerender } = render(
      <MemoryRouter>
        <FillNotice fills={first} onDismiss={() => undefined} />
      </MemoryRouter>,
    );
    const text = status()!.innerHTML;
    // The tour replays from Help, and another order fills meanwhile: nothing changes, so nothing is read out again.
    const both = [...first, fill('y', 'ORBIT')];
    rerender(
      <MemoryRouter>
        <FillNotice fills={both} onDismiss={() => undefined} held />
      </MemoryRouter>,
    );
    expect(status()!.innerHTML).toBe(text);
    expect(document.documentElement).toHaveClass('fill-notice-open');
    rerender(
      <MemoryRouter>
        <FillNotice fills={both} onDismiss={() => undefined} held={false} />
      </MemoryRouter>,
    );
    expect(status()).toHaveTextContent('Fills on 2 orders');
    expect(status()).toHaveTextContent('ORBIT');
  });
});
