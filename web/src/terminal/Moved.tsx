import type { ReactNode } from 'react';

import { arrow, type Direction } from './session';

/**
 * A figure that moved, coloured by its direction but never by colour alone: an arrow for the eye, and "up", "down" or
 * "unchanged" for a screen reader.
 */
export function Moved({ way, children }: { way: Direction; children: ReactNode }) {
  return (
    <span className={`change change-${way}`}>
      <span aria-hidden="true">{arrow(way)} </span>
      <span className="visually-hidden">{way === 'flat' ? 'unchanged,' : way}</span> {children}
    </span>
  );
}
