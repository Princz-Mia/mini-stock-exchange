import { useCallback, useRef, useState } from 'react';

import { ApiError, NoAnswerError } from '../api/client';
import type { Problem } from '../api/types';

export type CommandOutcome<T> =
  | { kind: 'idle' }
  /** On its way since `since`, on the browser's clock: only for counting the seconds it has taken. */
  | { kind: 'sending'; since: number }
  | { kind: 'done'; result: T }
  /**
   * Answered with a reason: committed refusals and invalid requests alike. A new attempt gets a new key. The problem is
   * kept for refusals that carry more than words, such as the holders of an instrument in use.
   */
  | { kind: 'refused'; code: string; message: string; problem: Problem | null }
  /**
   * No final answer (busy, timed out, unreachable). Retrying resends the same request under the same key. The problem
   * is what the exchange said, if it said anything: a command that may take long tells a wait that ran out from an
   * exchange that has stopped. `waited` is set when no answer came at all before the browser stopped waiting, in
   * milliseconds.
   */
  | { kind: 'unresolved'; problem: Problem | null; waited?: number };

const randomKey = () => crypto.randomUUID();

/** The server's own words: its detail, plus each field's message for an invalid request. */
export function describe(error: ApiError): string {
  const fields = error.problem?.errors ?? [];
  return fields.length === 0 ? error.message : fields.map((field) => field.message).join('; ');
}

/**
 * What a command that failed came to: refused when the exchange answered with a reason, which is final, and otherwise
 * unresolved, to be sent again under the same key.
 */
export function failed<T>(error: unknown): CommandOutcome<T> {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return { kind: 'refused', code: error.code ?? 'REFUSED', message: describe(error), problem: error.problem };
  }
  return error instanceof NoAnswerError
    ? { kind: 'unresolved', problem: null, waited: error.waited }
    : { kind: 'unresolved', problem: error instanceof ApiError ? error.problem : null };
}

/**
 * One operator command at a time, with its idempotency key kept until the exchange gives a final answer. A retry
 * after "busy" or a lost answer resends the original request under the original key, so it can never apply twice.
 */
export function useCommand<A, T>(send: (args: A, key: string) => Promise<T>, newKey: () => string = randomKey) {
  const [outcome, setOutcome] = useState<CommandOutcome<T>>({ kind: 'idle' });
  const pending = useRef<{ key: string; args: A } | null>(null);
  const inFlight = useRef(false);

  const attempt = useCallback(
    async (key: string, args: A): Promise<T | undefined> => {
      if (inFlight.current) {
        return undefined;
      }
      inFlight.current = true;
      pending.current = { key, args };
      setOutcome({ kind: 'sending', since: Date.now() });
      try {
        const result = await send(args, key);
        pending.current = null;
        setOutcome({ kind: 'done', result });
        return result;
      } catch (error) {
        const outcome = failed<T>(error);
        if (outcome.kind === 'refused') {
          pending.current = null;
        }
        setOutcome(outcome);
        return undefined;
      } finally {
        inFlight.current = false;
      }
    },
    [send],
  );

  /** A new command, unless one is still unresolved: then that one is retried instead, unchanged. */
  const run = useCallback(
    (args: A) => (pending.current === null ? attempt(newKey(), args) : attempt(pending.current.key, pending.current.args)),
    [attempt, newKey],
  );

  const retry = useCallback(
    () => (pending.current === null ? Promise.resolve(undefined) : attempt(pending.current.key, pending.current.args)),
    [attempt],
  );

  /**
   * Lets go of an unresolved command, so that the next one gets a new key: only for one the caller knows has been
   * settled since, one way or the other. A command on its way is kept.
   */
  const forget = useCallback(() => {
    if (!inFlight.current) {
      pending.current = null;
      setOutcome({ kind: 'idle' });
    }
  }, []);

  return { outcome, run, retry, forget, unresolved: outcome.kind === 'unresolved' };
}
