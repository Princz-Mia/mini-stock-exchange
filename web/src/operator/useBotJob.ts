import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';

import { api, isRefusal } from '../api/client';
import type { BotJob } from '../api/types';

/** How often a running job is read: often enough for the bar to move with each part, about every second. */
export const RUNNING_POLL_MS = 1_000;

/** How often the job is read otherwise during setup, so that one another operator starts shows here too. */
export const IDLE_POLL_MS = 5_000;

/**
 * The current simulation's newest bot job, read from the exchange: the same progress on every screen, after a
 * reload, and after leaving the page and coming back. Read only while bots can be created. Each panel that shows it
 * reads it here, so that only those panels are drawn again with every read of a running job, not the whole page.
 */
export function useBotJob(enabled: boolean) {
  return useQuery({
    queryKey: ['bot-job'],
    queryFn: api.currentBotJob,
    enabled,
    refetchInterval: (query) =>
      isRefusal(query.state.error) ? false : query.state.data?.state === 'RUNNING' ? RUNNING_POLL_MS : IDLE_POLL_MS,
  });
}

/**
 * Reads the bot population and the account list again when a job starts, ends or stops, and only then: a running
 * job is read every second, and reading both lists with each of those reads put three requests on the wire each
 * second, more on a slow exchange. The progress bar has the count meanwhile. A read already on its way is left to
 * finish rather than stopped and asked again. The job as first read is the page as it opened, whose lists are read
 * as it opens anyway. Called by one panel only, so each change reads the lists once.
 */
export function useRefreshOnJobChange(job: BotJob | null | undefined) {
  const client = useQueryClient();
  // Undefined until the job has been read; null when the read found none.
  const turn = job === undefined ? undefined : job === null ? null : `${job.jobId}|${job.state}`;
  const seen = useRef<string | null | undefined>(undefined);
  useEffect(() => {
    if (turn === undefined) {
      return;
    }
    const before = seen.current;
    seen.current = turn;
    if (before !== undefined && before !== turn) {
      void client.invalidateQueries({ queryKey: ['bots'] }, { cancelRefetch: false });
      void client.invalidateQueries({ queryKey: ['accounts'] }, { cancelRefetch: false });
    }
  }, [client, turn]);
}
