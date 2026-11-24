import { describe, expect, it } from 'vitest';

import type { BotRun, Instrument } from '../api/types';
import { botRun, finishedRun } from '../test/botRun';
import { noTrades } from '../test/fakeStream';
import { seeded } from '../test/seeded';
import {
  activeRun,
  averageRate,
  elapsedSeconds,
  LEFT_RUNNING_AFTER_SECONDS,
  leftRunning,
  ranForSeconds,
  ranForText,
  remainingSeconds,
  type RunForm,
  runRequest,
  shortfallText,
  startBlocked,
  strategiesText,
  taggedSplit,
  timeLeftText,
  workerText,
} from './runView';

/** The fixture run started at 10:00:00 on 6 November; this is that many seconds later. */
function after(seconds: number): number {
  return Date.parse('2026-11-06T10:00:00Z') + seconds * 1000;
}

/** The same moment as the exchange writes it. */
function at(seconds: number): string {
  return new Date(after(seconds)).toISOString();
}

/** The window of a run the next start ended, that long after the fixture run's start. */
function endedByNextStart(seconds: number): BotRun['window'] {
  return { startSequence: '41', stopSequence: null, endSequence: '900', endedBy: 'NEXT_START', endedAt: at(seconds) };
}

const NO_WORKER = { workerId: null, heartbeatAt: null, leaseExpiresAt: null };

function tagged(marketMakers: number, noise: number, momentum: number, untagged = 0) {
  return { marketMakers, noise, momentum, untagged };
}

function instrument(id: string, symbol: string): Instrument {
  return {
    instrumentId: id,
    symbol,
    displayName: null,
    venueState: 'OPEN',
    referencePrice: '100.00',
    tick: '0.01',
    lotSize: 1,
    minQuantity: 1,
    maxQuantity: 10000,
    maxPrice: '1000000.00',
    lastPrice: null,
    bestBid: null,
    bestAsk: null,
    lastTradeSequence: '0',
    sourceCommandSequence: '1',
    generatedAt: '2026-11-06T10:00:00Z',
    session: noTrades('100.00'),
  };
}

const NOVA = instrument('00000000-0000-0000-0000-000000000001', 'NOVA');
const ORBT = instrument('00000000-0000-0000-0000-000000000002', 'ORBT');

function form(overrides: Partial<RunForm> = {}): RunForm {
  return {
    marketMakers: '1',
    noise: '2',
    momentum: '0',
    seed: '',
    commandsPerSecond: '4.00',
    maxOrderQuantity: '10',
    maxPosition: '100',
    durationMinutes: '60',
    untilStopped: false,
    instrumentIds: [NOVA.instrumentId, ORBT.instrumentId],
    ...overrides,
  };
}

describe('the bot run view', () => {
  it('names the strategies with bots, singular where it fits', () => {
    expect(strategiesText({ marketMakers: 1, noise: 2, momentum: 0 })).toBe('1 market maker, 2 noise');
    expect(strategiesText({ marketMakers: 2, noise: 0, momentum: 3 })).toBe('2 market makers, 3 momentum');
    expect(strategiesText({ marketMakers: 0, noise: 0, momentum: 0 })).toBe('No bots');
  });

  it('averages every answered command from the start to the last heartbeat', () => {
    // 200 commands in 80 seconds, against a budget of 4.00.
    expect(averageRate(botRun())).toBe('2.50');
    expect(averageRate(botRun({ worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null } }))).toBeNull();
    expect(averageRate(botRun({ startedAt: '2026-11-06T10:01:20Z' }))).toBeNull();
  });

  it('treats the newest run as active until it has stopped', () => {
    const stopped = botRun({ runId: 'old', status: 'STOPPED', desiredState: 'STOPPED' });
    expect(activeRun([])).toBeUndefined();
    expect(activeRun([stopped])).toBeUndefined();
    for (const status of ['STARTING', 'RUNNING', 'STALE', 'STOPPING'] as const) {
      expect(activeRun([botRun({ status }), stopped])?.status).toBe(status);
    }
  });

  it('counts whole seconds from the start, whatever the worker last reported, and never below zero', () => {
    expect(elapsedSeconds(botRun(), after(0))).toBe(0);
    expect(elapsedSeconds(botRun(), after(0.999))).toBe(0);
    expect(elapsedSeconds(botRun(), after(750))).toBe(750);
    // The fixture's worker last reported 80 seconds in; a run whose worker is lost keeps ageing.
    expect(elapsedSeconds(botRun({ status: 'STALE' }), after(13 * 86_400))).toBe(13 * 86_400);
    expect(elapsedSeconds(botRun({ worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null } }), after(9))).toBe(9);
    // A browser clock behind the exchange's.
    expect(elapsedSeconds(botRun(), after(-30))).toBe(0);
  });

  it('counts down to a timed run\'s end, and has nothing to count for a run with no end', () => {
    const timed = botRun({ endsAt: '2026-11-06T11:00:00Z' });
    expect(remainingSeconds(timed, after(0))).toBe(3_600);
    expect(remainingSeconds(timed, after(750))).toBe(2_850);
    expect(remainingSeconds(timed, after(3_599.5))).toBe(1);
    expect(remainingSeconds(timed, after(3_600))).toBe(0);
    expect(remainingSeconds(timed, after(3_700))).toBe(0);
    expect(remainingSeconds(botRun(), after(750))).toBeNull();

    expect(timeLeftText(timed, after(750))).toBe('47 min 30 s');
    expect(timeLeftText(timed, after(3_599.5))).toBe('1 s');
    expect(timeLeftText(botRun(), after(750))).toBeNull();
    // Once the operator has asked for a stop, the end time decides nothing.
    expect(timeLeftText({ ...timed, status: 'STOPPING', desiredState: 'STOPPED' }, after(750))).toBeNull();
    expect(timeLeftText({ ...timed, status: 'STOPPED' }, after(3_700))).toBeNull();
  });

  it('leaves it to the exchange to say a timed run has reached its end, whatever the clock reads', () => {
    const timed = botRun({ endsAt: '2026-11-06T11:00:00Z' });
    // Past its end the exchange shows the run as stopping, and as still wanting to run until its worker confirms.
    const overdue: BotRun = { ...timed, status: 'STOPPING' };
    expect(overdue.desiredState).toBe('RUNNING');
    expect(timeLeftText(overdue, after(3_604))).toBe('Ending now');
    // A clock half a minute behind the exchange's would still count 30 seconds.
    expect(timeLeftText(overdue, after(3_570))).toBe('Ending now');
    // A clock ahead of the exchange's is past the end while the run is running: it has not ended, so a second is left.
    for (const status of ['STARTING', 'RUNNING', 'STALE'] as const) {
      expect(timeLeftText({ ...timed, status }, after(3_600))).toBe('1 s');
      expect(timeLeftText({ ...timed, status }, after(3_600 + 360))).toBe('1 s');
    }
    // Six minutes ahead on a two-minute run that has just started: not over from its first second.
    const short = botRun({ endsAt: '2026-11-06T10:02:00Z' });
    expect(remainingSeconds(short, after(362))).toBe(0);
    expect(timeLeftText(short, after(362))).toBe('1 s');
  });

  it('takes a run for forgotten only with no end, no stop asked for, and more than a day behind it', () => {
    const day = LEFT_RUNNING_AFTER_SECONDS;
    expect(day).toBe(86_400);
    expect(leftRunning(botRun(), after(day - 1))).toBe(false);
    expect(leftRunning(botRun(), after(day))).toBe(false);
    expect(leftRunning(botRun(), after(day + 1))).toBe(true);
    expect(leftRunning(botRun(), after(13 * day))).toBe(true);
    // Its worker lost, it still trades again as soon as one takes over.
    expect(leftRunning(botRun({ status: 'STALE' }), after(day + 1))).toBe(true);
    // A timed run ends by itself, even while its end is overdue.
    expect(leftRunning(botRun({ endsAt: '2026-11-07T10:00:00Z' }), after(day + 1))).toBe(false);
    expect(leftRunning(botRun({ endsAt: '2026-11-06T11:00:00Z' }), after(2 * day))).toBe(false);
    expect(leftRunning(botRun({ status: 'STOPPING', desiredState: 'STOPPED' }), after(2 * day))).toBe(false);
  });

  it('never takes a timed run for forgotten, and always one with no end past a day', () => {
    const draw = seeded(1123);
    for (let i = 0; i < 500; i++) {
      const elapsed = draw(3 * 86_400);
      const minutes = 1 + draw(1_440);
      const timed = botRun({ endsAt: new Date(after(minutes * 60)).toISOString() });
      expect(leftRunning(timed, after(elapsed))).toBe(false);
      expect(leftRunning(botRun(), after(elapsed))).toBe(elapsed > 86_400);
      // The time gone and the time left make the run's length, until it is due.
      expect(elapsedSeconds(timed, after(elapsed)) + (remainingSeconds(timed, after(elapsed)) ?? 0)).toBe(
        Math.max(elapsed, minutes * 60),
      );
    }
  });

  it("gives the history a stopped run's length, and the time so far of one that is not over", () => {
    // Its worker confirmed the stop ten minutes in; now does not matter.
    expect(ranForText(finishedRun(), after(5 * 86_400))).toBe('10 min');
    expect(ranForSeconds(finishedRun(), after(5 * 86_400))).toBe(600);
    // Stopped half an hour in with its worker gone: the worker's last report stands in, 80 seconds in.
    const unconfirmed = botRun({ status: 'STOPPED', desiredState: 'STOPPED', stopRequestedAt: at(1_800) });
    expect(ranForText(unconfirmed, after(5 * 86_400))).toBe('About 1 min 20 s');
    expect(ranForSeconds(unconfirmed, after(5 * 86_400))).toBe(80);
    const unknown = botRun({ status: 'STOPPED', desiredState: 'STOPPED', stopRequestedAt: at(1_800), worker: NO_WORKER });
    expect(ranForText(unknown, after(600))).toBe('Unknown');
    expect(ranForSeconds(unknown, after(600))).toBe(-1);
    // Not over: from its start to now, not to its worker's last report.
    expect(ranForText(botRun(), after(750))).toBe('12 min 30 s so far');
    expect(ranForSeconds(botRun(), after(750))).toBe(750);
    expect(ranForText(botRun({ status: 'STALE' }), after(13 * 86_400 + 4 * 3_600))).toBe('13 d 4 h so far');
    expect(ranForSeconds(botRun({ status: 'STOPPING', desiredState: 'STOPPED' }), after(750))).toBe(750);
  });

  it("keeps a run the next start ended at its own length, not the time until that start", () => {
    const now = after(9 * 86_400);
    const reads = (run: BotRun) => [ranForText(run, now), ranForSeconds(run, now)];
    // No end, its worker lost 80 seconds in, stopped half an hour in with no worker to confirm.
    const stopped = botRun({ status: 'STOPPED', desiredState: 'STOPPED', stopRequestedAt: at(1_800) });
    expect(reads(stopped)).toEqual(['About 1 min 20 s', 80]);
    // Three days later the next run starts and ends its window: the row reads as it did.
    const ended = { ...stopped, window: endedByNextStart(3 * 86_400) };
    expect(reads(ended)).toEqual(['About 1 min 20 s', 80]);
    // Its worker comes back after that and confirms: the stop asked for bounds it, not the late report.
    const lateReport = at(3 * 86_400 + 600);
    const confirmedLate = {
      ...ended,
      stoppedAt: lateReport,
      worker: { workerId: 'sim-1', heartbeatAt: lateReport, leaseExpiresAt: lateReport },
    };
    expect(reads(confirmedLate)).toEqual(['About 30 min', 1_800]);

    // A 60-minute run no worker ever took. Nothing was reported, so nothing is known, before the next start and after.
    const never = botRun({ status: 'STOPPED', endsAt: at(3_600), worker: NO_WORKER });
    expect(reads(never)).toEqual(['Unknown', -1]);
    // The next start asks the overdue run to stop as of its end time, then ends its window.
    const neverEnded: BotRun = {
      ...never,
      desiredState: 'STOPPED',
      stopRequestedAt: at(3_600),
      window: endedByNextStart(3 * 86_400),
    };
    expect(reads(neverEnded)).toEqual(['Unknown', -1]);

    // A timed run whose worker last reported two seconds before its end, and never confirmed.
    const reported = (seconds: number) => ({ workerId: 'sim-1', heartbeatAt: at(seconds), leaseExpiresAt: at(seconds + 15) });
    const timed = botRun({ status: 'STOPPED', endsAt: at(3_600), worker: reported(3_598) });
    expect(reads(timed)).toEqual(['About 59 min 58 s', 3_598]);
    const timedEnded: BotRun = {
      ...timed,
      desiredState: 'STOPPED',
      stopRequestedAt: at(3_600),
      window: endedByNextStart(2 * 86_400),
    };
    expect(reads(timedEnded)).toEqual(['About 59 min 58 s', 3_598]);
    // One whose worker reported once more past the end, settling: the run's limit bounds it.
    expect(reads({ ...timedEnded, worker: reported(3_603) })).toEqual(['About 1 h 0 min', 3_600]);
    // An hour's run stopped ten minutes in, its worker reporting once more five seconds later and never confirming:
    // the stop asked for bounds it, not the end time it would have had.
    const cutShort: BotRun = { ...timed, desiredState: 'STOPPED', stopRequestedAt: at(600), worker: reported(605) };
    expect(reads(cutShort)).toEqual(['About 10 min', 600]);
    expect(reads({ ...cutShort, window: endedByNextStart(86_400) })).toEqual(['About 10 min', 600]);

    // A confirmed stop is exact, to the confirmation.
    expect(reads(finishedRun())).toEqual(['10 min', 600]);
  });

  it('never counts an unconfirmed run past its stop, its end time or its last report, whenever the next run started', () => {
    const draw = seeded(2311);
    for (let i = 0; i < 500; i++) {
      const own = 1 + draw(100_000);
      const report = 1 + draw(200_000);
      const next = own + draw(10 * 86_400);
      // A timed run is stopped at its end time, or by the operator up to two hours before it.
      const timed = draw(2) === 1;
      const run = botRun({
        status: 'STOPPED',
        desiredState: 'STOPPED',
        endsAt: timed ? at(own + draw(2) * draw(7_200)) : null,
        stopRequestedAt: at(own),
        worker: { workerId: 'sim-1', heartbeatAt: at(report), leaseExpiresAt: at(report + 15) },
      });
      const ended = { ...run, window: endedByNextStart(next) };
      const seconds = ranForSeconds(ended, after(next + 60));
      expect(seconds).toBe(Math.min(own, report));
      // The next run's start changes nothing about it.
      expect(ranForSeconds(run, after(next + 60))).toBe(seconds);
      expect(ranForText(ended, after(next + 60))).toBe(ranForText(run, after(own + 60)));
      expect(ranForText(ended, after(next + 60))).toMatch(/^About /);
    }
  });

  it('says why a run cannot start: the market first, then the active run, then the bots', () => {
    expect(startBlocked('SETUP', botRun(), tagged(0, 0, 0))).toBe('Bots trade only once the market has opened.');
    expect(startBlocked('NOT_SET_UP', undefined, tagged(1, 3, 1))).toBe('Bots trade only once the market has opened.');
    expect(startBlocked('CLOSED', undefined, tagged(1, 3, 1))).toBe('The market has closed; bots no longer trade.');
    expect(startBlocked('OPEN', botRun(), tagged(0, 0, 0))).toBe('A run is active; one runs at a time. Stop it first.');
    expect(startBlocked('HALTED', undefined, tagged(0, 0, 0))).toBe('There are no bots; they are created during setup.');
    expect(startBlocked('OPEN', undefined, tagged(0, 0, 0, 4))).toBe(
      'No bot has a strategy, and runs use only bots tagged with one.',
    );
    expect(startBlocked('OPEN', undefined, tagged(0, 3, 0))).toBeNull();
    expect(startBlocked('OPEN', undefined, tagged(0, 0, 1, 7))).toBeNull();
    expect(startBlocked('HALTED', undefined, undefined)).toBeNull();
  });

  it('describes the worker for each status', () => {
    expect(workerText(botRun({ status: 'STARTING' }))).toBe('Waiting for a simulator worker to take the run.');
    expect(workerText(botRun())).toBe('sim-1 holds the run.');
    expect(workerText(botRun({ status: 'STALE' }))).toBe(
      'No heartbeat from sim-1 since 10:01:20 UTC. Its bots place nothing until a worker takes over.',
    );
    expect(workerText(botRun({ status: 'STOPPED', stoppedAt: null }))).toBe('Stopped without a worker to confirm it.');
    expect(workerText(botRun({ status: 'STOPPED', stoppedAt: '2026-11-06T10:01:00Z' }))).toBe(
      'Stopped and confirmed by sim-1.',
    );
  });

  it('starts from every tagged bot running its strategy, leaving untagged bots out', () => {
    expect(taggedSplit(tagged(4, 12, 4, 3))).toEqual({ marketMakers: '4', noise: '12', momentum: '4' });
    expect(taggedSplit(tagged(1, 0, 0))).toEqual({ marketMakers: '1', noise: '0', momentum: '0' });
    expect(taggedSplit(undefined)).toEqual({ marketMakers: '0', noise: '0', momentum: '0' });
  });

  it('blocks a strategy asked for beyond its tagged bots, naming the first in assignment order', () => {
    const counts = (marketMakers: string, noise: string, momentum: string) => form({ marketMakers, noise, momentum });
    expect(shortfallText(counts('2', '12', '4'), tagged(2, 12, 4))).toBeNull();
    expect(shortfallText(counts('3', '12', '4'), tagged(2, 12, 4))).toBe('Only 2 market makers exist.');
    expect(shortfallText(counts('2', '13', '5'), tagged(2, 12, 4))).toBe('Only 12 noise bots exist.');
    expect(shortfallText(counts('0', '1', '2'), tagged(0, 1, 1))).toBe('Only 1 momentum bot exists.');
    expect(shortfallText(counts('1', '0', '0'), tagged(0, 5, 0, 9))).toBe('No bot is tagged as a market maker.');
    expect(shortfallText(counts('1', '1', '1'), tagged(1, 1, 0))).toBe('No bot is tagged as a momentum bot.');
    expect(shortfallText(counts('1', '2000', '0'), tagged(1, 1999, 0))).toBe('Only 1,999 noise bots exist.');
    // A count that is not a whole number is the form's to explain when it is sent.
    expect(shortfallText(counts('x', '', '9.5'), tagged(0, 0, 0))).toBeNull();
    expect(shortfallText(counts('5', '5', '5'), undefined)).toBeNull();
  });

  it('never blocks counts within the tagged population, and always blocks one beyond it', () => {
    const draw = seeded(2026);
    for (let i = 0; i < 500; i++) {
      const population = tagged(draw(50), draw(50), draw(50), draw(5));
      const within = form({
        marketMakers: String(draw(population.marketMakers + 1)),
        noise: String(draw(population.noise + 1)),
        momentum: String(draw(population.momentum + 1)),
      });
      expect(shortfallText(within, population)).toBeNull();
      const field = (['marketMakers', 'noise', 'momentum'] as const)[draw(3)]!;
      expect(shortfallText({ ...within, [field]: String(population[field] + 1) }, population)).not.toBeNull();
    }
  });

  it('sends what was typed, leaving blank optional fields and an all-instrument choice to the exchange', () => {
    expect(runRequest(form(), [NOVA, ORBT])).toEqual({
      request: {
        marketMakers: 1,
        noise: 2,
        momentum: 0,
        commandsPerSecond: '4.00',
        maxOrderQuantity: 10,
        maxPosition: 100,
        durationMinutes: 60,
      },
    });
    expect(
      runRequest(form({ seed: ' 42 ', durationMinutes: '30', instrumentIds: [ORBT.instrumentId] }), [NOVA, ORBT]),
    ).toEqual({
      request: {
        marketMakers: 1,
        noise: 2,
        momentum: 0,
        commandsPerSecond: '4.00',
        maxOrderQuantity: 10,
        maxPosition: 100,
        seed: 42,
        durationMinutes: 30,
        instrumentIds: [ORBT.instrumentId],
      },
    });
  });

  it('sends no duration only for a run chosen to last until stopped, whatever the minutes field still holds', () => {
    const sent = (overrides: Partial<RunForm>) => {
      const built = runRequest(form(overrides), [NOVA, ORBT]);
      return 'request' in built ? built.request : built;
    };
    expect(sent({ untilStopped: true })).not.toHaveProperty('durationMinutes');
    expect(sent({ untilStopped: true, durationMinutes: '' })).not.toHaveProperty('durationMinutes');
    expect(sent({ untilStopped: true, durationMinutes: 'soon' })).toEqual({
      marketMakers: 1,
      noise: 2,
      momentum: 0,
      commandsPerSecond: '4.00',
      maxOrderQuantity: 10,
      maxPosition: 100,
    });
    expect(sent({ durationMinutes: ' 1440 ' })).toHaveProperty('durationMinutes', 1440);
    // A set time with no minutes is never read as no end. The limits, 1 to 1440, are the exchange's to refuse.
    expect(sent({ durationMinutes: '' })).toEqual({ error: 'Duration must be a whole number.' });
    expect(sent({ durationMinutes: '  ' })).toEqual({ error: 'Duration must be a whole number.' });
    expect(sent({ durationMinutes: '0' })).toHaveProperty('durationMinutes', 0);
  });

  it('always sends the minutes of a set time, and never any for a run until stopped', () => {
    const draw = seeded(60);
    for (let i = 0; i < 300; i++) {
      const minutes = draw(2_000);
      const untilStopped = draw(2) === 1;
      const built = runRequest(form({ untilStopped, durationMinutes: String(minutes) }), [NOVA, ORBT]);
      expect('request' in built && built.request.durationMinutes).toBe(untilStopped ? undefined : minutes);
    }
  });

  it('refuses a form that the exchange could only misread', () => {
    expect(runRequest(form({ instrumentIds: [] }), [NOVA, ORBT])).toEqual({ error: 'Choose at least one instrument.' });
    expect(runRequest(form({ noise: '' }), [NOVA])).toEqual({ error: 'Noise must be a whole number.' });
    expect(runRequest(form({ marketMakers: '1.5' }), [NOVA])).toEqual({
      error: 'Market makers must be a whole number.',
    });
    expect(runRequest(form({ seed: '-3' }), [NOVA])).toEqual({ error: 'Seed must be a whole number.' });
    expect(runRequest(form({ durationMinutes: 'soon' }), [NOVA])).toEqual({ error: 'Duration must be a whole number.' });
    expect(runRequest(form({ maxPosition: '' }), [NOVA])).toEqual({ error: 'Position limit must be a whole number.' });
  });

  it('sends a subset of instruments exactly, and never an empty list', () => {
    const listed = Array.from({ length: 6 }, (_, i) => instrument(`id-${i}`, `S${i}`));
    const draw = seeded(7);
    for (let i = 0; i < 300; i++) {
      const chosen = listed.filter(() => draw(2) === 1).map((item) => item.instrumentId);
      const built = runRequest(form({ instrumentIds: chosen }), listed);
      if (chosen.length === 0) {
        expect(built).toEqual({ error: 'Choose at least one instrument.' });
      } else if (chosen.length === listed.length) {
        expect('request' in built && built.request.instrumentIds).toBe(undefined);
      } else {
        expect('request' in built && built.request.instrumentIds).toEqual(chosen);
      }
    }
  });
});
