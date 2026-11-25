import { type QueryClient, type QueryKey, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useReducer, useSyncExternalStore } from 'react';

import { type CommandOutcome, failed } from './useCommand';

/**
 * The operator commands kept beside the cached queries, one of each kind at a time: the market's transitions, its
 * close, a bot run's start and stop, a simulation's start, and a bot job's start and stop.
 */
export type CommandSlot =
  | 'market'
  | 'close'
  | 'run-start'
  | 'run-stop'
  | 'simulation-start'
  | 'bot-job-start'
  | 'bot-job-stop';

/**
 * One operator command as far as this session knows it. It is kept beside the cached queries, not in the panel that
 * sent it: a command can take a while, and can end with no answer, and the operator may look at another page
 * meanwhile. Coming back finds the same command, on its way or with its answer, under the same key. It goes with the
 * session's cache at logout, and never into browser storage, so a reload loses it; the page then reads the
 * exchange's state before it offers anything.
 */
export interface KeptCommand<A, T, N = undefined> {
  /** Whose it is: the simulation it was sent to. A command kept for another is not this one's. */
  scope: string;
  /** Sent again as it is until the exchange gives a final answer. */
  key: string;
  args: A;
  outcome: CommandOutcome<T>;
  /** When the outcome was final, on the browser's clock: what is read after it knows of the command. */
  answeredAt: number | null;
  /**
   * How many reads of the query that shows what the command did had landed when its answer came, if it names one.
   * Any read counted after that was asked for after the answer, so it shows what the command did.
   */
  readsBefore: number | null;
  /** How often the key has been sent. */
  asks: number;
  /** What a kind of command keeps besides its outcome, worked out at each turn, such as an unfinished close's count. */
  note: N | undefined;
  /**
   * The exchange's state has shown what it asked for while its answer was unknown: it went through, or another
   * operator did the same, and either way nothing is left to send. The next command gets a new key.
   */
  settled: boolean;
}

const slotKey = (slot: CommandSlot) => ['operator-command', slot] as const;

const IDLE: CommandOutcome<never> = { kind: 'idle' };

export function keptCommand<A, T, N = undefined>(client: QueryClient, slot: CommandSlot): KeptCommand<A, T, N> | null {
  return client.getQueryData<KeptCommand<A, T, N> | null>(slotKey(slot)) ?? null;
}

/** How a kind of command works out its note from each turn, and what it was before. */
export type Noting<T, N> = (outcome: CommandOutcome<T>, before: N | undefined) => N | undefined;

/**
 * Reads again the query that shows what a command did, and counts the reads it had before. A read of it already on
 * its way, asked for before the command was answered, is called off first, so every read counted after this one was
 * asked for after the answer. Null when the command names no such query.
 */
function readAgain(client: QueryClient, confirmedBy: QueryKey | undefined): number | null {
  if (confirmedBy === undefined) {
    return null;
  }
  void client.invalidateQueries({ queryKey: confirmedBy });
  return client.getQueryState(confirmedBy)?.dataUpdateCount ?? 0;
}

/** Sends a command under its key and records each turn it takes, whether or not a panel is there to show it. */
async function sendKept<A, T, N>(
  client: QueryClient,
  slot: CommandSlot,
  turn: Pick<KeptCommand<A, T, N>, 'scope' | 'key' | 'args' | 'asks' | 'note'>,
  send: (args: A, key: string) => Promise<T>,
  note: Noting<T, N> | undefined,
  confirmedBy: QueryKey | undefined,
): Promise<void> {
  let last = turn.note;
  // Only while this is still the command on record: one started later, for another market, is not overwritten.
  const record = (outcome: CommandOutcome<T>, readsBefore: number | null) => {
    if (outcome.kind === 'sending' || keptCommand(client, slot)?.key === turn.key) {
      last = note === undefined ? undefined : note(outcome, last);
      const final = outcome.kind === 'done' || outcome.kind === 'refused';
      client.setQueryData<KeptCommand<A, T, N>>(slotKey(slot), {
        ...turn,
        outcome,
        note: last,
        answeredAt: final ? Date.now() : null,
        readsBefore,
        settled: false,
      });
    }
  };
  record({ kind: 'sending', since: Date.now() }, null);
  let outcome: CommandOutcome<T>;
  try {
    outcome = { kind: 'done', result: await send(turn.args, turn.key) };
  } catch (error) {
    outcome = failed<T>(error);
  }
  // Read again and counted in the same turn as the answer is recorded, so that no read can land in between.
  record(outcome, readAgain(client, confirmedBy));
}

/** How many reads of a query have landed, kept up to date as they land. Zero for none named. */
function useReads(confirmedBy: QueryKey | undefined): number {
  const client = useQueryClient();
  const subscribe = useCallback((changed: () => void) => client.getQueryCache().subscribe(changed), [client]);
  return useSyncExternalStore(subscribe, () =>
    confirmedBy === undefined ? 0 : (client.getQueryState(confirmedBy)?.dataUpdateCount ?? 0),
  );
}

/**
 * The command of one kind for the market on screen: one at a time, its key kept until the exchange gives a final
 * answer, and kept through a change of page as well. `scope` is the simulation on screen, or any fixed name for a
 * command that belongs to no one market. `reached` says whether the exchange's state, as read since the page opened,
 * already shows what a command with these args asks for: an unresolved one is then settled, and not sent again.
 * `confirmedBy` is the query that shows what the command did, read again as soon as it is answered: until that read
 * has landed, the page still shows the state from before the command, and a form drawn from it could offer the same
 * command again. It is a constant, the same array at every render.
 */
export function useOperatorCommand<A, T, N = undefined>(
  slot: CommandSlot,
  scope: string,
  send: (args: A, key: string) => Promise<T>,
  options: { reached?: (args: A) => boolean; note?: Noting<T, N>; confirmedBy?: QueryKey } = {},
) {
  const client = useQueryClient();
  const { reached, note, confirmedBy } = options;
  const reads = useReads(confirmedBy);
  const { data } = useQuery<KeptCommand<A, T, N> | null>({
    queryKey: slotKey(slot),
    // Never fetched: written by the command itself, and a read of it changes nothing. Never dropped while the
    // session lasts, however long no page shows it.
    queryFn: () => keptCommand<A, T, N>(client, slot),
    enabled: false,
    initialData: null,
    staleTime: Infinity,
    gcTime: Infinity,
  });
  // The cache tells this panel of a change only after the event that made it, so the panel that sends or forgets a
  // command draws itself again at once: a button it disabled is disabled before anything else can be pressed.
  const [, redraw] = useReducer((count: number) => count + 1, 0);
  const mine = data !== null && data.scope === scope ? data : null;
  const showsReached =
    mine !== null && mine.outcome.kind === 'unresolved' && reached !== undefined && reached(mine.args);

  // An unresolved command whose effect the state shows is settled for good, so that a later read that no longer shows
  // it, once the run has ended or the market moved on, does not offer to send it again.
  const key = mine?.key;
  const settle = showsReached && mine?.settled === false;
  useEffect(() => {
    const current = keptCommand<A, T, N>(client, slot);
    if (settle && current !== null && current.key === key) {
      client.setQueryData<KeptCommand<A, T, N>>(slotKey(slot), { ...current, settled: true, answeredAt: Date.now() });
    }
  }, [client, slot, key, settle]);

  /** The command on record for this scope, as it is now rather than as last drawn. */
  const current = useCallback(() => {
    const kept = keptCommand<A, T, N>(client, slot);
    return kept !== null && kept.scope === scope ? kept : null;
  }, [client, slot, scope]);

  const isOpen = useCallback(
    (kept: KeptCommand<A, T, N> | null) =>
      kept !== null && kept.outcome.kind === 'unresolved' && !kept.settled && !(reached?.(kept.args) ?? false),
    [reached],
  );

  /** A new command, unless one is on its way or unresolved: then nothing is sent, or that one is sent again. */
  const run = useCallback(
    (args: A) => {
      const kept = current();
      if (kept?.outcome.kind === 'sending') {
        return;
      }
      if (kept !== null && isOpen(kept)) {
        void sendKept(client, slot, { ...kept, asks: kept.asks + 1 }, send, note, confirmedBy);
      } else {
        const turn = { scope, key: crypto.randomUUID(), args, asks: 1, note: undefined };
        void sendKept(client, slot, turn, send, note, confirmedBy);
      }
      redraw();
    },
    [client, slot, scope, send, note, confirmedBy, current, isOpen],
  );

  /** Sends an unresolved command again under its key, with what the page keeps beside it brought up to date. */
  const retry = useCallback(
    (update?: (args: A) => A) => {
      const kept = current();
      if (kept !== null && isOpen(kept)) {
        const args = update === undefined ? kept.args : update(kept.args);
        void sendKept(client, slot, { ...kept, args, asks: kept.asks + 1 }, send, note, confirmedBy);
        redraw();
      }
    },
    [client, slot, send, note, confirmedBy, current, isOpen],
  );

  /** Lets go of a command that is not on its way, when another takes its place on screen or it is settled. */
  const forget = useCallback(() => {
    const kept = current();
    if (kept !== null && kept.outcome.kind !== 'sending') {
      client.setQueryData<KeptCommand<A, T, N> | null>(slotKey(slot), null);
      redraw();
    }
  }, [client, slot, current]);

  const outcome: CommandOutcome<T> = mine?.outcome ?? IDLE;
  const settled = outcome.kind === 'unresolved' && (mine?.settled === true || showsReached);
  const readsBefore = mine?.readsBefore ?? null;
  return {
    outcome,
    /**
     * Done, and the query that shows what it did has not been read since the answer: what the page shows is still the
     * state from before it, so nothing drawn from that state is offered yet.
     */
    awaitingRead: outcome.kind === 'done' && readsBefore !== null && reads <= readsBefore,
    args: mine?.args,
    note: mine?.note,
    asks: mine?.asks ?? 0,
    answeredAt: mine?.answeredAt ?? null,
    /** No answer, and nothing the exchange has shown since says it applied: Retry sends it again. */
    unresolved: outcome.kind === 'unresolved' && !settled,
    /** No answer, but the exchange's state shows what it asked for. */
    settled,
    run,
    retry,
    forget,
  };
}
