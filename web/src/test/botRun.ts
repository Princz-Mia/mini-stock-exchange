import type { BotRun } from '../api/types';

/** A running run with one market maker and two noise bots: 200 answered commands in its first 80 seconds. */
export function botRun(overrides: Partial<BotRun> = {}): BotRun {
  return {
    runId: '20000000-0000-0000-0000-000000000001',
    status: 'RUNNING',
    desiredState: 'RUNNING',
    startedAt: '2026-11-06T10:00:00Z',
    endsAt: null,
    stopRequestedAt: null,
    stoppedAt: null,
    config: {
      seed: '7',
      commandsPerSecond: '4.00',
      maxOrderQuantity: 10,
      maxPosition: 100,
      instruments: [{ instrumentId: '00000000-0000-0000-0000-000000000001', symbol: 'NOVA' }],
      strategies: { marketMakers: 1, noise: 2, momentum: 0 },
    },
    worker: { workerId: 'sim-1', heartbeatAt: '2026-11-06T10:01:20Z', leaseExpiresAt: '2026-11-06T10:01:35Z' },
    activity: { placed: 150, canceled: 40, rejected: 6, errors: 4, inFlight: 1, stopFailures: 0 },
    ...overrides,
  };
}
