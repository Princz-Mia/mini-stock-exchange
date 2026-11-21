import { describe, expect, it } from 'vitest';

import type { BotRun } from '../api/types';
import { botRun, finishedRun, runFigures } from '../test/botRun';
import { seeded } from '../test/seeded';
import {
  achievedRate,
  actualDuration,
  comparability,
  comparisonWarnings,
  durationText,
  fillRatio,
  MAX_COMPARED,
  marketMakerShare,
  parseRunIds,
  plannedDuration,
  refusalsText,
  runSeconds,
} from './compare';

const id = (n: number) => `20000000-0000-0000-0000-${String(n).padStart(12, '0')}`;

describe('the runs an address names', () => {
  it('keeps each run id once, in order, at most four, and names what it left out', () => {
    expect(parseRunIds(`${id(1)}, ${id(2)},${id(1)},nope,,${id(3)}`)).toEqual({
      ids: [id(1), id(2), id(3)],
      dropped: ['nope'],
      overflow: [],
    });
    expect(parseRunIds([1, 2, 3, 4, 5, 6, 5].map(id).join(','))).toEqual({
      ids: [id(1), id(2), id(3), id(4)],
      dropped: [],
      overflow: [id(5), id(6)],
    });
    expect(parseRunIds(id(1).toUpperCase()).ids).toEqual([id(1)]);
    expect(parseRunIds(null)).toEqual({ ids: [], dropped: [], overflow: [] });
  });

  it('holds for any list: unique, valid, first come first kept, and nothing valid lost', () => {
    const random = seeded(20261121);
    for (let round = 0; round < 300; round++) {
      const parts: string[] = [];
      for (let part = random(10); part > 0; part--) {
        const roll = random(10);
        parts.push(roll < 7 ? id(1 + random(8)) : roll < 9 ? `junk-${random(5)}` : '');
      }
      const { ids, dropped, overflow } = parseRunIds(parts.join(','));
      const valid = [...new Set(parts.filter((part) => part.startsWith('2000')))];
      expect(new Set(ids).size).toBe(ids.length);
      expect(ids.length).toBeLessThanOrEqual(MAX_COMPARED);
      expect([...ids, ...overflow]).toEqual(valid);
      expect(dropped).toEqual(parts.filter((part) => part.startsWith('junk')));
    }
  });
});

describe('a run for a comparison', () => {
  it('is finished once its window has an end, never when recorded before windows were kept', () => {
    expect(comparability(finishedRun())).toBe('FINISHED');
    expect(comparability(botRun())).toBe('UNFINISHED');
    // Stopped, but its worker has not confirmed it, so its window has no end yet.
    expect(comparability(botRun({ status: 'STOPPED', desiredState: 'STOPPED' }))).toBe('UNFINISHED');
    expect(
      comparability(botRun({ window: { startSequence: '1', stopSequence: null, endSequence: null, endedBy: 'NOT_KEPT' } })),
    ).toBe('NOT_KEPT');
  });

  it('has a market-maker share by bot count', () => {
    expect(marketMakerShare({ marketMakers: 1, noise: 2, momentum: 0 })).toBe('1 of 3 (33%)');
    expect(marketMakerShare({ marketMakers: 2, noise: 1, momentum: 1 })).toBe('2 of 4 (50%)');
    expect(marketMakerShare({ marketMakers: 0, noise: 1200, momentum: 0 })).toBe('0 of 1,200 (0%)');
    expect(marketMakerShare({ marketMakers: 0, noise: 0, momentum: 0 })).toBe('—');
  });

  it('ran from its start to its confirmed stop, or about to its last report, or for an unknown time', () => {
    expect(runSeconds(finishedRun())).toEqual({ seconds: 600, approximate: false });
    const ended = finishedRun({ stoppedAt: null });
    expect(runSeconds(ended)).toEqual({ seconds: 600, approximate: true });
    expect(actualDuration(ended)).toBe('About 10 min');
    const never = finishedRun({ stoppedAt: null, worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null } });
    expect(runSeconds(never)).toBeNull();
    expect(actualDuration(never)).toBe('Unknown');
    expect(plannedDuration(finishedRun())).toBe('Until stopped');
    expect(plannedDuration(finishedRun({ endsAt: '2026-11-06T11:00:00Z' }))).toBe('1 h 0 min');
  });

  it('writes lengths in hours, minutes and seconds', () => {
    expect(durationText(45)).toBe('45 s');
    expect(durationText(750)).toBe('12 min 30 s');
    expect(durationText(720)).toBe('12 min');
    expect(durationText(3_900)).toBe('1 h 5 min');
  });

  it('achieved the commands answered in its window over how long it ran', () => {
    // 600 + 300 + 800 + 100 placements and cancels in 600 seconds.
    expect(achievedRate(finishedRun(), runFigures())).toBe('3.00');
    expect(achievedRate(finishedRun(), null)).toBeNull();
    expect(achievedRate(finishedRun({ stoppedAt: null, worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null } }), runFigures())).toBeNull();
  });

  it('fills a share of what it placed, and counts its refusals by code', () => {
    const [makers, noise] = runFigures().strategies;
    expect(fillRatio(makers!)).toBe('62.5%');
    expect(fillRatio(noise!)).toBe('—');
    expect(refusalsText(makers!)).toBe('0');
    expect(refusalsText(noise!)).toBe('3 (INSUFFICIENT_CASH 2, MARKET_NOT_OPEN 1)');
  });
});

describe('why runs may not compare like for like', () => {
  const at = (rate: string, symbols: string[]): BotRun =>
    finishedRun({
      config: {
        ...finishedRun().config,
        commandsPerSecond: rate,
        instruments: symbols.map((symbol, index) => ({ instrumentId: id(100 + index), symbol })),
      },
    });

  it('names a different rate and different instruments, and nothing for alike runs', () => {
    expect(comparisonWarnings([at('4.00', ['NOVA']), at('4.00', ['NOVA'])])).toEqual([]);
    expect(comparisonWarnings([at('8.00', ['NOVA']), at('4.00', ['NOVA'])])).toEqual([
      'The runs asked for different rates (4.00, 8.00 commands a second), so more trading in one may be only its rate.',
    ]);
    expect(comparisonWarnings([at('4.00', ['NOVA', 'ORBT']), at('4.00', ['ORBT', 'NOVA'])])).toEqual([]);
    expect(comparisonWarnings([at('4.00', ['NOVA']), at('4.00', ['NOVA', 'ORBT'])])).toHaveLength(1);
  });

  it('says the same whatever order the runs come in', () => {
    const random = seeded(7);
    const rates = ['4.00', '8.00', '12.50'];
    const symbols = ['NOVA', 'ORBT', 'HELI'];
    for (let round = 0; round < 200; round++) {
      const runs = Array.from({ length: 2 + random(3) }, () =>
        at(rates[random(rates.length)]!, symbols.slice(0, 1 + random(symbols.length))),
      );
      const shuffled = [...runs].sort(() => random(3) - 1);
      expect(comparisonWarnings(shuffled)).toEqual(comparisonWarnings(runs));
    }
  });
});
