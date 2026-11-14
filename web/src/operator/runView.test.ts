import { describe, expect, it } from 'vitest';

import type { Instrument } from '../api/types';
import { botRun } from '../test/botRun';
import { noTrades } from '../test/fakeStream';
import { seeded } from '../test/seeded';
import {
  activeRun,
  averageRate,
  type RunForm,
  runRequest,
  shortfallText,
  startBlocked,
  strategiesText,
  taggedSplit,
  workerText,
} from './runView';

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
    durationMinutes: '',
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

  it('says why a run cannot start: the market first, then the active run, then the bots', () => {
    expect(startBlocked('SETUP', botRun(), tagged(0, 0, 0))).toBe('Bots trade only once the market has opened.');
    expect(startBlocked('NOT_SET_UP', undefined, tagged(1, 3, 1))).toBe('Bots trade only once the market has opened.');
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
