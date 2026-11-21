import { describe, expect, it } from 'vitest';

import { seeded } from '../test/seeded';
import { CHART_HEIGHT, CHART_WIDTH, drawnCents, linePath, minuteIndex, percent, type Point, yDomain } from './qualityChart';

describe('minutes since a run started', () => {
  it('counts the minute it started in as 0, whatever the second', () => {
    expect(minuteIndex('2026-11-06T10:00:00Z', '2026-11-06T10:00:42.5Z')).toBe(0);
    expect(minuteIndex('2026-11-06T10:01:00Z', '2026-11-06T10:00:59Z')).toBe(1);
    expect(minuteIndex('2026-11-06T11:30:00Z', '2026-11-06T10:00:00Z')).toBe(90);
  });
});

describe('the scale and the line', () => {
  const at = (index: number, value: number | null): Point => ({ index, value });

  it('shares one scale over every series, ignoring gaps', () => {
    expect(yDomain([[at(0, 5), at(1, null)], [at(0, 2), at(3, 9)]])).toEqual({ low: 2, high: 9 });
    expect(yDomain([[at(0, null)], []])).toBeNull();
    expect(drawnCents('100.05')).toBe(10_005);
    expect(drawnCents(null)).toBeNull();
  });

  it('draws consecutive minutes as one line, breaks at a gap, and dashes a value alone', () => {
    const domain = { low: 0, high: 10 };
    expect(linePath([at(0, 0), at(1, 10)], 0, 1, domain)).toBe(`M 0.0 ${CHART_HEIGHT.toFixed(1)} L ${CHART_WIDTH.toFixed(1)} 0.0`);
    // A missing value, and a minute not given, each break the line; the value alone after a break is a dash.
    expect(linePath([at(0, 5), at(1, 5), at(2, null), at(3, 5), at(5, 5)], 0, 5, domain)).toBe(
      'M 0.0 36.0 L 48.0 36.0 M 144.0 36.0 h 2 M 240.0 36.0 h 2',
    );
    // One value across the whole domain: a flat line through the middle.
    expect(linePath([at(0, 7), at(1, 7)], 0, 1, { low: 7, high: 7 })).toBe('M 0.0 36.0 L 240.0 36.0');
    expect(linePath([at(0, null)], 0, 0, domain)).toBe('');
  });

  it('starts a segment for every run of consecutive values, and stays in its box', () => {
    const random = seeded(20261121);
    for (let round = 0; round < 300; round++) {
      const points: Point[] = [];
      let index = random(3);
      for (let count = 1 + random(30); count > 0; count--) {
        points.push(at(index, random(5) === 0 ? null : random(1_000)));
        index += 1 + (random(6) === 0 ? 1 + random(3) : 0);
      }
      const first = Math.min(0, ...points.map((point) => point.index));
      const last = Math.max(...points.map((point) => point.index));
      const domain = yDomain([points]);
      if (domain === null) continue;
      const path = linePath([...points].reverse(), first, last, domain);
      let segments = 0;
      let previous: number | null = null;
      for (const point of points) {
        if (point.value !== null && (previous === null || point.index !== previous + 1)) segments++;
        previous = point.value === null ? null : point.index;
      }
      expect(path.split('M').length - 1).toBe(segments);
      for (const [, x, y] of path.matchAll(/[ML] (-?[\d.]+) (-?[\d.]+)/g)) {
        expect(Number(x)).toBeGreaterThanOrEqual(0);
        expect(Number(x)).toBeLessThanOrEqual(CHART_WIDTH);
        expect(Number(y)).toBeGreaterThanOrEqual(0);
        expect(Number(y)).toBeLessThanOrEqual(CHART_HEIGHT);
      }
    }
  });

  it('writes a volatility as a percentage', () => {
    expect(percent('0.008165')).toBe('0.8165%');
    expect(percent('0.000000')).toBe('0.0000%');
  });
});
