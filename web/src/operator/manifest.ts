import type { BotRunManifest } from '../api/types';

/** The strategies in the order the screens list them, with their names. */
export const STRATEGIES = [
  ['MARKET_MAKER', 'Market makers'],
  ['NOISE', 'Noise'],
  ['MOMENTUM', 'Momentum'],
] as const;

/**
 * A run's manifest as a column has it: the manifest, null when no worker reported one, undefined when the run itself
 * isn't there (still loading, or no such run).
 */
export type ManifestColumn = BotRunManifest | null | undefined;

type Setting = BotRunManifest['defaults'][string];

/** The simulator a run ran on, in a few words: its version, and whether another took over midway. */
export function simulatorText(manifest: BotRunManifest | null): string {
  if (manifest === null) return 'Not reported';
  return manifest.reports > 1 ? `${manifest.version} · changed during the run` : manifest.version;
}

export function settingText(value: Setting | undefined): string {
  if (value === undefined) return '—';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  return String(value);
}

/** Two settings as JSON values: 10 and 10.0 are one number in JavaScript already, but true is not 1. */
function same(a: Setting | undefined, b: Setting | undefined): boolean {
  return typeof a === typeof b && a === b;
}

export type SettingRow = { name: string; values: string[]; differs: boolean };
export type SettingGroup = { key: string; label: string; rows: SettingRow[] };

/** One group's rows: every name any run reported, in name order, and whether the runs that reported disagree. */
function rows(columns: readonly ManifestColumn[], pick: (manifest: BotRunManifest) => Record<string, Setting>) {
  const reported = columns.filter((column): column is BotRunManifest => column != null);
  const names = [...new Set(reported.flatMap((manifest) => Object.keys(pick(manifest))))].sort();
  return names.map((name): SettingRow => {
    const values = reported.map((manifest) => pick(manifest)[name]);
    return {
      name,
      values: columns.map((column) =>
        column === undefined ? '—' : column === null ? 'Not reported' : settingText(pick(column)[name]),
      ),
      // A setting one run has and another lacks differs too; a run that reported nothing is left out.
      differs: values.some((value) => !same(value, values[0])),
    };
  });
}

/**
 * The runs' manifests side by side: the version and runtime, each strategy's settings, then the defaults, one row per
 * setting with a value for each run. Names are the simulator's own: the contract leaves them open.
 */
export function settingGroups(columns: readonly ManifestColumn[]): SettingGroup[] {
  const reported = columns.filter((column): column is BotRunManifest => column != null);
  const strategies = [...new Set(reported.flatMap((manifest) => Object.keys(manifest.strategies)))];
  const known: readonly string[] = STRATEGIES.map(([key]) => key);
  const named = [
    ...STRATEGIES.filter(([key]) => strategies.includes(key)),
    ...strategies.filter((key) => !known.includes(key)).sort().map((key) => [key, key] as const),
  ];
  const groups: SettingGroup[] = [
    {
      key: 'build',
      label: 'Version and runtime',
      rows: [
        ...rows(columns, (manifest) => ({ version: manifest.version })),
        ...rows(columns, (manifest) => manifest.runtime),
      ],
    },
    ...named.map(([key, label]) => ({
      key,
      label,
      rows: rows(columns, (manifest) => manifest.strategies[key] ?? {}),
    })),
    { key: 'defaults', label: 'Defaults', rows: rows(columns, (manifest) => manifest.defaults) },
  ];
  return groups.filter((group) => group.rows.length > 0);
}

/** How many settings differ between the runs that reported, the version among them. */
export function differingSettings(columns: readonly ManifestColumn[]): number {
  return settingGroups(columns).reduce((sum, group) => sum + group.rows.filter((row) => row.differs).length, 0);
}
