import { describe, expect, it } from 'vitest';

import type { BotRunManifest } from '../api/types';
import { manifest } from '../test/botRun';
import { seeded } from '../test/seeded';
import { differingSettings, type ManifestColumn, settingGroups, settingText, simulatorText } from './manifest';

type Setting = string | number | boolean;
/** A manifest to change in a test: the API's type is read-only. */
type Editable = Omit<BotRunManifest, 'version' | 'runtime' | 'strategies' | 'defaults'> & {
  version: string;
  runtime: Record<string, string>;
  strategies: Record<string, Record<string, Setting>>;
  defaults: Record<string, Setting>;
};

const editable = (): Editable => structuredClone(manifest());

const changed = (edit: (copy: Editable) => void): BotRunManifest => {
  const copy = editable();
  edit(copy);
  return copy;
};

/** Each row by group and name: its values, and an asterisk when it differs. */
function table(columns: readonly ManifestColumn[]): string[] {
  return settingGroups(columns).flatMap((group) =>
    group.rows.map((row) => `${group.label} ${row.name}${row.differs ? '*' : ''}: ${row.values.join(' | ')}`),
  );
}

describe('the simulator a run reported', () => {
  it('names its version, and says when another took over midway', () => {
    expect(simulatorText(null)).toBe('Not reported');
    expect(simulatorText(manifest())).toBe('0.1.0+27a70d6c1e2f');
    expect(simulatorText(manifest({ reports: 3 }))).toBe('0.1.0+27a70d6c1e2f · changed during the run');
    expect(settingText(undefined)).toBe('—');
    expect(settingText(true)).toBe('Yes');
    expect(settingText(false)).toBe('No');
    expect(settingText(0.001)).toBe('0.001');
    expect(settingText('fast')).toBe('fast');
  });

  it('lists version and runtime, each strategy in order, then the defaults', () => {
    expect(settingGroups([manifest()]).map((group) => group.label)).toEqual([
      'Version and runtime',
      'Market makers',
      'Noise',
      'Momentum',
      'Defaults',
    ]);
    const rows = table([manifest()]);
    expect(rows.slice(0, 3)).toEqual([
      'Version and runtime version: 0.1.0+27a70d6c1e2f',
      'Version and runtime numpy: 2.5.4',
      'Version and runtime python: 3.14.10',
    ]);
    expect(rows).toContain('Momentum threshold: 0.001');
    expect(rows.some((each) => each.includes('*'))).toBe(false);
    // A strategy the screens don't know yet comes after the known ones, under its own name.
    const newer = changed((copy) => {
      copy.strategies = { ARBITRAGE: { lagTicks: 3 }, NOISE: copy.strategies.NOISE! };
    });
    expect(settingGroups([newer]).map((group) => group.label)).toEqual([
      'Version and runtime',
      'Noise',
      'ARBITRAGE',
      'Defaults',
    ]);
  });

  it('marks a setting that differs, one missing, or a version, but not runs that reported nothing', () => {
    const other = changed((copy) => {
      copy.version = '0.1.0+9f00c0ffee12.dirty';
      copy.defaults.maxInFlight = 64;
      delete copy.strategies.NOISE!.spreadTicks;
      copy.strategies.MARKET_MAKER!.skewTicks = true;
    });
    const rows = table([manifest(), null, other, undefined]);

    expect(rows).toContain(
      'Version and runtime version*: 0.1.0+27a70d6c1e2f | Not reported | 0.1.0+9f00c0ffee12.dirty | —',
    );
    expect(rows).toContain('Defaults maxInFlight*: 32 | Not reported | 64 | —');
    expect(rows).toContain('Noise spreadTicks*: 5 | Not reported | — | —');
    // true is not the number 1.
    expect(rows).toContain('Market makers skewTicks*: 1 | Not reported | Yes | —');
    // Nor is the text "5" the number 5.
    const quoted = changed((copy) => {
      copy.strategies.NOISE!.spreadTicks = '5';
    });
    expect(table([manifest(), quoted])).toContain('Noise spreadTicks*: 5 | 5');
    expect(rows).toContain('Defaults maxReadsInFlight: 64 | Not reported | 64 | —');
    expect(differingSettings([manifest(), null, other, undefined])).toBe(4);
    // One run that reported has nothing to differ from.
    expect(differingSettings([manifest(), null, undefined])).toBe(0);
    expect(settingGroups([null, undefined])).toEqual([]);
  });

  it('marks exactly the settings changed, whatever the runs and gaps around them', () => {
    const random = seeded(20261121);
    for (let round = 0; round < 300; round++) {
      const base = manifest();
      const other = editable();
      // Every setting of the copy by group and name, with the object it lives in.
      const places = new Map<string, [Record<string, unknown>, string]>();
      places.set('build.version', [other as unknown as Record<string, unknown>, 'version']);
      for (const name of Object.keys(other.runtime)) places.set(`build.${name}`, [other.runtime, name]);
      for (const [strategy, settings] of Object.entries(other.strategies)) {
        for (const name of Object.keys(settings)) places.set(`${strategy}.${name}`, [settings, name]);
      }
      for (const name of Object.keys(other.defaults)) places.set(`defaults.${name}`, [other.defaults, name]);
      const expected = new Set<string>();
      if (random(5) === 0) {
        // A strategy gone altogether: each of its settings is missing from this run.
        const gone = Object.keys(other.strategies)[random(3)]!;
        delete other.strategies[gone];
        for (const key of [...places.keys()]) {
          if (key.startsWith(`${gone}.`)) {
            expected.add(key);
            places.delete(key);
          }
        }
      }
      const keys = [...places.keys()].filter((key) => key !== 'build.version' || random(2) === 0);
      for (let count = random(6); count > 0 && keys.length > 0; count--) {
        const key = keys.splice(random(keys.length), 1)[0]!;
        const [holder, name] = places.get(key)!;
        const value = holder[name];
        const change = key === 'build.version' ? 0 : random(typeof value === 'number' ? 3 : 2);
        if (change === 0) holder[name] = typeof value === 'number' ? value + 1 : `${String(value)}-other`;
        else if (change === 1) delete holder[name];
        // A boolean is never the number it would equal.
        else holder[name] = value !== 0;
        expected.add(key);
      }
      for (let count = random(3); count > 0; count--) {
        const group = ['defaults', ...Object.keys(other.strategies)][random(1 + Object.keys(other.strategies).length)]!;
        const holder = group === 'defaults' ? other.defaults : other.strategies[group]!;
        const name = `extra${random(10)}`;
        holder[name] = [7, 0.25, true, 'fast'][random(4)]!;
        expected.add(`${group}.${name}`);
      }
      // Runs that reported nothing, or aren't there, never make a setting differ.
      const columns: ManifestColumn[] = [base, other];
      for (let gaps = random(3); gaps > 0; gaps--) {
        columns.splice(random(columns.length + 1), 0, random(2) === 0 ? null : undefined);
      }
      if (random(4) === 0) columns.push(structuredClone(base));

      const groups = settingGroups(columns);
      const marked = groups.flatMap((group) => group.rows.filter((row) => row.differs).map((row) => `${group.key}.${row.name}`));

      expect(marked.sort()).toEqual([...expected].sort());
      expect(differingSettings(columns)).toBe(expected.size);
      for (const group of groups) {
        for (const row of group.rows) expect(row.values).toHaveLength(columns.length);
      }
    }
  });
});
