import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';

import { api, isRefusal } from '../api/client';

/** How often a running job is read: often enough for the bar to move with each part, about every second. */
export const RUNNING_POLL_MS = 1_000;

/** How often the job is read otherwise during setup, so that one another operator starts shows here too. */
export const IDLE_POLL_MS = 5_000;

/**
 * The current simulation's newest bot job, read from the exchange: the same progress on every screen, after a
 * reload, and after leaving the page and coming back. Read only while bots can be created. The bot population and
 * the account list are read again whenever the job has made more bots.
 */
export function useBotJob(enabled: boolean) {
  const client = useQueryClient();
  const job = useQuery({
    queryKey: ['bot-job'],
    queryFn: api.currentBotJob,
    enabled,
    refetchInterval: (query) =>
      isRefusal(query.state.error) ? false : query.state.data?.state === 'RUNNING' ? RUNNING_POLL_MS : IDLE_POLL_MS,
  });
  const created = job.data?.created;
  const state = job.data?.state;
  useEffect(() => {
    if (created !== undefined) {
      void client.invalidateQueries({ queryKey: ['bots'] });
      void client.invalidateQueries({ queryKey: ['accounts'] });
    }
  }, [client, created, state]);
  return job;
}
