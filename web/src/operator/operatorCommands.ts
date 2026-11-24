import { type QueryClient, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useReducer } from 'react';

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
  /** How often the key has been sent. */
  asks: number;
  /** What a kind of command keeps besides its outcome, worked out at each turn, such as an unfinished close's count. */
  note: N | undefined;
  /**
   * The exchange's state has shown what it asked for while its answer was unknown: it went through, or another
   * operator did the same, and either way nothing is left to send. The next command gets a new key.
   */
  settled: boolean;
  /** This command took the place of one whose answer never came and that the exchange's state settled: its args. */
  after: A | undefined;
}

const slotKey = (slot: CommandSlot) => ['operator-command', slot] as const;

const IDLE: CommandOutcome<never> = { kind: 'idle' };

export function keptCommand<A, T, N = undefined>(client: QueryClient, slot: CommandSlot): KeptCommand<A, T, N> | null {
  return client.getQueryData<KeptCommand<A, T, N> | null>(slotKey(slot)) ?? null;
}

/** How a kind of command works out its note from each turn, and what it was before. */
export type Noting<T, N> = (outcome: CommandOutcome<T>, before: N | undefined) => N | undefined;

/** Sends a command under its key and records each turn it takes, whether or not a panel is there to show it. */
async function sendKept<A, T, N>(
  client: QueryClient,
  slot: CommandSlot,
  turn: Pick<KeptCommand<A, T, N>, 'scope' | 'key' | 'args' | 'asks' | 'note' | 'after'>,
  send: (args: A, key: string) => Promise<T>,
  note: Noting<T, N> | undefined,
): Promise<void> {
  let last = turn.note;
  // Only while this is still the command on record: one started later, for another market, is not overwritten.
  const record = (outcome: CommandOutcome<T>) => {
    if (outcome.kind === 'sending' || keptCommand(client, slot)?.key === turn.key) {
      last = note === undefined ? undefined : note(outcome, last);
      const final = outcome.kind === 'done' || outcome.kind === 'refused';
      client.setQueryData<KeptCommand<A, T, N>>(slotKey(slot), {
        ...turn,
        outcome,
        note: last,
        answeredAt: final ? Date.now() : null,
        settled: false,
      });
    }
  };
  record({ kind: 'sending', since: Date.now() });
  try {
    record({ kind: 'done', result: await send(turn.args, turn.key) });
  } catch (error) {
    record(failed<T>(error));
  }
}

/**
 * The command of one kind for the market on screen: one at a time, its key kept until the exchange gives a final
 * answer, and kept through a change of page as well. `scope` is the simulation on screen, or any fixed name for a
 * command that belongs to no one market. `reached` says whether the exchange's state, as read since the page opened,
 * already shows what a command with these args asks for: an unresolved one is then settled, and not sent again.
 */
export function useOperatorCommand<A, T, N = undefined>(
  slot: CommandSlot,
  scope: string,
  send: (args: A, key: string) => Promise<T>,
  options: { reached?: (args: A) => boolean; note?: Noting<T, N> } = {},
) {
  const client = useQueryClient();
  const { reached, note } = options;
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
        void sendKept(client, slot, { ...kept, asks: kept.asks + 1 }, send, note);
      } else {
        const after = kept !== null && kept.outcome.kind === 'unresolved' ? kept.args : undefined;
        const turn = { scope, key: crypto.randomUUID(), args, asks: 1, note: undefined, after };
        void sendKept(client, slot, turn, send, note);
      }
      redraw();
    },
    [client, slot, scope, send, note, current, isOpen],
  );

  /** Sends an unresolved command again under its key, with what the page keeps beside it brought up to date. */
  const retry = useCallback(
    (update?: (args: A) => A) => {
      const kept = current();
      if (kept !== null && isOpen(kept)) {
        const args = update === undefined ? kept.args : update(kept.args);
        void sendKept(client, slot, { ...kept, args, asks: kept.asks + 1 }, send, note);
        redraw();
      }
    },
    [client, slot, send, note, current, isOpen],
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
  return {
    outcome,
    args: mine?.args,
    note: mine?.note,
    asks: mine?.asks ?? 0,
    answeredAt: mine?.answeredAt ?? null,
    /** The args of an earlier command of this kind whose answer never came, which the state settled. */
    after: mine?.after,
    /** No answer, and nothing the exchange has shown since says it applied: Retry sends it again. */
    unresolved: outcome.kind === 'unresolved' && !settled,
    /** No answer, but the exchange's state shows what it asked for. */
    settled,
    run,
    retry,
    forget,
  };
}
