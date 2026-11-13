import type { SessionFigures } from '../api/types';
import { direction, trendPoints } from './session';

const WIDTH = 48;
const HEIGHT = 20;

/**
 * The last trade price of each recent minute as a small line, coloured by the session's direction. A picture of what
 * the row already says in text, so hidden from screen readers.
 */
export function Sparkline({ session }: { session: SessionFigures }) {
  const points = trendPoints(session.trend, WIDTH, HEIGHT);
  return (
    <svg
      className={`sparkline sparkline-${direction(session) ?? 'flat'}`}
      width={WIDTH}
      height={HEIGHT}
      viewBox={`-1 -1 ${WIDTH + 2} ${HEIGHT + 2}`}
      aria-hidden="true"
      focusable="false"
    >
      {points !== null && <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.5" />}
    </svg>
  );
}
