import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';

import type { StreamFactory, StreamSource } from '../terminal/useMarketStream';
import { CLOSED, reopenDelay } from './reopen';

/** The private queries an account change can make stale, and a market's close or a change of simulation with it. */
export const PRIVATE_QUERIES = [['portfolio'], ['orders'], ['own-trades']] as const;

/** Changes in quick succession, such as a run of fills, become one refetch after this pause. */
export const DEBOUNCE_MS = 150;

const browserStream: StreamFactory = (url) => new EventSource(url) as unknown as StreamSource;

/**
 * While a trader is signed in, listens for their account's change notifications and refetches their orders and
 * portfolio after each, debounced, then calls `onChange`. Every connection starts with a frame, so a reconnect
 * refetches too, and tells of any fill missed meanwhile. The pages keep polling every five seconds as well, so a lost
 * notification never leaves balances stale for long.
 *
 * The browser reconnects by itself after a dropped connection, but not after a refusal or a proxy error: a trader with
 * no account in the market is refused, as is everyone once a new simulation has started until they are funded in it,
 * and the proxy answers 502 while Execution restarts. A stream given up on is opened again after 1, 2, 4 … and at most
 * 30 seconds, from one second again once a frame arrives, whatever the market is doing; and at once when `moment`
 * changes, the moment an account may have been created (`fundingMoment`).
 *
 * Returns whether a stream that had been delivering is being reconnected, for the page to say that live updates are
 * paused. A stream that has not delivered since the trader came, such as one refused for want of an account, is not
 * called lost.
 */
export function useAccountEvents(
  enabled: boolean,
  create: StreamFactory = browserStream,
  onChange?: () => void,
  moment?: string,
): { reconnecting: boolean } {
  const client = useQueryClient();
  // The latest callback, without reconnecting when it changes.
  const changed = useRef(onChange);
  useEffect(() => {
    changed.current = onChange;
  }, [onChange]);
  // Opens the stream at once while an attempt is waiting; set by the stream's effect.
  const retryNow = useRef<(() => void) | undefined>(undefined);
  const [lost, setLost] = useState(false);
  if (lost && !enabled) {
    setLost(false);
  }
  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    let source: StreamSource;
    let pending: ReturnType<typeof setTimeout> | undefined;
    // Attempts that failed since the last frame, the next attempt while one is waiting, and whether any stream opened
    // here has delivered.
    let failures = 0;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let delivered = false;
    const heard = () => {
      failures = 0;
      delivered = true;
      setLost(false);
    };
    const open = () => {
      retry = undefined;
      const opened = create('/api/v1/account-events');
      source = opened;
      opened.addEventListener('account', () => {
        heard();
        clearTimeout(pending);
        pending = setTimeout(() => {
          for (const queryKey of PRIVATE_QUERIES) {
            void client.invalidateQueries({ queryKey });
          }
          changed.current?.();
        }, DEBOUNCE_MS);
      });
      opened.addEventListener('heartbeat', heard);
      opened.onerror = () => {
        if (delivered) {
          setLost(true);
        }
        // Once given up on, a stream is done with: the attempt already waiting is the only one.
        if (opened.readyState === CLOSED && retry === undefined) {
          opened.close();
          retry = setTimeout(open, reopenDelay(failures));
          failures += 1;
        }
      };
    };
    retryNow.current = () => {
      if (retry !== undefined) {
        clearTimeout(retry);
        open();
      }
    };
    open();
    return () => {
      retryNow.current = undefined;
      clearTimeout(retry);
      clearTimeout(pending);
      source.close();
    };
  }, [enabled, create, client]);
  // An account may just have been created: a stream given up on need not wait out its pause.
  useEffect(() => {
    retryNow.current?.();
  }, [moment]);
  return { reconnecting: enabled && lost };
}
