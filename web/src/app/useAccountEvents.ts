import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';

import type { StreamFactory, StreamSource } from '../terminal/useMarketStream';

/** The private queries an account change can make stale. */
const PRIVATE_QUERIES = [['portfolio'], ['orders'], ['own-trades']] as const;

/** Changes in quick succession, such as a run of fills, become one refetch after this pause. */
export const DEBOUNCE_MS = 150;

const browserStream: StreamFactory = (url) => new EventSource(url) as unknown as StreamSource;

/**
 * While a trader is signed in, listens for their account's change notifications and refetches their orders and
 * portfolio after each, debounced. Every connection starts with a frame, so a reconnect refetches too. The pages keep
 * polling every five seconds as well, so a lost notification never leaves balances stale for long.
 */
export function useAccountEvents(enabled: boolean, create: StreamFactory = browserStream) {
  const client = useQueryClient();
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
      }, DEBOUNCE_MS);
    });
    return () => {
      clearTimeout(pending);
      source.close();
    };
  }, [enabled, create, client]);
}
