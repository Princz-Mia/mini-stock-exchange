import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';

import type { StreamFactory, StreamSource } from '../terminal/useMarketStream';

/** The private queries an account change can make stale, and a market's close or a change of simulation with it. */
export const PRIVATE_QUERIES = [['portfolio'], ['orders'], ['own-trades']] as const;

/** Changes in quick succession, such as a run of fills, become one refetch after this pause. */
export const DEBOUNCE_MS = 150;

const browserStream: StreamFactory = (url) => new EventSource(url) as unknown as StreamSource;

/** EventSource's readyState once it has given up for good, such as after a refusal. */
const CLOSED = 2;

/**
 * While a trader is signed in, listens for their account's change notifications and refetches their orders and
 * portfolio after each, debounced, then calls `onChange`. Every connection starts with a frame, so a reconnect
 * refetches too. The pages keep polling every five seconds as well, so a lost notification never leaves balances stale
 * for long.
 *
 * The browser reconnects by itself after a dropped connection, but not after a refusal: a trader with no account in the
 * market is refused, as is everyone once a new simulation has started, until they are funded in it. A stream given up
 * on is opened again when `moment` changes, the moment an account may have been created (`fundingMoment`).
 */
export function useAccountEvents(
  enabled: boolean,
  create: StreamFactory = browserStream,
  onChange?: () => void,
  moment?: string,
) {
  const client = useQueryClient();
  // The latest callback, without reconnecting when it changes.
  const changed = useRef(onChange);
  const now = useRef(moment);
  useEffect(() => {
    changed.current = onChange;
    now.current = moment;
  }, [onChange, moment]);
  // How many streams were opened, and the moment the last one was given up on, if it was.
  const [stream, setStream] = useState<{ opened: number; gaveUpAt: string | undefined }>({ opened: 0, gaveUpAt: undefined });
  if (stream.gaveUpAt !== undefined && (moment ?? '') !== stream.gaveUpAt) {
    setStream({ opened: stream.opened + 1, gaveUpAt: undefined });
  }
  const opened = stream.opened;
  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    const source = create('/api/v1/account-events');
    let pending: ReturnType<typeof setTimeout> | undefined;
    source.addEventListener('account', () => {
      clearTimeout(pending);
      pending = setTimeout(() => {
        for (const queryKey of PRIVATE_QUERIES) {
          void client.invalidateQueries({ queryKey });
        }
        changed.current?.();
      }, DEBOUNCE_MS);
    });
    source.onerror = () => {
      if (source.readyState === CLOSED) {
        setStream((current) => (current.opened === opened ? { opened, gaveUpAt: now.current ?? '' } : current));
      }
    };
    return () => {
      clearTimeout(pending);
      source.close();
    };
  }, [enabled, create, client, opened]);
}
