import { describe, expect, it } from 'vitest';

import { botRun } from '../test/botRun';
import { seeded } from '../test/seeded';
import {
  loadedPercent,
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
