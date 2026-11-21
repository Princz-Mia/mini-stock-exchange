import type { BotRun, BotRunDetail, RunFigures } from '../api/types';

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
    window: { startSequence: '41', stopSequence: null, endSequence: null, endedBy: null, endedAt: null },
    manifest: null,
    ...overrides,
  };
}

/** A run its worker stopped and confirmed after ten minutes: its window has an end. */
export function finishedRun(overrides: Partial<BotRun> = {}): BotRun {
  return botRun({
    status: 'STOPPED',
    desiredState: 'STOPPED',
    stopRequestedAt: '2026-11-06T10:09:58Z',
    stoppedAt: '2026-11-06T10:10:00Z',
    worker: { workerId: 'sim-1', heartbeatAt: '2026-11-06T10:10:00Z', leaseExpiresAt: '2026-11-06T10:10:00Z' },
    activity: { placed: 1500, canceled: 400, rejected: 60, errors: 0, inFlight: 0, stopFailures: 0 },
    window: {
      startSequence: '41',
      stopSequence: '2100',
      endSequence: '2104',
      endedBy: 'WORKER',
      endedAt: '2026-11-06T10:10:00Z',
    },
    manifest: {
      version: '0.1.0+27a70d6c1e2f',
      workerId: 'sim-1',
      reportedAt: '2026-11-06T10:00:01Z',
      runtime: { python: '3.14.10', numpy: '2.5.4' },
      strategies: {},
      defaults: {},
      reports: 1,
    },
    ...overrides,
  });
}

/** Figures for NOVA and the market makers and noise bots: 1,800 placements and cancels in the window. */
export function runFigures(overrides: Partial<RunFigures> = {}): RunFigures {
  return {
    computedAt: '2026-11-06T10:10:05Z',
    instruments: [
      {
        instrumentId: '00000000-0000-0000-0000-000000000001',
        symbol: 'NOVA',
        trades: 320,
        volume: 1250,
        notional: '125430.50',
        startMark: { price: '100.00', source: 'REFERENCE' },
        endMark: { price: '101.25', source: 'LAST_TRADE' },
      },
    ],
    strategies: [
      {
        strategy: 'MARKET_MAKER',
        bots: 1,
        placements: 600,
        cancels: 300,
        refusals: [],
        placedQuantity: 6000,
        filledQuantity: 3750,
        trades: 200,
        volume: 800,
        notional: '80200.00',
        cashChange: '-150.25',
        holdingsStart: '10000.00',
        holdingsEnd: '10250.75',
        pnl: '100.50',
      },
      {
        strategy: 'NOISE',
        bots: 2,
        placements: 800,
        cancels: 100,
        refusals: [
          { code: 'INSUFFICIENT_CASH', commands: 2 },
          { code: 'MARKET_NOT_OPEN', commands: 1 },
        ],
        placedQuantity: 0,
        filledQuantity: 0,
        trades: 120,
        volume: 450,
        notional: '45230.50',
        cashChange: '150.25',
        holdingsStart: '20000.00',
        holdingsEnd: '19849.00',
        pnl: '-0.75',
      },
    ],
    ...overrides,
  };
}

export function runDetail(run: BotRun, figures: RunFigures | null = runFigures()): BotRunDetail {
  return { run, figures };
}
