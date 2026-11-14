import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { Instrument, SessionFigures } from '../api/types';
import { noTrades } from '../test/fakeStream';
import { InstrumentFigures } from './InstrumentFigures';
import { direction, signed, spread, trendPoints } from './session';
import { Watchlist } from './Watchlist';

function traded(change: string, percent: string, overrides: Partial<SessionFigures> = {}): SessionFigures {
  return {
    ...noTrades('100.00'),
    last: '101.03',
    change,
    changePercent: percent,
    high: '102.00',
    low: '99.10',
    volume: 12_345,
    tradeCount: 17,
    vwap: '100.47',
    trend: [
      { minute: '2026-11-12T10:00:00Z', price: '100.00' },
      { minute: '2026-11-12T10:01:00Z', price: '102.00' },
      { minute: '2026-11-12T10:02:00Z', price: '101.03' },
    ],
    ...overrides,
  };
}

describe('session figures, written out', () => {
  it('reads the direction from the change, and has none before a trade', () => {
    expect(direction(traded('1.03', '1.03'))).toBe('up');
    expect(direction(traded('-0.50', '-0.50'))).toBe('down');
    expect(direction(traded('0.00', '0.00'))).toBe('flat');
    expect(direction(noTrades('100.00'))).toBeNull();
  });

  it('writes a sign on every change but none, and a true minus', () => {
    expect(signed('1.03')).toBe('+1.03');
    expect(signed('-0.50')).toBe('−0.50');
    expect(signed('0.00')).toBe('0.00');
    expect(signed('-1234.50')).toBe('−1,234.50');
  });

  it('works the spread out exactly in cents, and leaves it out without both sides', () => {
    expect(spread('101.00', '101.05')).toBe('0.05');
    expect(spread('0.10', '0.30')).toBe('0.20');
    expect(spread(null, '101.05')).toBeNull();
    expect(spread('101.00', null)).toBeNull();
  });

  it('draws the trend oldest at the left with the range filling the height, flat when prices never move', () => {
    expect(trendPoints(traded('1.03', '1.03').trend, 50, 20)).toBe('0.0,20.0 25.0,0.0 50.0,9.7');
    expect(
      trendPoints(
        [
          { minute: '2026-11-12T10:00:00Z', price: '5.00' },
          { minute: '2026-11-12T10:01:00Z', price: '5.00' },
        ],
        40,
        20,
      ),
    ).toBe('0.0,10.0 40.0,10.0');
    expect(trendPoints([{ minute: '2026-11-12T10:00:00Z', price: '5.00' }], 40, 20)).toBeNull();
    expect(trendPoints([], 40, 20)).toBeNull();
  });
});

const instrument = (session: SessionFigures, overrides: Partial<Instrument> = {}): Instrument => ({
  instrumentId: '00000000-0000-0000-0000-000000000001',
  symbol: 'NOVA',
  displayName: null,
  venueState: 'OPEN',
  referencePrice: '100.00',
  tick: '0.01',
  lotSize: 1,
  minQuantity: 1,
  maxQuantity: 10000,
  maxPrice: '1000000.00',
  lastPrice: session.last,
  bestBid: { price: '101.00', quantity: 5, orderCount: 1 },
  bestAsk: { price: '101.05', quantity: 3, orderCount: 1 },
  lastTradeSequence: '17',
  sourceCommandSequence: '40',
  generatedAt: '2026-11-12T10:02:00Z',
  session,
  ...overrides,
});

describe('Watchlist', () => {
  it('shows the last price, the change with a sign and an arrow, a trend line, and the bid and ask', () => {
    render(<Watchlist instruments={[instrument(traded('1.03', '1.03'))]} selected={null} onSelect={() => undefined} />);
    const row = screen.getByRole('button', { name: /NOVA/ });
    expect(row).toHaveAccessibleName('NOVA 101.03 Bid 101.00 Ask 101.05 up +1.03%');
    expect(within(row).getByText('▲')).toHaveAttribute('aria-hidden', 'true');
    expect(row.querySelector('svg.sparkline-up polyline')).not.toBeNull();
  });

  it('says a fall in words as well as by its arrow, and an unchanged price as unchanged', () => {
    const { rerender } = render(
      <Watchlist instruments={[instrument(traded('-0.50', '-0.50'))]} selected={null} onSelect={() => undefined} />,
    );
    expect(screen.getByRole('button', { name: /NOVA/ })).toHaveAccessibleName('NOVA 101.03 Bid 101.00 Ask 101.05 down −0.50%');
    rerender(<Watchlist instruments={[instrument(traded('0.00', '0.00'))]} selected={null} onSelect={() => undefined} />);
    expect(screen.getByRole('button', { name: /NOVA/ })).toHaveAccessibleName(
      'NOVA 101.03 Bid 101.00 Ask 101.05 unchanged, 0.00%',
    );
  });

  it('labels the reference price before the first trade, with no change and dashes for an empty book', () => {
    render(
      <Watchlist
        instruments={[instrument(noTrades('100.00'), { bestBid: null, bestAsk: null })]}
        selected={null}
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByRole('button', { name: /NOVA/ })).toHaveAccessibleName('NOVA 100.00 ref Bid — Ask —');
  });

  it("prefers the snapshot's last price, as the header does, when the two differ", () => {
    render(
      <Watchlist
        instruments={[instrument(traded('1.03', '1.03'), { lastPrice: '101.20' })]}
        selected={null}
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByRole('button', { name: /NOVA/ })).toHaveAccessibleName('NOVA 101.20 Bid 101.00 Ask 101.05 up +1.03%');
  });

  it("shows the trade feed's last price while the snapshot has none yet", () => {
    render(
      <Watchlist
        instruments={[instrument(traded('1.03', '1.03'), { lastPrice: null })]}
        selected={null}
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByRole('button', { name: /NOVA/ })).toHaveAccessibleName('NOVA 101.03 Bid 101.00 Ask 101.05 up +1.03%');
  });

  it("shows the snapshot's last price while the trade feed is a moment behind it", () => {
    render(
      <Watchlist
        instruments={[instrument(noTrades('100.00'), { lastPrice: '100.40' })]}
        selected={null}
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByRole('button', { name: /NOVA/ })).toHaveAccessibleName('NOVA 100.40 Bid 101.00 Ask 101.05');
  });
});

describe('InstrumentFigures', () => {
  const figures = () =>
    Object.fromEntries(
      within(document.querySelector<HTMLElement>('.instrument-figures')!)
        .getAllByRole('term')
        .map((term) => [term.textContent, term.nextElementSibling?.textContent]),
    );

  it('shows the change, the book and the session since trading began', () => {
    render(<InstrumentFigures session={traded('1.03', '1.03')} bid="101.00" ask="101.05" />);
    expect(figures()).toEqual({
      Change: '▲ up +1.03 (+1.03%)',
      Bid: '101.00',
      Ask: '101.05',
      Spread: '0.05',
      High: '102.00',
      Low: '99.10',
      Volume: '12,345',
      VWAP: '100.47',
      Reference: '100.00',
    });
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('says an unchanged price is unchanged, with its own arrow', () => {
    render(<InstrumentFigures session={traded('0.00', '0.00')} bid="101.00" ask="101.05" />);
    expect(figures().Change).toBe('▶︎ unchanged, 0.00 (0.00%)');
  });

  it('writes dashes for what is not known yet', () => {
    render(<InstrumentFigures session={noTrades('100.00')} bid={null} ask="101.05" />);
    expect(figures()).toMatchObject({ Change: '—', Bid: '—', Spread: '—', High: '—', Volume: '0', VWAP: '—' });
  });

  it('says the figures are behind while the trade feed is not live', () => {
    render(
      <InstrumentFigures
        session={traded('1.03', '1.03', { feed: { state: 'STALE', lag: 12 } })}
        bid="101.00"
        ask="101.05"
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Figures: Stale · 12 behind');
  });

  it('says how far behind only when there is a lag to say', () => {
    render(
      <InstrumentFigures session={traded('1.03', '1.03', { feed: { state: 'PAUSED', lag: 0 } })} bid={null} ask={null} />,
    );
    expect(screen.getByRole('status')).toHaveTextContent(/^Figures: Paused$/);
  });
});
