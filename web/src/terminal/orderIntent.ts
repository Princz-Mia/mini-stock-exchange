import { useCallback, useEffect, useRef, useState } from 'react';

import { ApiError, api } from '../api/client';
import type { FieldError, Order, OrderRequest } from '../api/types';

/**
 * What became of one order the user meant to place. Only `accepted` and `rejected` are committed facts; `busy`,
 * `limited`, `unknown` and `notCommitted` are unresolved, and the next attempt reuses the same key and the same request.
 */
export type Outcome =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'accepted'; order: Order }
  | { kind: 'rejected'; code: string }
  | { kind: 'invalid'; errors: readonly FieldError[]; code: string }
  | { kind: 'busy' }
  | { kind: 'limited' }
  | { kind: 'unknown' }
  | { kind: 'notCommitted' };

export function isUnresolved(outcome: Outcome): boolean {
  return (
    outcome.kind === 'busy' || outcome.kind === 'limited' || outcome.kind === 'unknown' || outcome.kind === 'notCommitted'
  );
}

interface Pending {
  key: string;
  request: OrderRequest;
}

const LOOKUP_ATTEMPTS = 10;

const randomKey = () => crypto.randomUUID();

function sleep(milliseconds: number) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/**
 * Places orders so that a retry can never become a second order. Each new intent gets a fresh idempotency key;
 * while its outcome is unresolved, a retry resends the stored request under the same key. A timeout is resolved by
 * looking the key up: a committed answer settles it, and a 404 means nothing was committed, so retrying is safe.
 */
export function useOrderIntent(
  options: {
    lookupDelay?: number;
    newKey?: () => string;
    /** Each accepted order with the request it answers, as first committed. */
    onAccepted?: (order: Order, request: OrderRequest) => void;
  } = {},
) {
  const lookupDelay = options.lookupDelay ?? 1_000;
  const newKey = options.newKey ?? randomKey;
  const [outcome, setOutcome] = useState<Outcome>({ kind: 'idle' });
  const pending = useRef<Pending | null>(null);
  const inFlight = useRef(false);
  const accepted = useRef(options.onAccepted);
  useEffect(() => {
    accepted.current = options.onAccepted;
  }, [options.onAccepted]);

  const settle = useCallback((next: Outcome) => {
    if (next.kind === 'accepted' && pending.current !== null) {
      accepted.current?.(next.order, pending.current.request);
    }
    if (next.kind === 'accepted' || next.kind === 'rejected' || next.kind === 'invalid') {
      pending.current = null;
    }
    setOutcome(next);
  }, []);

  const resolveByKey = useCallback(
    async (key: string) => {
      for (let attempt = 0; attempt < LOOKUP_ATTEMPTS; attempt++) {
        try {
          settle({ kind: 'accepted', order: await api.placement(key) });
          return;
        } catch (error) {
          if (error instanceof ApiError && error.status === 404) {
            settle({ kind: 'notCommitted' });
            return;
          }
          if (error instanceof ApiError && error.status === 422) {
            settle({ kind: 'rejected', code: error.code ?? 'REJECTED' });
            return;
          }
        }
        await sleep(lookupDelay);
      }
      settle({ kind: 'unknown' });
    },
    [lookupDelay, settle],
  );

  /** Sends a new intent, or retries the unresolved one. Ignored while a request is in flight: no double submits. */
  const submit = useCallback(
    async (request: OrderRequest) => {
      if (inFlight.current) {
        return;
      }
      inFlight.current = true;
      const attempt = pending.current ?? { key: newKey(), request };
      pending.current = attempt;
      setOutcome({ kind: 'sending' });
      try {
        settle({ kind: 'accepted', order: await api.placeOrder(attempt.request, attempt.key) });
      } catch (error) {
        if (!(error instanceof ApiError)) {
          await resolveByKey(attempt.key);
        } else if (error.status === 422 && error.code !== 'UNKNOWN_INSTRUMENT') {
          settle({ kind: 'rejected', code: error.code ?? 'REJECTED' });
        } else if (error.status === 400 || error.status === 422) {
          settle({ kind: 'invalid', errors: error.problem?.errors ?? [], code: error.code ?? 'VALIDATION_FAILED' });
        } else if (error.status === 503 && error.code === 'EXCHANGE_BUSY') {
          settle({ kind: 'busy' });
        } else if (error.status === 429) {
          settle({ kind: 'limited' });
        } else if (error.status === 504) {
          await resolveByKey(attempt.key);
        } else {
          settle({ kind: 'invalid', errors: [], code: error.code ?? `HTTP_${error.status}` });
        }
      } finally {
        inFlight.current = false;
      }
    },
    [newKey, resolveByKey, settle],
  );

  /** The request that a retry would resend, if an intent is unresolved. */
  const pendingRequest = () => pending.current?.request ?? null;

  return { outcome, submit, pendingRequest };
}
