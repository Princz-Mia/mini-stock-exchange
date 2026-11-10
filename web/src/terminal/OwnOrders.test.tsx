import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetSession } from '../api/client';
import type { OrderDetail } from '../api/types';
import { fakeServer } from '../test/fakeServer';
import { OwnOrders, outcome } from './OwnOrders';

const NOVA = '00000000-0000-0000-0000-000000000001';
const ORBIT = '00000000-0000-0000-0000-000000000002';

let next = 0;

function order(changes: Partial<OrderDetail> = {}): OrderDetail {
  next += 1;
  return {
    orderId: `00000000-0000-0000-0000-${String(next).padStart(12, '0')}`,
    instrumentId: NOVA,
    symbol: 'NOVA',
    side: 'BUY',
    type: 'LIMIT',
    timeInForce: 'GTC',
    limitPrice: '100.00',
    protectionPrice: null,
    requestedQuantity: 10,
    filledQuantity: 0,
    workingQuantity: 10,
    canceledQuantity: 0,
    state: 'OPEN',
    reason: null,
    version: '1',
    createdAt: '2026-11-10T12:00:00Z',
    updatedAt: '2026-11-10T12:00:05Z',
    ...changes,
  };
}

type Instrument = { instrumentId: string; symbol: string } | null;

function renderOrders(orders: readonly OrderDetail[], instrument: Instrument) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const ui = (on: Instrument) => (
    <QueryClientProvider client={client}>
      <OwnOrders orders={orders} instrument={on} />
    </QueryClientProvider>
  );
  const rendered = render(ui(instrument));
  return { client, select: (on: Instrument) => rendered.rerender(ui(on)) };
}

const tab = (name: RegExp) => screen.getByRole('tab', { name });
const asked = (url: string) => new URL(url, 'http://localhost').searchParams;
const page = (orders: readonly OrderDetail[]) => ({ status: 200, json: { orders, nextCursor: null } });

beforeEach(() => {
  resetSession();
  next = 0;
});

describe('OwnOrders', () => {
  it('opens on the open orders, counted, and asks for nothing more until Recent is chosen', () => {
    const server = fakeServer();
    renderOrders([order(), order({ instrumentId: ORBIT, symbol: 'ORBIT' })], { instrumentId: NOVA, symbol: 'NOVA' });

    expect(tab(/Open orders/)).toHaveAttribute('aria-selected', 'true');
    expect(tab(/Open orders/)).toHaveTextContent('Open orders (2)');
    expect(within(screen.getByRole('table', { name: 'Open orders' })).getAllByRole('row')).toHaveLength(3);
    expect(server.requests).toHaveLength(0);
    expect(screen.getByRole('tabpanel')).toHaveAttribute('tabindex', '0');
  });

  it('shows the most recently ended orders under Recent, in the order sent, with how each ended', async () => {
    const recent = [
      order({ state: 'FILLED', filledQuantity: 10, workingQuantity: 0, side: 'SELL', updatedAt: '2026-11-10T12:09:00Z' }),
      order({ state: 'CANCELED', filledQuantity: 4, workingQuantity: 0, canceledQuantity: 6, reason: 'CANCELED_BY_OWNER' }),
      order({ state: 'REJECTED', workingQuantity: 0, reason: 'INSUFFICIENT_CASH' }),
      order({
        state: 'CANCELED',
        type: 'MARKET_PROTECTED',
        timeInForce: 'IOC',
        limitPrice: null,
        protectionPrice: '105.00',
        filledQuantity: 5,
        workingQuantity: 0,
        canceledQuantity: 5,
        reason: 'IOC_REMAINDER_CANCELED',
        updatedAt: '2026-11-10T12:01:00Z',
      }),
    ];
    const server = fakeServer().on('GET /api/v1/orders', page(recent));
    renderOrders([], { instrumentId: NOVA, symbol: 'NOVA' });

    fireEvent.click(tab(/Recent orders/));

    const table = await screen.findByRole('table', { name: 'Recent orders' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((row) => within(row).getAllByRole('cell')[4]!.textContent)).toEqual([
      'Filled · 10 of 10',
      'Canceled · 4 of 10 filled',
      'Rejected · INSUFFICIENT_CASH',
      'Canceled · 5 of 10 filled · the rest found no match',
    ]);
    expect(rows[0]).toHaveTextContent('12:09:00 UTC');
    // A protected market order shows its protection price.
    expect(within(rows[3]!).getAllByRole('cell')[3]).toHaveTextContent('105.00');
    const params = asked(server.requests[0]!.url);
    expect(params.get('status')).toBe('finished');
    expect(params.get('limit')).toBe('20');
    expect(params.has('order')).toBe(false);
    expect(params.has('instrumentId')).toBe(false);
  });

  it('narrows both tabs to the instrument on screen', async () => {
    const server = fakeServer().on('GET /api/v1/orders', page([]));
    renderOrders([order(), order({ instrumentId: ORBIT, symbol: 'ORBIT', limitPrice: '40.00' })], {
      instrumentId: NOVA,
      symbol: 'NOVA',
    });

    fireEvent.click(screen.getByLabelText('Only NOVA'));

    expect(tab(/Open orders/)).toHaveTextContent('Open orders (1)');
    expect(screen.queryByRole('button', { name: 'Cancel buy ORBIT at 40.00' })).not.toBeInTheDocument();
    fireEvent.click(tab(/Recent orders/));
    expect(await screen.findByText('No finished orders in NOVA yet.')).toBeInTheDocument();
    expect(asked(server.requests.at(-1)!.url).get('instrumentId')).toBe(NOVA);
  });

  it('follows the switch and the instrument while Recent is showing, never the old rows', async () => {
    const novaDone = order({ state: 'FILLED', filledQuantity: 10, workingQuantity: 0 });
    const server = fakeServer().on('GET /api/v1/orders', (request) => {
      const instrument = asked(request.url).get('instrumentId');
      return page(instrument === ORBIT ? [] : [novaDone]);
    });
    const view = renderOrders([], { instrumentId: NOVA, symbol: 'NOVA' });
    fireEvent.click(tab(/Recent orders/));
    await screen.findByRole('table', { name: 'Recent orders' });

    fireEvent.click(screen.getByLabelText('Only NOVA'));
    await waitFor(() => expect(asked(server.requests.at(-1)!.url).get('instrumentId')).toBe(NOVA));

    view.select({ instrumentId: ORBIT, symbol: 'ORBIT' });
    expect(screen.getByLabelText('Only ORBIT')).toBeChecked();
    expect(screen.queryByRole('table', { name: 'Recent orders' })).not.toBeInTheDocument();
    expect(await screen.findByText('No finished orders in ORBIT yet.')).toBeInTheDocument();
    expect(asked(server.requests.at(-1)!.url).get('instrumentId')).toBe(ORBIT);
  });

  it('says so when the open orders of the instrument on screen are none', () => {
    renderOrders([order({ instrumentId: ORBIT, symbol: 'ORBIT' })], { instrumentId: NOVA, symbol: 'NOVA' });
    fireEvent.click(screen.getByLabelText('Only NOVA'));
    expect(screen.getByText('No open orders in NOVA.')).toBeInTheDocument();
  });

  it('moves between the tabs with the arrow keys, as a tab list does', () => {
    fakeServer().on('GET /api/v1/orders', page([]));
    renderOrders([], null);

    fireEvent.keyDown(tab(/Open orders/), { key: 'ArrowRight' });

    expect(tab(/Recent orders/)).toHaveAttribute('aria-selected', 'true');
    expect(tab(/Recent orders/)).toHaveFocus();
    expect(tab(/Open orders/)).toHaveAttribute('tabindex', '-1');
    fireEvent.keyDown(tab(/Recent orders/), { key: 'ArrowLeft' });
    expect(tab(/Open orders/)).toHaveFocus();
    expect(screen.queryByLabelText(/Only/)).not.toBeInTheDocument();
  });

  it('says so when recent orders cannot be loaded', async () => {
    fakeServer().on('GET /api/v1/orders', { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } });
    renderOrders([], null);
    fireEvent.click(tab(/Recent orders/));
    expect(await screen.findByRole('alert')).toHaveTextContent('Recent orders could not be loaded.');
  });

  it('keeps the recent orders it has when a later refresh fails, and says so', async () => {
    let calls = 0;
    fakeServer().on('GET /api/v1/orders', () =>
      ++calls === 1
        ? page([order({ state: 'FILLED', filledQuantity: 10, workingQuantity: 0 })])
        : { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } },
    );
    const view = renderOrders([], null);
    fireEvent.click(tab(/Recent orders/));
    await screen.findByRole('table', { name: 'Recent orders' });

    await act(() => view.client.refetchQueries({ queryKey: ['orders', 'recent'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be refreshed; showing the last loaded');
    expect(screen.getByRole('table', { name: 'Recent orders' })).toBeInTheDocument();
  });

  it('marks Recent out of date after a cancel, so the canceled order shows there', async () => {
    const resting = order();
    let canceled = false;
    fakeServer()
      .on('GET /api/v1/auth/csrf', { status: 200, json: { headerName: 'X-CSRF-TOKEN', token: 't' } })
      .on(`POST /api/v1/orders/${resting.orderId}/cancel`, () => {
        canceled = true;
        return { status: 200, json: { orderId: resting.orderId, state: 'CANCELED', canceledQuantity: 10, commandSequence: '9' } };
      })
      .on('GET /api/v1/orders', () =>
        page(canceled ? [{ ...resting, state: 'CANCELED', workingQuantity: 0, canceledQuantity: 10 }] : []),
      );
    const view = renderOrders([resting], { instrumentId: NOVA, symbol: 'NOVA' });
    fireEvent.click(tab(/Recent orders/));
    expect(await screen.findByText('No finished orders yet.')).toBeInTheDocument();
    fireEvent.click(tab(/Open orders/));

    fireEvent.click(screen.getByRole('button', { name: 'Cancel buy NOVA at 100.00' }));
    await waitFor(() => expect(view.client.getQueryState(['orders', 'recent', null])?.isInvalidated).toBe(true));
    fireEvent.click(tab(/Recent orders/));

    const table = await screen.findByRole('table', { name: 'Recent orders' });
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Canceled');
  });
});

describe('outcome', () => {
  it('says a rejection without a reason plainly, and shows a reason it does not know', () => {
    expect(outcome(order({ state: 'REJECTED', reason: null }))).toBe('Rejected');
    expect(outcome(order({ state: 'CANCELED', reason: 'SOMETHING_NEW' }))).toBe('Canceled · SOMETHING_NEW');
    expect(outcome(order({ state: 'CANCELED', reason: 'IOC_REMAINDER_CANCELED' }))).toBe('Canceled · the rest found no match');
  });
});
