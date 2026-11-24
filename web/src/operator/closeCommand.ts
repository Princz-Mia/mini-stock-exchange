import { type QueryClient, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';

import { api } from '../api/client';
import type { MarketClosed } from '../api/types';
import { type CommandOutcome, failed } from './useCommand';

/**
 * The close of one market, as far as this session knows it. It is kept beside the cached queries, not in the panel
 * that sent it: a close can take half a minute and can end with no answer, and the operator may look at another page
 * meanwhile. Coming back finds the same close, under the same key. It goes with the session's cache at logout, and
 * never into browser storage.
 */
export interface CloseCommand {
  /** The market it was sent to. A close kept for another simulation is not this market's. */
  simulationId: string;
  /** Sent again as it is until the exchange gives a final answer. */
  key: string;
  outcome: CommandOutcome<MarketClosed>;
  /**
   * Set once the exchange has said the close is unfinished: how many orders still rested, if it said. From then on the
   * market is known to be closed, whatever a later answer under the same key says while the exchange is stopped.
   */
  stillResting: number | null | undefined;
  /** How often the key has been sent. */
  asks: number;
}

const CLOSE_COMMAND = ['close-command'] as const;

const NONE: CommandOutcome<MarketClosed> = { kind: 'idle' };

function kept(client: QueryClient): CloseCommand | null {
  return client.getQueryData<CloseCommand | null>(CLOSE_COMMAND) ?? null;
}

/** Sends the close under its key and records each turn it takes, whether or not a panel is there to show it. */
async function send(client: QueryClient, simulationId: string, key: string, before: CloseCommand | null): Promise<void> {
  const asks = (before?.asks ?? 0) + 1;
  const stillResting = before?.stillResting;
  // Only while this is still the close on record: one started later, for another market, is not overwritten.
  const record = (outcome: CommandOutcome<MarketClosed>, resting: number | null | undefined) => {
    if (outcome.kind === 'sending' || kept(client)?.key === key) {
      const turn: CloseCommand = { simulationId, key, outcome, stillResting: resting, asks };
      client.setQueryData<CloseCommand | null>(CLOSE_COMMAND, turn);
    }
  };
  record({ kind: 'sending', since: Date.now() }, stillResting);
  try {
    record({ kind: 'done', result: await api.closeMarket(key) }, undefined);
  } catch (error) {
    const outcome = failed<MarketClosed>(error);
    if (outcome.kind === 'unresolved') {
      const problem = outcome.problem;
      record(outcome, problem?.code === 'MARKET_CLOSE_UNFINISHED' ? (problem.restingOrders ?? null) : stillResting);
    } else {
      record(outcome, undefined);
    }
  } finally {
    void client.invalidateQueries({ queryKey: ['market'] });
  }
}

/**
 * The close command of the market on screen: one at a time, its key kept until the exchange gives a final answer, as
 * for every operator command, and kept through a change of page as well.
 */
export function useCloseCommand(simulationId: string | null) {
  const client = useQueryClient();
  const { data } = useQuery<CloseCommand | null>({
    queryKey: CLOSE_COMMAND,
    // Never fetched: written by the close itself, and a read of it changes nothing. Never dropped while the session
    // lasts, however long no page shows it.
    queryFn: () => kept(client),
    enabled: false,
    initialData: null,
    staleTime: Infinity,
    gcTime: Infinity,
  });
  const close = data !== null && data.simulationId === simulationId ? data : null;

  /** A new close, unless one is on its way or unresolved: then nothing is sent, or that one is sent again, unchanged. */
  const run = useCallback(() => {
    if (simulationId === null) {
      return;
    }
    const current = kept(client);
    const mine = current !== null && current.simulationId === simulationId ? current : null;
    if (mine?.outcome.kind === 'sending') {
      return;
    }
    void (mine?.outcome.kind === 'unresolved'
      ? send(client, simulationId, mine.key, mine)
      : send(client, simulationId, crypto.randomUUID(), null));
  }, [client, simulationId]);

  /** Drops a close that has its final answer, when another command takes its place on screen. */
  const forget = useCallback(() => {
    const kind = kept(client)?.outcome.kind;
    if (kind === 'done' || kind === 'refused') {
      client.setQueryData<CloseCommand | null>(CLOSE_COMMAND, null);
    }
  }, [client]);

  return {
    outcome: close?.outcome ?? NONE,
    stillResting: close?.stillResting,
    /** The key has been sent before: what is on its way now asks for the answer of a close already made. */
    askedBefore: (close?.asks ?? 0) > 1,
    run,
    forget,
  };
}
