import { describe, expect, it } from 'vitest';

import { botJob } from '../test/botJob';
import { seeded } from '../test/seeded';
import {
  MAX_BOTS,
  milestone,
  mixText,
  outcomeText,
  percentMade,
  progressText,
  resolveMix,
  strategiesText,
  timeLeftText,
} from './botJob';

describe('the strategy mix of a bot job', () => {
  it('gives the usual split as the demo seed makes it: a fifth each rounded down, noise the rest', () => {
    expect(resolveMix('20', { marketMakers: '20', momentum: '20' })).toEqual({
      total: 20,
      mix: { marketMakers: 4, noise: 12, momentum: 4 },
    });
    expect(resolveMix('8', { marketMakers: '20', momentum: '20' })).toEqual({
      total: 8,
      mix: { marketMakers: 1, noise: 6, momentum: 1 },
    });
    expect(resolveMix('4', { marketMakers: '20', momentum: '20' })).toEqual({
      total: 4,
      mix: { marketMakers: 0, noise: 4, momentum: 0 },
    });
    expect(resolveMix(' 20,000 ', { marketMakers: '20', momentum: '20' })).toEqual({
      total: 20_000,
      mix: { marketMakers: 4_000, noise: 12_000, momentum: 4_000 },
    });
  });

  it('always adds up to the number of bots, each share rounded down, over seeded totals and splits', () => {
    const draw = seeded(20261124);
    for (let i = 0; i < 2_000; i++) {
      const scale = [10, 1_000, 1_000_000, MAX_BOTS][draw(4)]!;
      const total = 1 + draw(scale);
      const makers = draw(101);
      const momentum = draw(101 - makers);
      const resolved = resolveMix(String(total), { marketMakers: String(makers), momentum: String(momentum) });
      if ('problem' in resolved) {
        throw new Error(`${total} at ${makers}% and ${momentum}%: ${resolved.problem}`);
      }
      const { mix } = resolved;
      expect(mix.marketMakers + mix.noise + mix.momentum).toBe(total);
      expect(mix.marketMakers).toBe(Math.floor((total * makers) / 100));
      expect(mix.momentum).toBe(Math.floor((total * momentum) / 100));
      expect(mix.noise).toBeGreaterThanOrEqual(0);
      for (const count of [mix.marketMakers, mix.noise, mix.momentum]) {
        expect(Number.isSafeInteger(count)).toBe(true);
      }
    }
  });

  it('says why a mix cannot be worked out', () => {
    const usual = { marketMakers: '20', momentum: '20' };
    expect(resolveMix('', usual)).toEqual({ problem: 'Type how many bots to create.' });
    for (const count of ['0', '-5', '1.5', 'many', String(MAX_BOTS + 1)]) {
      expect(resolveMix(count, usual)).toEqual({
        problem: 'The number of bots is a whole number from 1 to 2,147,483,647.',
      });
    }
    expect(resolveMix(String(MAX_BOTS), usual)).toHaveProperty('total', MAX_BOTS);
    expect(resolveMix('10', { marketMakers: '101', momentum: '0' })).toEqual({
      problem: 'Each share is a whole percentage from 0 to 100.',
    });
    expect(resolveMix('10', { marketMakers: '', momentum: '0' })).toEqual({
      problem: 'Each share is a whole percentage from 0 to 100.',
    });
    expect(resolveMix('10', { marketMakers: '60', momentum: '41' })).toEqual({
      problem: 'Market makers and momentum together can be at most 100%; noise is the rest.',
    });
    expect(resolveMix('10', { marketMakers: '60', momentum: '40' })).toEqual({
      total: 10,
      mix: { marketMakers: 6, noise: 0, momentum: 4 },
    });
  });

  it('names every count, so the sum can be checked', () => {
    expect(mixText({ marketMakers: 4_000, noise: 12_000, momentum: 4_000 })).toBe(
      '4,000 market makers, 12,000 noise and 4,000 momentum bots',
    );
    expect(mixText({ marketMakers: 1, noise: 0, momentum: 1 })).toBe('1 market maker, 0 noise and 1 momentum bot');
  });
});

describe("a bot job's progress in words", () => {
  it('counts, percentages and the strategies the job makes', () => {
    const job = botJob({
      created: 5_000,
      strategies: {
        marketMakers: { requested: 4_000, created: 1_000 },
        noise: { requested: 12_000, created: 3_000 },
        momentum: { requested: 4_000, created: 1_000 },
      },
    });
    expect(progressText(job)).toBe('5,000 of 20,000 bots made (25%)');
    expect(strategiesText(job)).toBe('Market makers 1,000 of 4,000 · Noise 3,000 of 12,000 · Momentum 1,000 of 4,000');
    expect(
      strategiesText(
        botJob({
          strategies: {
            marketMakers: { requested: 0, created: 0 },
            noise: { requested: 5, created: 2 },
            momentum: { requested: 0, created: 0 },
          },
        }),
      ),
    ).toBe('Noise 2 of 5');
    // Rounded down: 100% only once every bot is made.
    expect(percentMade({ created: 19_999, total: 20_000 })).toBe(99);
    expect(percentMade({ created: 20_000, total: 20_000 })).toBe(100);
  });

  it('the time left, from seconds to hours', () => {
    expect(timeLeftText(null)).toBe('Working out the time left…');
    expect(timeLeftText(0)).toBe('About 1 s left');
    expect(timeLeftText(30)).toBe('About 30 s left');
    expect(timeLeftText(60)).toBe('About 1 min left');
    expect(timeLeftText(61)).toBe('About 2 min left');
    expect(timeLeftText(3_600)).toBe('About 1 h left');
    expect(timeLeftText(3_900)).toBe('About 1 h 5 min left');
  });

  it('what a finished job came to, with what it made', () => {
    expect(
      outcomeText(
        botJob({
          state: 'DONE',
          created: 20_000,
          strategies: {
            marketMakers: { requested: 4_000, created: 4_000 },
            noise: { requested: 12_000, created: 12_000 },
            momentum: { requested: 4_000, created: 4_000 },
          },
        }),
      ),
    ).toBe('Created 20,000 bots: 4,000 market makers, 12,000 noise and 4,000 momentum bots.');
    expect(outcomeText(botJob({ state: 'STOPPED', created: 3_000 }))).toBe(
      'Stopped after 3,000 of 20,000 bots. The 3,000 made stay. The rest were not made.',
    );
    expect(outcomeText(botJob({ state: 'STOPPED', created: 0 }))).toBe(
      'Stopped after 0 of 20,000 bots. No bot was made. The rest were not made.',
    );
    expect(outcomeText(botJob({ state: 'FAILED', created: 2_000, failure: 'Bots could not be made: disk full' }))).toBe(
      'Creating bots failed after 2,000 of 20,000: Bots could not be made: disk full. The 2,000 made stay. Create the rest once the cause is fixed.',
    );
  });

  it('is announced at the start, at each quarter and at the end, and not in between', () => {
    expect(milestone(botJob())).toBe('Creating 20,000 bots.');
    expect(milestone(botJob({ created: 4_999 }))).toBe('Creating 20,000 bots.');
    expect(milestone(botJob({ created: 5_000 }))).toBe('25% of the 20,000 bots are made.');
    // Between two quarters the words do not change, so nothing new is said.
    expect(milestone(botJob({ created: 9_999 }))).toBe('25% of the 20,000 bots are made.');
    expect(milestone(botJob({ created: 10_000 }))).toBe('50% of the 20,000 bots are made.');
    expect(milestone(botJob({ state: 'STOPPED', created: 6_000 }))).toBe(
      'Stopped after 6,000 of 20,000 bots. The 6,000 made stay. The rest were not made.',
    );
  });
});
