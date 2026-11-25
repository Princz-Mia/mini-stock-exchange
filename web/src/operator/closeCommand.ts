import { useCallback } from 'react';

import { api } from '../api/client';
import type { MarketClosed } from '../api/types';
import { type Noting, useOperatorCommand } from './operatorCommands';

/** The market's status, which shows what a close did. */
const MARKET = ['market'] as const;
import type { CommandOutcome } from './useCommand';

/**
 * How many orders an unfinished close left resting, if the exchange said: set once it has said the close is
 * unfinished, and from then on the market is known to be closed, whatever a later answer under the same key says
 * while the exchange is stopped. Undefined while it has not said so.
 */
type StillResting = number | null;

const stillResting: Noting<MarketClosed, StillResting> = (outcome: CommandOutcome<MarketClosed>, before) => {
  switch (outcome.kind) {
    case 'done':
    case 'refused':
      return undefined;
    case 'unresolved':
      return outcome.problem?.code === 'MARKET_CLOSE_UNFINISHED' ? (outcome.problem.restingOrders ?? null) : before;
    default:
      return before;
  }
};

/**
 * The close command of the market on screen: one at a time, its key kept until the exchange gives a final answer, as
 * for every operator command, and kept through a change of page as well. A close can take half a minute.
 */
export function useCloseCommand(simulationId: string | null) {
  const send = useCallback((_: null, key: string) => api.closeMarket(key), []);
  // The status is read again with the answer, and the market is offered nothing more until it has been.
  const close = useOperatorCommand<null, MarketClosed, StillResting>('close', simulationId ?? '', send, {
    note: stillResting,
    confirmedBy: MARKET,
  });
  const { run: runClose, forget: forgetClose, outcome } = close;

  /** A new close, unless one is on its way or unresolved: then nothing is sent, or that one is sent again, unchanged. */
  const run = useCallback(() => {
    if (simulationId !== null) {
      runClose(null);
    }
  }, [simulationId, runClose]);

  /** Drops a close that has its final answer, when another command takes its place on screen. */
  const forget = useCallback(() => {
    if (outcome.kind === 'done' || outcome.kind === 'refused') {
      forgetClose();
    }
  }, [outcome.kind, forgetClose]);

  return {
    outcome,
    /** Closed, and the status has not been read since: it still shows the market as open. */
    awaitingRead: close.awaitingRead,
    stillResting: close.note,
    /** The key has been sent before: what is on its way now asks for the answer of a close already made. */
    askedBefore: close.asks > 1,
    run,
    forget,
  };
}
