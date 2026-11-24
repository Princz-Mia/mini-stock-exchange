import type { BotJob } from '../api/types';

/** A bot job as the exchange answers it: the usual split of 20,000, nothing made yet, unless overridden. */
export function botJob(overrides: Partial<BotJob> = {}): BotJob {
  return {
    jobId: '30000000-0000-0000-0000-000000000001',
    state: 'RUNNING',
    total: 20_000,
    created: 0,
    strategies: {
      marketMakers: { requested: 4_000, created: 0 },
      noise: { requested: 12_000, created: 0 },
      momentum: { requested: 4_000, created: 0 },
    },
    cash: '100000.00',
    holdings: [],
    partSize: 1_000,
    partsDone: 0,
    startedAt: '2026-11-24T10:00:00Z',
    finishedAt: null,
    secondsLeft: null,
    failure: null,
    resumed: 0,
    ...overrides,
  };
}
