import type { SessionFigures } from '../api/types';
import { groupDigits } from '../format';
import { fromCents, toCents } from './money';

export type Direction = 'up' | 'down' | 'flat';

/** Which way the last price moved from the reference, or null before the first trade. */
export function direction(session: SessionFigures): Direction | null {
  if (session.change === null) {
    return null;
  }
  const cents = toCents(session.change.replace('-', ''));
  if (cents === 0n) {
    return 'flat';
  }
  return session.change.startsWith('-') ? 'down' : 'up';
}

// U+FE0E asks for the text form of ▶, which some systems would otherwise draw as a coloured emoji.
const ARROWS: Record<Direction, string> = { up: '▲', down: '▼', flat: '▶︎' };

/** An arrow that says the direction by its shape, so the change never depends on colour alone. */
export function arrow(way: Direction): string {
  return ARROWS[way];
}

/** A signed amount with its sign written out: +1.03, −0.50 (a true minus), 0.00. */
export function signed(amount: string): string {
  const negative = amount.startsWith('-');
  const magnitude = groupDigits(negative ? amount.slice(1) : amount);
  if (toCents(negative ? amount.slice(1) : amount) === 0n) {
    return magnitude;
  }
  return `${negative ? '−' : '+'}${magnitude}`;
}

/** The best ask less the best bid, or null without both. */
export function spread(bid: string | null, ask: string | null): string | null {
  return bid === null || ask === null ? null : fromCents(toCents(ask) - toCents(bid));
}

/**
 * The trend as SVG polyline points in a box of the given size: oldest at the left, the range of prices filling the
 * height, a flat line through the middle when every price is the same, and nothing with fewer than two minutes.
 */
export function trendPoints(trend: SessionFigures['trend'], width: number, height: number): string | null {
  if (trend.length < 2) {
    return null;
  }
  const prices = trend.map((point) => Number(toCents(point.price)));
  const low = Math.min(...prices);
  const high = Math.max(...prices);
  const step = width / (prices.length - 1);
  return prices
    .map((price, index) => {
      const y = high === low ? height / 2 : height - ((price - low) / (high - low)) * height;
      return `${(index * step).toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}
