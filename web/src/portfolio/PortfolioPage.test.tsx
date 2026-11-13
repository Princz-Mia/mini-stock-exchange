import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { App } from '../App';
import { resetSession } from '../api/client';
import { GLOSSARY } from '../help/glossary';
import { signedIn } from '../test/fakeServer';

const PORTFOLIO = {
  cash: { total: '10404.00', available: '10224.00', reserved: '180.00' },
  holdings: [
    {
      instrumentId: '00000000-0000-0000-0000-000000000001',
      symbol: 'NOVA',
      total: 96,
      available: 93,
      reserved: 3,
      mark: { price: '101.00', source: 'LAST_TRADE', at: '2026-11-04T12:30:05Z' },
      value: '9696.00',
      changePercent: '1.00',
      weight: '43.87',
      averageCost: '101.50',
      unrealized: '-48.00',
      unrealizedPercent: '-0.49',
    },
    {
      instrumentId: '00000000-0000-0000-0000-000000000002',
      symbol: 'ORBIT',
      total: 50,
      available: 50,
      reserved: 0,
      mark: { price: '40.00', source: 'REFERENCE', at: null },
      value: '2000.00',
      changePercent: '0.00',
      weight: '9.05',
      averageCost: '1250.00',
      unrealized: '-60500.00',
      unrealizedPercent: '-96.80',
    },
  ],
  equity: '22100.00',
  accountVersion: '7',
  asOfCommandSequence: '42',
  costBasis: '11744.00',
  unrealized: '-48.00',
  unrealizedPercent: '-0.41',
};

function trade(n: number) {
  return {
    tradeId: `00000000-0000-0000-0000-0000000001${String(n).padStart(2, '0')}`,
    instrumentId: '00000000-0000-0000-0000-000000000001',
    symbol: 'NOVA',
    side: n % 2 === 0 ? 'BUY' : 'SELL',
    liquidity: 'MAKER',
    price: '101.00',
    quantity: 4,
    notional: '404.00',
    executedAt: '2026-11-04T12:30:05Z',
    commandSequence: String(100 - n),
  };
}

describe('portfolio page', () => {
  beforeEach(() => resetSession());

  it('shows cash, equity and each holding with where its mark came from', async () => {
    signedIn()
      .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
      .on('GET /api/v1/portfolio/trades', { status: 200, json: { trades: [], nextCursor: null } })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    window.history.pushState({}, '', '/portfolio');

    render(<App />);

    const summary = await screen.findByRole('region', { name: 'Summary' });
    expect(within(summary).getByText('22,100.00')).toBeInTheDocument();
    expect(within(summary).getByText('10,224.00')).toBeInTheDocument();
    expect(within(summary).getByText('180.00')).toBeInTheDocument();
    for (const term of ['equity', 'available', 'reserved']) {
      expect(within(summary).getByRole('button', { name: `What is ${term}?` })).toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: 'What are mark and average cost?' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'What is partial fill?' })).toBeInTheDocument();
    const nova = screen.getByRole('row', { name: /NOVA/ });
    expect(within(nova).getByText(/last trade · 12:30:05 UTC/)).toBeInTheDocument();
    expect(within(nova).getByText('9,696.00')).toBeInTheDocument();
    const orbit = screen.getByRole('row', { name: /ORBIT/ });
    expect(within(orbit).getByText('reference')).toBeInTheDocument();
    expect(await screen.findByText('No executions yet.')).toBeInTheDocument();
  });

  it('shows each holding against its reference, its weight, and the estimated cost and unrealized P/L, with totals', async () => {
    signedIn()
      .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
      .on('GET /api/v1/portfolio/trades', { status: 200, json: { trades: [], nextCursor: null } })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    window.history.pushState({}, '', '/portfolio');
    render(<App />);

    const table = await screen.findByRole('table', { name: 'Holdings' });
    const headers = within(table)
      .getAllByRole('columnheader')
      .map((header) => header.textContent);
    expect(headers).toEqual([
      'Symbol',
      'Shares',
      'Available',
      'Reserved',
      'Mark',
      'Change',
      'Avg cost',
      'Value',
      'Weight',
      'Unrealized P/L',
    ]);
    const cells = (symbol: string) =>
      within(screen.getByRole('row', { name: new RegExp(symbol) }))
        .getAllByRole('cell')
        .map((cell) => cell.textContent);
    expect(cells('NOVA').slice(4)).toEqual(['▲ up +1.00%', '101.50', '9,696.00', '43.87%', '▼ down −48.00 −0.49%']);
    expect(cells('ORBIT').slice(4)).toEqual([
      '▶︎ unchanged, 0.00%',
      '1,250.00',
      '2,000.00',
      '9.05%',
      '▼ down −60,500.00 −96.80%',
    ]);
    expect(screen.getByText(/are estimates from your own trades/)).toBeInTheDocument();

    const summary = screen.getByRole('region', { name: 'Summary' });
    expect(within(summary).getByRole('button', { name: 'What is unrealized p/l?' })).toBeInTheDocument();
    expect(within(summary).getByText(/−48\.00 \(−0\.41%\)/)).toHaveTextContent('▼ down −48.00 (−0.41%)');
    // The arrows are for the eye; the words say the same to a screen reader.
    for (const arrow of [...within(table).getAllByText(/^[▲▼▶]/), ...within(summary).getAllByText('▼')]) {
      expect(arrow).toHaveAttribute('aria-hidden', 'true');
    }
    expect(within(summary).getByText('Cost 11,744.00')).toBeInTheDocument();
  });

  it.each(['costBasis', 'unrealized', 'unrealizedPercent'])('writes a dash for the totals without %s', async (field) => {
    signedIn()
      .on('GET /api/v1/portfolio', { status: 200, json: { ...PORTFOLIO, [field]: null } })
      .on('GET /api/v1/portfolio/trades', { status: 200, json: { trades: [], nextCursor: null } })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    window.history.pushState({}, '', '/portfolio');
    render(<App />);
    const summary = await screen.findByRole('region', { name: 'Summary' });
    const figure = within(summary).getByText('Estimated unrealized P/L').closest('.figure')!;
    expect(figure.querySelector('strong')).toHaveTextContent(/^—$/);
    expect(within(summary).queryByText(/^Cost /)).toBeNull();
  });

  it('explains average cost and unrealized P/L by the rule the server uses', () => {
    expect(GLOSSARY.averageCost.definition).toContain("at the instrument's reference price");
    expect(GLOSSARY.averageCost.definition).toContain('A sale leaves it unchanged; selling everything starts it afresh.');
    expect(GLOSSARY.unrealized.definition).toContain('its value at the mark less what it cost at the average cost');
    expect(GLOSSARY.unrealized.definition).toContain('It becomes real only when you sell.');
  });

  it('writes a dash where the cost of a holding cannot be worked out, and for the totals then', async () => {
    const unknown = {
      ...PORTFOLIO,
      holdings: [{ ...PORTFOLIO.holdings[0], averageCost: null, unrealized: null, unrealizedPercent: null }],
      costBasis: null,
      unrealized: null,
      unrealizedPercent: null,
    };
    signedIn()
      .on('GET /api/v1/portfolio', { status: 200, json: unknown })
      .on('GET /api/v1/portfolio/trades', { status: 200, json: { trades: [], nextCursor: null } })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    window.history.pushState({}, '', '/portfolio');
    render(<App />);

    const row = await screen.findByRole('row', { name: /NOVA/ });
    const cells = within(row)
      .getAllByRole('cell')
      .map((cell) => cell.textContent);
    expect(cells.slice(5)).toEqual(['—', '9,696.00', '43.87%', '—']);
    const summary = screen.getByRole('region', { name: 'Summary' });
    const figure = within(summary).getByText('Estimated unrealized P/L').closest('.figure')!;
    expect(figure.querySelector('strong')).toHaveTextContent(/^—$/);
    expect(within(summary).queryByText(/^Cost /)).toBeNull();
  });

  it('pages the executions with the cursor, page size and order it was given, one page on screen at a time', async () => {
    const server = signedIn()
      .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
      .on('GET /api/v1/portfolio/trades', (request) =>
        request.url.includes('cursor=next')
          ? { status: 200, json: { trades: [trade(3)], nextCursor: null } }
          : { status: 200, json: { trades: [trade(1), trade(2)], nextCursor: 'next' } },
      )
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    window.history.pushState({}, '', '/portfolio');
    render(<App />);
    const panel = await screen.findByRole('region', { name: 'Executions' });
    const executions = await within(panel).findByRole('table', { name: 'Executions' });
    expect(within(executions).getAllByRole('row')).toHaveLength(3);
    const first = server.requests.find((request) => request.url.startsWith('/api/v1/portfolio/trades'))!;
    expect(first.url).toContain('limit=25');
    expect(first.url).toContain('order=newest');

    fireEvent.click(within(panel).getByRole('button', { name: 'Next' }));

    await waitFor(() => expect(within(executions).getAllByRole('row')).toHaveLength(2));
    expect(within(panel).getByText('Showing 3–3')).toBeInTheDocument();
    expect(server.requests.some((request) => request.url.includes('cursor=next'))).toBe(true);
    expect(within(panel).getByRole('button', { name: 'Next' })).toBeDisabled();

    fireEvent.change(within(panel).getByLabelText('Order'), { target: { value: 'oldest' } });
    await waitFor(() =>
      expect(server.requests.some((request) => request.url.includes('order=oldest') && !request.url.includes('cursor'))).toBe(
        true,
      ),
    );
    expect(window.location.search).toBe('?executionsOrder=oldest');
  });

  it('filters executions and orders by symbol, side and status, as the server is asked', async () => {
    const ORBIT = '00000000-0000-0000-0000-000000000002';
    const server = signedIn()
      .on('GET /api/v1/portfolio', { status: 200, json: PORTFOLIO })
      .on('GET /api/v1/instruments', {
        status: 200,
        json: {
          instruments: [
            { instrumentId: ORBIT, symbol: 'ORBIT' },
            { instrumentId: '00000000-0000-0000-0000-000000000001', symbol: 'NOVA' },
          ],
          feed: { state: 'LIVE', lag: 0 },
        },
      })
      .on('GET /api/v1/portfolio/trades', { status: 200, json: { trades: [trade(1)], nextCursor: null } })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    window.history.pushState({}, '', '/portfolio');
    render(<App />);
    const orders = await screen.findByRole('region', { name: 'Orders' });
    const executions = screen.getByRole('region', { name: 'Executions' });
    await within(executions).findByRole('table', { name: 'Executions' });

    // The symbols come from the instrument list, in alphabetical order.
    const symbol = within(executions).getByLabelText('Symbol');
    await waitFor(() =>
      expect(within(symbol).getAllByRole('option').map((option) => option.textContent)).toEqual(['All', 'NOVA', 'ORBIT']),
    );
    fireEvent.change(symbol, { target: { value: ORBIT } });
    fireEvent.change(within(executions).getByLabelText('Side'), { target: { value: 'SELL' } });
    await waitFor(() =>
      expect(
        server.requests.some(
          (request) =>
            request.url.startsWith('/api/v1/portfolio/trades') &&
            request.url.includes(`instrumentId=${ORBIT}`) &&
            request.url.includes('side=SELL'),
        ),
      ).toBe(true),
    );
    expect(within(executions).getByRole('button', { name: 'Remove filter Symbol: ORBIT from Executions' })).toBeInTheDocument();

    // Orders filter by symbol, side and status, and say when nothing matches.
    expect(await within(orders).findByText('No orders yet.')).toBeInTheDocument();
    expect(within(orders).queryByLabelText('Status')).toBeNull();
    // A side or status the server does not take is dropped from the address rather than sent.
    const before = server.requests.length;
    window.history.pushState({}, '', '/portfolio?ordersSide=buy&ordersState=EXPIRED&ordersSize=10');
    window.dispatchEvent(new PopStateEvent('popstate'));
    // The page size is new, so the orders are asked for again; the bad side and status are not in that request.
    const asked = () =>
      server.requests.slice(before).filter((request) => request.url.startsWith('/api/v1/orders?') && request.url.includes('limit=10'));
    await waitFor(() => expect(asked().length).toBeGreaterThan(0));
    expect(asked().every((request) => !request.url.includes('side=') && !request.url.includes('state='))).toBe(true);
    expect(within(orders).queryByRole('list', { name: 'Orders filters in use' })).toBeNull();
    window.history.pushState({}, '', '/portfolio?ordersState=FILLED');
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(await within(orders).findByText('No orders match these filters.')).toBeInTheDocument();
    expect(within(orders).getByLabelText('Status')).toHaveValue('FILLED');
    expect(within(orders).getByLabelText('Symbol')).toBeInTheDocument();
    expect(within(orders).getByLabelText('Side')).toBeInTheDocument();
    expect(within(within(orders).getByLabelText('Status')).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'All',
      'Open',
      'Partly filled',
      'Filled',
      'Canceled',
      'Rejected',
    ]);
    expect(server.requests.some((request) => request.url.includes('state=FILLED') && request.url.includes('status=all'))).toBe(
      true,
    );
  });

  it('explains a missing trading account instead of calling it an outage', async () => {
    signedIn()
      .on('GET /api/v1/portfolio', { status: 403, problem: { status: 403, code: 'NO_TRADING_ACCOUNT' } })
      .on('GET /api/v1/portfolio/trades', { status: 403, problem: { status: 403, code: 'NO_TRADING_ACCOUNT' } })
      .on('GET /api/v1/orders', { status: 403, problem: { status: 403, code: 'NO_TRADING_ACCOUNT' } });
    window.history.pushState({}, '', '/portfolio');

    render(<App />);

    expect(await screen.findByRole('alert', {}, { timeout: 5000 })).toHaveTextContent('no trading account yet');
  });

  it('says so when the portfolio cannot be read', async () => {
    signedIn()
      .on('GET /api/v1/portfolio', { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE' } })
      .on('GET /api/v1/portfolio/trades', { status: 200, json: { trades: [], nextCursor: null } })
      .on('GET /api/v1/orders', { status: 200, json: { orders: [], nextCursor: null } });
    window.history.pushState({}, '', '/portfolio');

    render(<App />);

    expect(await screen.findByRole('alert', {}, { timeout: 5000 })).toHaveTextContent('not reachable');
  });
});
