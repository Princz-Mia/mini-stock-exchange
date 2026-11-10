import type { Candle, TapeTrade } from '../api/types';
import { fromCents, toCents } from './money';

/**
 * Bars for the chart, built from the analytics service's one-minute candles and topped up with live trades the
 * candles do not include yet. Everything is counted in exact cents and shares; prices become numbers only when they
 * are handed to the chart to draw.
 */

export const CHART_TYPES = ['candle', 'line', 'area'] as const;
export type ChartType = (typeof CHART_TYPES)[number];

/** How far back the chart looks, in minutes. One day is the most one request for one-minute bars can cover. */
export const RANGES = { '30m': 30, '2h': 120, '1d': 1_440 } as const;
export type Range = keyof typeof RANGES;

/** Bar sizes in minutes, built in the browser from one-minute bars. */
export const BAR_SIZES = { '1m': 1, '5m': 5, '15m': 15, '1h': 60 } as const;
export type BarSize = keyof typeof BAR_SIZES;

/** A bar size fits a range when the range holds at least two such bars. */
export function fits(bars: BarSize, range: Range): boolean {
  return BAR_SIZES[bars] * 2 <= RANGES[range];
}

/** The largest bar size no bigger than the one asked for that fits the range; one minute always fits. */
export function fittedBarSize(bars: BarSize, range: Range): BarSize {
  const sizes = Object.keys(BAR_SIZES) as BarSize[];
  for (let index = sizes.indexOf(bars); index > 0; index--) {
    const size = sizes[index]!;
    if (fits(size, range)) {
      return size;
    }
  }
  return '1m';
}

export interface Bar {
  /** The bar's start, in whole seconds since the epoch, on a multiple of its size. */
  time: number;
  open: bigint;
  high: bigint;
  low: bigint;
  close: bigint;
  /** Shares traded. */
  volume: bigint;
  /** Price times shares, in cents. */
  notional: bigint;
  trades: bigint;
}

/** The request window for a range: whole minutes, ending after the current minute so it includes the forming bar. */
export function windowFor(range: Range, now: number): { from: string; to: string } {
  const end = Math.floor(now / 60_000) * 60_000 + 60_000;
  return { from: new Date(end - RANGES[range] * 60_000).toISOString(), to: new Date(end).toISOString() };
}

function bucket(milliseconds: number, minutes: number): number {
  const size = minutes * 60;
  return Math.floor(milliseconds / 1000 / size) * size;
}

/** One-minute candles, oldest first, into bars of the given size, aligned to UTC multiples of that size. */
export function aggregate(candles: readonly Candle[], minutes: number): Bar[] {
  const bars = new Map<number, Bar>();
  const ordered = [...candles].sort((a, b) => Date.parse(a.bucketStart) - Date.parse(b.bucketStart));
  for (const candle of ordered) {
    const time = bucket(Date.parse(candle.bucketStart), minutes);
    const high = toCents(candle.high);
    const low = toCents(candle.low);
    const close = toCents(candle.close);
    const volume = BigInt(candle.volume);
    const notional = toCents(candle.notional);
    const trades = BigInt(candle.tradeCount);
    const bar = bars.get(time);
    if (bar === undefined) {
      bars.set(time, { time, open: toCents(candle.open), high, low, close, volume, notional, trades });
    } else {
      bar.high = high > bar.high ? high : bar.high;
      bar.low = low < bar.low ? low : bar.low;
      bar.close = close;
      bar.volume += volume;
      bar.notional += notional;
      bar.trades += trades;
    }
  }
  return [...bars.values()].sort((a, b) => a.time - b.time);
}

/**
 * The highest trade sequence the bars include. The server reads the bars and its own figure in two statements, and a
 * candle written in between makes its figure run ahead of the bars it sent; the bars' own figure cannot. With no bars
 * in the window, the server's figure is all there is.
 */
export function throughOf(candles: readonly Candle[], serverThrough: string | null): string | null {
  if (candles.length === 0) {
    return serverThrough;
  }
  return candles.reduce((highest, candle) =>
    BigInt(candle.sourceMaxTradeSequence) > BigInt(highest) ? candle.sourceMaxTradeSequence : highest,
  candles[0]!.sourceMaxTradeSequence);
}

/** How many trades the browser keeps beyond the tape while the bars catch up. */
export const KEPT_TRADES = 5_000;

/**
 * Every trade the browser has seen on the tape that the bars do not include yet. The tape holds only the newest 50,
 * and the bars are fetched every few seconds, so a burst can push trades off the tape before the next fetch; kept
 * here, they still reach the chart. Trades the bars include are dropped, and at most `limit` of the newest are kept.
 */
export function remember(
  kept: readonly TapeTrade[],
  trades: readonly TapeTrade[],
  throughSequence: string | null,
  limit = KEPT_TRADES,
): TapeTrade[] {
  const through = throughSequence === null ? 0n : BigInt(throughSequence);
  const bySequence = new Map<string, TapeTrade>();
  for (const trade of [...kept, ...trades]) {
    if (BigInt(trade.tradeSequence) > through) {
      bySequence.set(trade.tradeSequence, trade);
    }
  }
  return [...bySequence.values()]
    .sort((a, b) => (BigInt(a.tradeSequence) < BigInt(b.tradeSequence) ? 1 : -1))
    .slice(0, limit);
}

/** Bars that start before the window hold only part of their period, so they are left out rather than drawn short. */
export function complete(bars: readonly Bar[], from: string): Bar[] {
  const start = Date.parse(from) / 1000;
  return bars.filter((bar) => bar.time >= start);
}

/**
 * Adds the live trades no candle includes yet: those after the highest trade sequence any bar includes. Trade
 * sequences are per instrument and the analytics job takes them in order, so nothing at or below that sequence is
 * missing from the bars and nothing above it is in them. (A computation epoch replayed from a later point has no bars
 * for the trades before that point; the chart then shows none for them.) Trades before the window's start are left
 * out.
 */
export function withTrades(
  bars: readonly Bar[],
  trades: readonly TapeTrade[],
  throughSequence: string | null,
  minutes: number,
  from: string,
): Bar[] {
  const through = throughSequence === null ? 0n : BigInt(throughSequence);
  const start = Date.parse(from);
  const result = new Map(bars.map((bar) => [bar.time, { ...bar }]));
  const fresh = trades
    .filter((trade) => BigInt(trade.tradeSequence) > through && Date.parse(trade.executedAt) >= start)
    .sort((a, b) => (BigInt(a.tradeSequence) < BigInt(b.tradeSequence) ? -1 : 1));
  for (const trade of fresh) {
    const time = bucket(Date.parse(trade.executedAt), minutes);
    const price = toCents(trade.price);
    const quantity = BigInt(trade.quantity);
    const bar = result.get(time);
    if (bar === undefined) {
      result.set(time, {
        time,
        open: price,
        high: price,
        low: price,
        close: price,
        volume: quantity,
        notional: price * quantity,
        trades: 1n,
      });
    } else {
      bar.high = price > bar.high ? price : bar.high;
      bar.low = price < bar.low ? price : bar.low;
      bar.close = price;
      bar.volume += quantity;
      bar.notional += price * quantity;
      bar.trades += 1n;
    }
  }
  return [...result.values()].sort((a, b) => a.time - b.time);
}

/**
 * Whether the bars plus the tape leave trades out: the analytics are behind and the tape no longer holds the trade
 * right after the last one the bars include. While the tape covers the difference, the chart is complete.
 */
export function hasGap(
  trades: readonly TapeTrade[],
  throughSequence: string | null,
  latestSequence: string,
): boolean {
  const next = (throughSequence === null ? 0n : BigInt(throughSequence)) + 1n;
  if (next > BigInt(latestSequence)) {
    return false;
  }
  return !trades.some((trade) => BigInt(trade.tradeSequence) === next);
}

/** Notional over volume, rounded half to even to the cent, as the analytics service computes it. */
export function vwap(bar: Pick<Bar, 'notional' | 'volume'>): string {
  const quotient = bar.notional / bar.volume;
  const twice = (bar.notional % bar.volume) * 2n;
  const up = twice > bar.volume || (twice === bar.volume && quotient % 2n === 1n);
  return fromCents(up ? quotient + 1n : quotient);
}

/** Cents as a number for drawing only; prices are far below the range where a double loses cents. */
export function drawn(cents: bigint): number {
  return Number(cents) / 100;
}
