import { createContext, useContext } from 'react';

/**
 * When the operator page opened, on the browser's clock. Coming back to the page draws the lists as they were cached
 * before, which may be from before a command sent meanwhile went through: a form drawn from them could offer Start
 * run for a run that has started, or Halt for a market already halted. So what may be sent is offered only from a
 * read made since the page opened. Zero, where no page says when it opened, takes every read as fresh.
 */
export const PageOpened = createContext(0);

/** Said in place of a form, or of why its button is off, until the exchange has been read since the page opened. */
export const CHECKING = 'Checking the exchange…';

/** The parts of a query's state that say when it was last read and last failed. */
interface Read {
  dataUpdatedAt: number;
  errorUpdatedAt: number;
}

export type Freshness = 'fresh' | 'checking' | 'unreachable';

/**
 * Whether a query has been read since the page opened: fresh once it has, unreachable when it has only failed since,
 * and checking until either.
 */
export function freshness(query: Read, opened: number): Freshness {
  if (query.dataUpdatedAt >= opened && query.dataUpdatedAt > 0) {
    return 'fresh';
  }
  return query.errorUpdatedAt >= opened && query.errorUpdatedAt > 0 ? 'unreachable' : 'checking';
}

/** The same, for a panel inside the page. */
export function useFreshRead(query: Read): Freshness {
  return freshness(query, useContext(PageOpened));
}

/** Why a form is not offered yet: the exchange is still being read, or has not answered since the page opened. */
export function notReadYet(read: Freshness): string | null {
  switch (read) {
    case 'fresh':
      return null;
    case 'checking':
      return CHECKING;
    case 'unreachable':
      return 'The exchange has not answered since this page opened, so nothing is offered until it does.';
  }
}
