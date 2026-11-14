import { useCallback, useRef, useState } from 'react';

import { ApiError } from '../api/client';
import type { Problem } from '../api/types';

export type CommandOutcome<T> =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'done'; result: T }
  /**
   * Answered with a reason: committed refusals and invalid requests alike. A new attempt gets a new key. The problem is
   * kept for refusals that carry more than words, such as the holders of an instrument in use.
   */
  | { kind: 'refused'; code: string; message: string; problem: Problem | null }
  /** No final answer (busy, timed out, unreachable). Retrying resends the same request under the same key. */
  | { kind: 'unresolved' };

const randomKey = () => crypto.randomUUID();

/** The server's own words: its detail, plus each field's message for an invalid request. */
function describe(error: ApiError): string {
  const fields = error.problem?.errors ?? [];
  return fields.length === 0 ? error.message : fields.map((field) => field.message).join('; ');
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
      setOutcome({ kind: 'sending' });
      try {
        const result = await send(args, key);
        pending.current = null;
        setOutcome({ kind: 'done', result });
        return result;
      } catch (error) {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          pending.current = null;
          setOutcome({ kind: 'refused', code: error.code ?? 'REFUSED', message: describe(error), problem: error.problem });
        } else {
          setOutcome({ kind: 'unresolved' });
        }
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

  return { outcome, run, retry, unresolved: outcome.kind === 'unresolved' };
}
