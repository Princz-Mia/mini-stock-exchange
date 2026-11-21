import { toCents } from '../terminal/money';

/** Each small chart's box: wide for the minutes, short so a row of runs fits a laptop. */
export const CHART_WIDTH = 240;

export const CHART_HEIGHT = 72;

/** The minute since the run started, the minute it started in being 0: runs line up from their start. */
export function minuteIndex(minute: string, startedAt: string): number {
  return Math.floor(Date.parse(minute) / 60_000) - Math.floor(Date.parse(startedAt) / 60_000);
}

/** A price in cents, as a number for drawing only; the amounts shown stay strings. */
export function drawnCents(price: string | null): number | null {
  return price === null ? null : Number(toCents(price));
}

export type Point = { index: number; value: number | null };

/** The lowest and highest value over every series, so a row of charts shares one scale; null with none. */
export function yDomain(series: readonly (readonly Point[])[]): { low: number; high: number } | null {
  const values = series.flatMap((points) => points.flatMap((point) => (point.value === null ? [] : [point.value])));
  return values.length === 0 ? null : { low: Math.min(...values), high: Math.max(...values) };
}

/**
 * An SVG path through the points at their minutes, in a box of the given size: the minutes `first` to `last` across,
 * the domain up the height, a flat line through the middle when the domain is one value. A missing value, or a minute
 * not given, breaks the line rather than joining across it; a value alone between breaks is a short dash. Empty with
 * no values.
 */
export function linePath(
  points: readonly Point[],
  first: number,
  last: number,
  domain: { low: number; high: number },
  width = CHART_WIDTH,
  height = CHART_HEIGHT,
): string {
  const x = (index: number) => (last === first ? width / 2 : ((index - first) / (last - first)) * width);
  const y = (value: number) =>
    domain.high === domain.low ? height / 2 : height - ((value - domain.low) / (domain.high - domain.low)) * height;
  const parts: string[] = [];
  let previous: number | null = null;
  let alone = false;
  for (const point of [...points].sort((a, b) => a.index - b.index)) {
    if (point.value === null) {
      if (alone) parts.push('h 2');
      previous = null;
      alone = false;
      continue;
    }
    const at = `${x(point.index).toFixed(1)} ${y(point.value).toFixed(1)}`;
    if (previous !== null && point.index === previous + 1) {
      parts.push(`L ${at}`);
      alone = false;
    } else {
      if (alone) parts.push('h 2');
      parts.push(`M ${at}`);
      alone = true;
    }
    previous = point.index;
  }
  if (alone) parts.push('h 2');
  return parts.join(' ');
}

/** "0.8165%": a volatility fraction as a percentage with four places. */
export function percent(fraction: string): string {
  return `${(Number(fraction) * 100).toFixed(4)}%`;
}
