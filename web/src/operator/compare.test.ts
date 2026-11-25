import { describe, expect, it } from 'vitest';

import type { BotRun } from '../api/types';
import { botRun, finishedRun, manifest, runFigures } from '../test/botRun';
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
  withTick,
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
      // Ids, some in capitals or with spaces round them, and near misses that are not ids at all.
      const near = (one: string) =>
        [`${one}x`, `x${one}`, one.slice(1), one.replaceAll('-', ''), one.replace('2', 'g')][random(5)]!;
      for (let part = random(10); part > 0; part--) {
        const one = id(1 + random(8));
        const roll = random(12);
        parts.push(roll < 5 ? one : roll < 7 ? one.toUpperCase() : roll < 8 ? `  ${one} ` : roll < 11 ? near(one) : '');
      }
      const { ids, dropped, overflow } = parseRunIds(parts.join(','));
      const isId = (part: string) => /^2000[0-9-]{32}$/.test(part.trim().toLowerCase()) && part.trim().length === 36;
      const valid = [...new Set(parts.filter(isId).map((part) => part.trim().toLowerCase()))];
      expect(new Set(ids).size).toBe(ids.length);
      expect(ids.length).toBeLessThanOrEqual(MAX_COMPARED);
      expect([...ids, ...overflow]).toEqual(valid);
      expect(dropped).toEqual(parts.filter((part) => part.trim() !== '' && !isId(part)).map((part) => part.trim()));
    }
  });
});

describe('ticking runs to compare', () => {
  it('adds a ticked run last and drops an unticked one, keeping the others in order', () => {
    expect(withTick(['a', 'b'], 'c', true)).toEqual(['a', 'b', 'c']);
    expect(withTick(['a', 'b', 'c'], 'b', false)).toEqual(['a', 'c']);
    expect(withTick(['a', 'b'], 'a', true)).toEqual(['b', 'a']);
    expect(withTick([], 'a', false)).toEqual([]);
  });

  it('never lists a run twice, and lists exactly the runs left ticked', () => {
    const random = seeded(20261121);
    for (let round = 0; round < 200; round++) {
      let ids: string[] = [];
      const on = new Set<string>();
      for (let step = 0; step < 20; step++) {
        const id = `run-${random(6)}`;
        const tick = random(2) === 0;
        ids = withTick(ids, id, tick);
        if (tick) on.add(id);
        else on.delete(id);
        expect(new Set(ids).size).toBe(ids.length);
        expect([...ids].sort()).toEqual([...on].sort());
        if (tick) expect(ids.at(-1)).toBe(id);
      }
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
      comparability(
        botRun({ window: { startSequence: '1', stopSequence: null, endSequence: null, endedBy: 'NOT_KEPT', endedAt: null } }),
      ),
    ).toBe('NOT_KEPT');
  });

  it('has a market-maker share by bot count', () => {
    expect(marketMakerShare({ marketMakers: 1, noise: 2, momentum: 0 })).toBe('1 of 3 (33%)');
    expect(marketMakerShare({ marketMakers: 2, noise: 1, momentum: 1 })).toBe('2 of 4 (50%)');
    expect(marketMakerShare({ marketMakers: 0, noise: 1200, momentum: 0 })).toBe('0 of 1,200 (0%)');
    expect(marketMakerShare({ marketMakers: 0, noise: 0, momentum: 0 })).toBe('—');
  });

  it('ran from its start to the end of its window, or about to its last report before that, or for an unknown time', () => {
    expect(runSeconds(finishedRun())).toEqual({ seconds: 600, approximate: false });
    // Ended by the next start at 10:07; its worker came back and confirmed the stop at 10:40, after the window.
    const nextStart = finishedRun({
      stoppedAt: '2026-11-06T10:40:00Z',
      worker: { workerId: 'sim-1', heartbeatAt: '2026-11-06T10:40:00Z', leaseExpiresAt: '2026-11-06T10:40:00Z' },
      window: { ...finishedRun().window, endedBy: 'NEXT_START', endedAt: '2026-11-06T10:07:00Z' },
    });
    expect(runSeconds(nextStart)).toEqual({ seconds: 420, approximate: false });
    expect(actualDuration(nextStart)).toBe('7 min');
    const going = botRun();
    expect(runSeconds(going)).toEqual({ seconds: 80, approximate: true });
    expect(actualDuration(going)).toBe('About 1 min 20 s');
    const never = botRun({ worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null } });
    expect(runSeconds(never)).toBeNull();
    expect(actualDuration(never)).toBe('Unknown');
    expect(plannedDuration(finishedRun())).toBe('Until stopped');
    expect(plannedDuration(finishedRun({ endsAt: '2026-11-06T11:00:00Z' }))).toBe('1 h 0 min');
  });

  it('writes lengths in days, hours, minutes and seconds', () => {
    expect(durationText(45)).toBe('45 s');
    expect(durationText(750)).toBe('12 min 30 s');
    expect(durationText(720)).toBe('12 min');
    expect(durationText(3_900)).toBe('1 h 5 min');
    expect(durationText(86_399)).toBe('23 h 59 min');
    expect(durationText(86_400)).toBe('1 d 0 h');
    // From 10 November 10:00 to 23 November 14:30.
    expect(durationText(13 * 86_400 + 4 * 3_600 + 30 * 60)).toBe('13 d 4 h');
  });

  it('achieved the commands answered in its window over how long it ran', () => {
    // 600 + 300 + 800 + 100 placements and cancels in 600 seconds.
    expect(achievedRate(finishedRun(), runFigures())).toBe('3.00');
    expect(achievedRate(finishedRun(), null)).toBeNull();
    expect(
      achievedRate(
        finishedRun({
          window: { ...finishedRun().window, endedAt: null },
          worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null },
        }),
        runFigures(),
      ),
    ).toBeNull();
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
    // In numeric order, which text order is not.
    expect(comparisonWarnings([at('12.50', ['NOVA']), at('4.00', ['NOVA'])])[0]).toContain('(4.00, 12.50 commands a second)');
    expect(comparisonWarnings([at('4.00', ['NOVA', 'ORBT']), at('4.00', ['ORBT', 'NOVA'])])).toEqual([]);
    expect(comparisonWarnings([at('4.00', ['NOVA']), at('4.00', ['NOVA', 'ORBT'])])).toHaveLength(1);
  });

  it('names simulators that differ, but not a run that reported none', () => {
    const plain = at('4.00', ['NOVA']);
    const tuned = { ...plain, manifest: manifest({ defaults: { ...manifest().defaults, maxInFlight: 64 } }) };
    const rebuilt = { ...tuned, manifest: { ...tuned.manifest, version: '0.1.0+9f00c0ffee12' } };

    expect(comparisonWarnings([plain, tuned])).toEqual([
      "The runs' simulators differ in 1 setting, marked under Simulator, so a difference may come from the code or its settings rather than the bots.",
    ]);
    expect(comparisonWarnings([rebuilt, plain])[0]).toContain('differ in 2 settings');
    expect(comparisonWarnings([plain, { ...tuned, manifest: null }])).toEqual([]);
    // However many runs, and wherever the one that differs is.
    expect(comparisonWarnings([plain, plain, plain, tuned])[0]).toContain('differ in 1 setting');
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
