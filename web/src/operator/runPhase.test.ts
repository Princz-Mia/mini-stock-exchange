import { describe, expect, it } from 'vitest';

import { botRun } from '../test/botRun';
import { seeded } from '../test/seeded';
import {
  loadedPercent,
  type LoadingBeat,
  loadingSecondsLeft,
  pausedByReasonText,
  pausedTotal,
  pauseReason,
  runMilestone,
  runPhase,
  runTime,
} from './runPhase';

const NO_WORKER = { workerId: null, heartbeatAt: null, leaseExpiresAt: null };

describe('runPhase', () => {
  it('follows the status, and the worker’s phase while it holds the run', () => {
    expect(runPhase(botRun({ status: 'STARTING', worker: NO_WORKER, progress: null }))).toBe('WAITING');
    expect(runPhase(botRun({ progress: { phase: 'LOADING', botsLoaded: 0, botsTotal: 3 } }))).toBe('LOADING');
    expect(runPhase(botRun())).toBe('TRADING');
    expect(runPhase(botRun({ progress: null }))).toBe('HELD');
    expect(runPhase(botRun({ status: 'STALE' }))).toBe('LOST');
    expect(runPhase(botRun({ status: 'STOPPING' }))).toBe('STOPPING');
    expect(runPhase(botRun({ status: 'STOPPED' }))).toBe('STOPPED');
  });
});

describe('loadedPercent', () => {
  it('rounds down, so 100% means every bot is built', () => {
    expect(loadedPercent({ phase: 'LOADING', botsLoaded: 19_999, botsTotal: 20_000 })).toBe(99);
    expect(loadedPercent({ phase: 'LOADING', botsLoaded: 20_000, botsTotal: 20_000 })).toBe(100);
    expect(loadedPercent({ phase: 'LOADING', botsLoaded: 1, botsTotal: 3 })).toBe(33);
  });

  it('counts a run of no bots as loaded, and never goes past 100%', () => {
    expect(loadedPercent({ phase: 'LOADING', botsLoaded: 0, botsTotal: 0 })).toBe(100);
    expect(loadedPercent({ phase: 'LOADING', botsLoaded: 7, botsTotal: 5 })).toBe(100);
  });

  it('stays within 0 to 100 and never falls as more bots load, for drawn sizes', () => {
    const random = seeded(141);
    for (let draw = 0; draw < 500; draw++) {
      const total = 1 + random(2_000_000);
      const loaded = random(total);
      const percent = loadedPercent({ phase: 'LOADING', botsLoaded: loaded, botsTotal: total });
      expect(percent).toBeGreaterThanOrEqual(0);
      expect(percent).toBeLessThan(100);
      expect(loadedPercent({ phase: 'LOADING', botsLoaded: loaded + 1, botsTotal: total })).toBeGreaterThanOrEqual(percent);
    }
  });
});

describe('loadingSecondsLeft', () => {
  const beat = (seconds: number, botsLoaded: number, botsTotal = 20_000): LoadingBeat => ({
    at: new Date(Date.parse('2026-11-06T10:00:00Z') + seconds * 1000).toISOString(),
    progress: { phase: 'LOADING', botsLoaded, botsTotal },
  });

  it('goes by the pace between the two heartbeats, rounded up', () => {
    // 4,000 bots in 5 s: the 12,000 left take 15 s.
    expect(loadingSecondsLeft(beat(10, 8_000), beat(5, 4_000))).toBe(15);
    // 3 bots in 5 s: the 19,994 left take 33,323.3 s, said as 33,324.
    expect(loadingSecondsLeft(beat(10, 6), beat(5, 3))).toBe(33_324);
    expect(loadingSecondsLeft(beat(10, 20_000), beat(5, 19_000))).toBe(0);
  });

  it('has no pace to go by before two heartbeats of the same loading, or when none loaded between them', () => {
    expect(loadingSecondsLeft(beat(5, 4_000), null)).toBeNull();
    expect(loadingSecondsLeft(beat(10, 4_000), beat(5, 4_000))).toBeNull();
    // A worker that took the run over loads it again from none.
    expect(loadingSecondsLeft(beat(10, 500), beat(5, 9_000))).toBeNull();
    expect(loadingSecondsLeft(beat(10, 8_000), beat(10, 4_000))).toBeNull();
    expect(loadingSecondsLeft(beat(10, 8_000), beat(5, 4_000, 10_000))).toBeNull();
    expect(loadingSecondsLeft(beat(10, 8_000), { at: null, progress: beat(5, 4_000).progress })).toBeNull();
    const trading = { at: beat(10, 0).at, progress: { phase: 'TRADING', botsLoaded: 20_000, botsTotal: 20_000 } } as const;
    expect(loadingSecondsLeft(trading, beat(5, 4_000))).toBeNull();
  });

  it('is enough for the bots left at that pace, and less than a second more, for drawn loadings', () => {
    const random = seeded(1411);
    for (let draw = 0; draw < 500; draw++) {
      const total = 2 + random(2_000_000);
      const before = random(total - 1);
      const now = before + 1 + random(total - before - 1);
      const gap = 1 + random(30);
      const left = loadingSecondsLeft(beat(gap, now, total), beat(0, before, total)) as number;
      const pace = (now - before) / gap;
      expect(left * pace).toBeGreaterThanOrEqual(total - now - 1e-6);
      expect((left - 1) * pace).toBeLessThan(total - now);
    }
  });
});

describe('runMilestone', () => {
  const at = (botsLoaded: number) => runMilestone(botRun({ progress: { phase: 'LOADING', botsLoaded, botsTotal: 20_000 } }));

  it('changes only at a change of phase and at each quarter of the loading', () => {
    expect(at(0)).toBe('Loading 20,000 bots.');
    expect(at(4_999)).toBe('Loading 20,000 bots.');
    expect(at(5_000)).toBe('25% of the 20,000 bots are loaded.');
    expect(at(9_999)).toBe('25% of the 20,000 bots are loaded.');
    expect(at(10_000)).toBe('50% of the 20,000 bots are loaded.');
    expect(at(15_000)).toBe('75% of the 20,000 bots are loaded.');
    expect(at(19_999)).toBe('75% of the 20,000 bots are loaded.');
    expect(runMilestone(botRun())).toBe('Every bot is loaded; the run is trading.');
    expect(runMilestone(botRun({ status: 'STARTING', worker: NO_WORKER, progress: null }))).toBe(
      'The run is waiting for a simulator worker.',
    );
    expect(runMilestone(botRun({ status: 'STALE' }))).toBe("The run's heartbeats stopped; its bots place nothing.");
    expect(runMilestone(botRun({ status: 'STOPPING', desiredState: 'STOPPED' }))).toBe('The run is stopping.');
    // A stop during loading is a change of phase too, said once, and not followed by the loading's quarters.
    const loadingWhenStopped = { phase: 'LOADING', botsLoaded: 15_000, botsTotal: 20_000 } as const;
    expect(runMilestone(botRun({ status: 'STOPPING', desiredState: 'STOPPED', progress: loadingWhenStopped }))).toBe(
      'The run is stopping.',
    );
    expect(runMilestone(botRun({ progress: null }))).toBe('A simulator worker holds the run.');
    expect(runMilestone(botRun({ status: 'STOPPED', desiredState: 'STOPPED' }))).toBe('The run has stopped.');
    expect(runMilestone(botRun({ progress: { phase: 'LOADING', botsLoaded: 0, botsTotal: 1 } }))).toBe('Loading 1 bot.');
  });
});

describe('pauseReason', () => {
  const activity = (changes: Partial<ReturnType<typeof botRun>['activity']>) => ({ ...botRun().activity, ...changes });

  it('explains a run seen once only when it has placed nothing at all', () => {
    expect(pauseReason(activity({ placed: 0, pausedOther: 1 }), null)).toBe('pausedOther');
    expect(pauseReason(activity({ placed: 1, pausedOther: 100 }), null)).toBeNull();
    expect(pauseReason(activity({ placed: 0 }), null)).toBeNull();
  });

  it('between heartbeats, names the reason that grew most while nothing more was placed', () => {
    const before = activity({ placed: 10, pausedStaleData: 100, pausedMarketNotOpen: 0 });
    expect(pauseReason(activity({ placed: 10, pausedStaleData: 101, pausedMarketNotOpen: 2 }), before)).toBe(
      'pausedMarketNotOpen',
    );
    expect(pauseReason(activity({ placed: 11, pausedStaleData: 150 }), before)).toBeNull();
  });
});

describe('the paused counts', () => {
  it('add up the reasons and list those with any', () => {
    const activity = { ...botRun().activity, pausedStaleData: 1_204, pausedMarketNotOpen: 0, pausedOther: 3 };
    expect(pausedTotal(activity)).toBe(1_207);
    expect(pausedByReasonText(activity)).toBe('market data stale 1,204 · other 3');
    const every = { ...activity, pausedMarketNotOpen: 12 };
    expect(pausedTotal(every)).toBe(1_219);
    expect(pausedByReasonText(every)).toBe('market data stale 1,204 · market not open 12 · other 3');
    expect(pausedByReasonText(botRun().activity)).toBe('');
  });
});

describe('runTime', () => {
  it('measures a timed run from its start to its end, within its length', () => {
    const run = botRun({ endsAt: '2026-11-06T10:10:00Z' });
    const start = Date.parse(run.startedAt);
    expect(runTime(run, start - 5_000)).toEqual({ elapsed: 0, length: 600 });
    expect(runTime(run, start + 90_500)).toEqual({ elapsed: 90, length: 600 });
    expect(runTime(run, start + 3_600_000)).toEqual({ elapsed: 600, length: 600 });
    expect(runTime(botRun(), start)).toBeNull();
  });
});
