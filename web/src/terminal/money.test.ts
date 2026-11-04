import { describe, expect, it } from 'vitest';

import type { TapeTrade } from '../api/types';
import { fromCents, isPrice, maximumSpend, toCents } from './money';
import { chartPoints } from './PriceChart';

describe('exact amounts', () => {
  it('multiplies in cents, so tenths never drift', () => {
    expect(maximumSpend('0.10', 3)).toBe('0.30');
    expect(maximumSpend('100.25', 10_000)).toBe('1002500.00');
    expect(maximumSpend('999999.99', 10_000)).toBe('9999999900.00');
  });

  it('round-trips cents and accepts only two-decimal prices', () => {
    expect(fromCents(toCents('100.07'))).toBe('100.07');
    expect(fromCents(-5n)).toBe('-0.05');
    expect(isPrice('100.25')).toBe(true);
    expect(isPrice('100.5')).toBe(false);
    expect(isPrice('1e2')).toBe(false);
    expect(isPrice('-1.00')).toBe(false);
  });
});

describe('chart points', () => {
  function trade(sequence: number, at: string, price: string): TapeTrade {
    return {
      tradeId: `00000000-0000-0000-0000-${String(sequence).padStart(12, '0')}`,
      tradeSequence: String(sequence),
      price,
      quantity: 1,
      aggressorSide: 'BUY',
      executedAt: at,
    };
  }

  it('turns the newest-first tape into one point per second, oldest first, the last trade winning', () => {
    const tape = [
      trade(3, '2026-11-04T12:00:01.900Z', '101.00'),
      trade(2, '2026-11-04T12:00:01.100Z', '100.50'),
      trade(1, '2026-11-04T12:00:00.500Z', '100.00'),
    ];

    expect(chartPoints(tape)).toEqual([
      { time: Date.parse('2026-11-04T12:00:00Z') / 1000, value: 100 },
      { time: Date.parse('2026-11-04T12:00:01Z') / 1000, value: 101 },
    ]);
  });
});
