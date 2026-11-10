import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { App } from '../App';
import { resetSession } from '../api/client';
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
    },
    {
      instrumentId: '00000000-0000-0000-0000-000000000002',
      symbol: 'ORBIT',
      total: 50,
      available: 50,
      reserved: 0,
      mark: { price: '40.00', source: 'REFERENCE', at: null },
      value: '2000.00',
    },
  ],
  equity: '22100.00',
  accountVersion: '7',
  asOfCommandSequence: '42',
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
    const nova = screen.getByRole('row', { name: /NOVA/ });
    expect(within(nova).getByText(/last trade · 12:30:05 UTC/)).toBeInTheDocument();
    expect(within(nova).getByText('9,696.00')).toBeInTheDocument();
    const orbit = screen.getByRole('row', { name: /ORBIT/ });
    expect(within(orbit).getByText('reference')).toBeInTheDocument();
    expect(await screen.findByText('No executions yet.')).toBeInTheDocument();
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
