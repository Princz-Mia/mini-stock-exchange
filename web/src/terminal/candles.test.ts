import { describe, expect, it } from 'vitest';

import type { Candle, TapeTrade } from '../api/types';
import { seeded } from '../test/seeded';
import {
  type Bar,
  aggregate,
  complete,
  fits,
  fittedBarSize,
  hasGap,
  remember,
  throughOf,
  vwap,
  windowFor,
  withTrades,
} from './candles';
import { fromCents } from './money';

/** A trade as the oracle sees it: plain numbers, counted with BigInt only where sums could grow large. */
interface Trade {
  sequence: number;
  at: number;
  cents: number;
  quantity: number;
}

const START = Date.parse('2026-11-10T08:00:00Z');

/** A random walk of trades a few seconds apart, sometimes several in one second, over about three hours. */
function trades(seed: number, count: number): Trade[] {
  const draw = seeded(seed);
  const result: Trade[] = [];
  let at = START + draw(60_000);
  let cents = 5_000 + draw(10_000);
  for (let sequence = 1; sequence <= count; sequence++) {
    at += draw(4) === 0 ? 0 : draw(40_000);
    cents = Math.max(1, cents + draw(201) - 100);
    result.push({ sequence, at, cents, quantity: 1 + draw(500) });
  }
  return result;
}

/** The oracle: bars of a size straight from the trades, in the shape `aggregate` returns. */
function oracleBars(source: readonly Trade[], minutes: number): Bar[] {
  const bars = new Map<number, Bar>();
  for (const trade of source) {
    const time = Math.floor(trade.at / 1000 / (minutes * 60)) * minutes * 60;
    const price = BigInt(trade.cents);
    const quantity = BigInt(trade.quantity);
    const bar = bars.get(time);
    if (bar === undefined) {
      bars.set(time, {
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
  return [...bars.values()].sort((a, b) => a.time - b.time);
}

/** One-minute candles as the API sends them, from the oracle's one-minute bars. */
function candles(source: readonly Trade[]): Candle[] {
  return oracleBars(source, 1).map((bar) => ({
    bucketStart: new Date(bar.time * 1000).toISOString(),
    open: fromCents(bar.open),
    high: fromCents(bar.high),
    low: fromCents(bar.low),
    close: fromCents(bar.close),
    volume: Number(bar.volume),
    tradeCount: Number(bar.trades),
    notional: fromCents(bar.notional),
    vwap: vwap(bar),
    sourceMaxTradeSequence: String(
      Math.max(...source.filter((trade) => Math.floor(trade.at / 60_000) * 60 === bar.time).map((t) => t.sequence)),
    ),
  }));
}

/** The tape: the newest trades first, at most fifty, as the market stream sends them. */
function tape(source: readonly Trade[]): TapeTrade[] {
  return [...source]
    .reverse()
    .slice(0, 50)
    .map((trade) => ({
      tradeId: `t${trade.sequence}`,
      tradeSequence: String(trade.sequence),
      price: fromCents(BigInt(trade.cents)),
      quantity: trade.quantity,
      aggressorSide: 'BUY' as const,
      executedAt: new Date(trade.at).toISOString(),
    }));
}

const FROM = new Date(START - 60_000).toISOString();
const SIZES = [1, 5, 15, 60];

describe('aggregate', () => {
  it('builds every bar size from one-minute candles exactly as from the trades themselves', () => {
    for (let seed = 1; seed <= 40; seed++) {
      const source = trades(seed, 300 + seeded(seed)(400));
      const oneMinute = candles(source);
      for (const minutes of SIZES) {
        expect(aggregate(oneMinute, minutes), `seed ${seed}, ${minutes} min`).toEqual(oracleBars(source, minutes));
      }
    }
  });

  it('takes candles in time order whatever order they arrive in', () => {
    const source = trades(99, 200);
    const shuffled = [...candles(source)].reverse();
    expect(aggregate(shuffled, 5)).toEqual(oracleBars(source, 5));
  });

  it('aligns bars to UTC multiples of their size', () => {
    const source = trades(5, 400);
    for (const minutes of SIZES) {
      for (const bar of aggregate(candles(source), minutes)) {
        expect(bar.time % (minutes * 60)).toBe(0);
      }
    }
  });
});

describe('withTrades', () => {
  it('completes the bars with the tape when the tape reaches back to the first missing trade', () => {
    for (let seed = 1; seed <= 40; seed++) {
      const draw = seeded(seed * 31);
      const source = trades(seed, 200 + draw(200));
      // The analytics have reached somewhere in the last fifty trades, or all of them.
      const through = source.length - draw(51);
      const known = source.slice(0, through);
      const throughSequence = through === 0 ? null : String(through);
      for (const minutes of SIZES) {
        const merged = withTrades(aggregate(candles(known), minutes), tape(source), throughSequence, minutes, FROM);
        expect(merged, `seed ${seed}, through ${through}, ${minutes} min`).toEqual(oracleBars(source, minutes));
      }
      expect(hasGap(tape(source), throughSequence, String(source.length))).toBe(false);
    }
  });

  it('never counts a trade the bars already include', () => {
    const source = trades(12, 120);
    const all = aggregate(candles(source), 5);
    expect(withTrades(all, tape(source), String(source.length), 5, FROM)).toEqual(all);
  });

  it('starts bars from the tape alone when there are no candles yet', () => {
    const source = trades(3, 40);
    expect(withTrades([], tape(source), null, 15, FROM)).toEqual(oracleBars(source, 15));
  });

  it('leaves out trades from before the window', () => {
    const source = trades(8, 40);
    const from = new Date(source[20]!.at).toISOString();
    const inWindow = source.filter((trade) => trade.at >= source[20]!.at);
    expect(withTrades([], tape(source), null, 1, from)).toEqual(oracleBars(inWindow, 1));
  });

  it('does not change the bars it was given', () => {
    const source = trades(4, 100);
    const bars = aggregate(candles(source.slice(0, 80)), 5);
    const before = structuredClone(bars);
    withTrades(bars, tape(source), '80', 5, FROM);
    expect(bars).toEqual(before);
  });
});

describe('hasGap', () => {
  const source = trades(21, 200);

  it('finds a gap once the tape no longer holds the trade after the last one in the bars', () => {
    expect(hasGap(tape(source), '149', '200')).toBe(true);
    expect(hasGap(tape(source), '150', '200')).toBe(false);
    expect(hasGap(tape(source), null, '200')).toBe(true);
  });

  it('counts the newest trade itself as missing until the tape holds it', () => {
    expect(hasGap([], '199', '200')).toBe(true);
    expect(hasGap(tape(source), '199', '200')).toBe(false);
  });

  it('finds none when the bars are up to date, or nothing has traded', () => {
    expect(hasGap(tape(source), '200', '200')).toBe(false);
    expect(hasGap([], null, '0')).toBe(false);
  });
});

describe('remember', () => {
  const source = trades(31, 120);

  it('keeps every trade seen that the bars do not include, newest first, once each', () => {
    const first = tape(source.slice(0, 90));
    const second = tape(source);
    const kept = remember(remember([], first, '50'), second, '50');
    expect(kept.map((trade) => Number(trade.tradeSequence))).toEqual(
      Array.from({ length: 70 }, (_, index) => 120 - index),
    );
  });

  it('drops what the bars have caught up with', () => {
    const kept = remember(tape(source), [], '100');
    expect(kept.map((trade) => Number(trade.tradeSequence))).toEqual(
      Array.from({ length: 20 }, (_, index) => 120 - index),
    );
  });

  it('keeps at most the newest so many', () => {
    expect(remember([], tape(source), null, 10).map((trade) => trade.tradeSequence)).toEqual(
      tape(source).slice(0, 10).map((trade) => trade.tradeSequence),
    );
  });
});

describe('complete', () => {
  it('leaves out bars that start before the window', () => {
    const source = trades(17, 300);
    const bars = aggregate(candles(source), 15);
    const from = new Date((bars[3]!.time + 60) * 1000).toISOString();
    expect(complete(bars, from)).toEqual(bars.slice(4));
    expect(complete(bars, new Date(bars[3]!.time * 1000).toISOString())).toEqual(bars.slice(3));
  });
});

describe('throughOf', () => {
  it('takes the highest sequence in the bars sent, and the server figure only when none were sent', () => {
    const sent = candles(trades(23, 100));
    expect(throughOf(sent, '150')).toBe('100');
    expect(throughOf([], '150')).toBe('150');
    expect(throughOf([], null)).toBeNull();
  });
});

describe('vwap', () => {
  it('rounds half to even, to the cent', () => {
    const at = (notional: bigint, volume: bigint) => vwap({ notional, volume });
    expect(at(25n, 10n)).toBe('0.02');
    expect(at(35n, 10n)).toBe('0.04');
    expect(at(26n, 10n)).toBe('0.03');
    expect(at(24n, 10n)).toBe('0.02');
    expect(at(1_000_050n, 100n)).toBe('100.00');
    expect(at(1_000_150n, 100n)).toBe('100.02');
  });

  it('matches exact division against seeded cases', () => {
    const draw = seeded(77);
    for (let index = 0; index < 2_000; index++) {
      const volume = BigInt(1 + draw(10_000));
      const notional = BigInt(draw(1_000_000_000));
      const [whole = '0', fraction = '00'] = vwap({ notional, volume }).split('.');
      const cents = BigInt(whole) * 100n + BigInt(fraction);
      // Within half a cent of notional / volume, and on a tie, even.
      const error = cents * volume * 2n - notional * 2n;
      expect(error <= volume && error >= -volume).toBe(true);
      if (error === volume || error === -volume) {
        expect(cents % 2n).toBe(0n);
      }
    }
  });
});

describe('ranges and bar sizes', () => {
  it('keeps a bar size only when the range holds at least two such bars', () => {
    expect(fits('15m', '30m')).toBe(true);
    expect(fits('1h', '30m')).toBe(false);
    expect(fits('1h', '2h')).toBe(true);
    expect(fittedBarSize('1h', '30m')).toBe('15m');
    expect(fittedBarSize('5m', '30m')).toBe('5m');
    expect(fittedBarSize('1m', '1d')).toBe('1m');
  });

  it('asks for whole minutes that include the forming one, and never more than a day', () => {
    const now = Date.parse('2026-11-10T12:34:56.789Z');
    expect(windowFor('2h', now)).toEqual({ from: '2026-11-10T10:35:00.000Z', to: '2026-11-10T12:35:00.000Z' });
    const day = windowFor('1d', now);
    expect(Date.parse(day.to) - Date.parse(day.from)).toBe(1_440 * 60_000);
  });
});
