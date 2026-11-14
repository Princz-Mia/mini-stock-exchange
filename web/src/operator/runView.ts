import type { BotPopulation, BotRun, BotRunRequest, Instrument, MarketStatus } from '../api/types';
import { groupInteger, utcTime } from '../format';

export const STATUS_LABELS: Record<BotRun['status'], string> = {
  STARTING: 'Starting',
  RUNNING: 'Running',
  STALE: 'Worker lost',
  STOPPING: 'Stopping',
  STOPPED: 'Stopped',
};

/** The badge style for a status: running looks like an open market, a lost worker like a halt. */
export function statusClass(status: BotRun['status']): string {
  if (status === 'RUNNING') return 'state-badge state-open';
  if (status === 'STALE') return 'state-badge state-halted';
  return 'state-badge';
}

/** "2 market makers, 6 noise, 2 momentum", leaving out strategies with no bots. */
export function strategiesText({ marketMakers, noise, momentum }: BotRun['config']['strategies']): string {
  const parts = [
    marketMakers > 0 ? `${marketMakers} market maker${marketMakers === 1 ? '' : 's'}` : null,
    noise > 0 ? `${noise} noise` : null,
    momentum > 0 ? `${momentum} momentum` : null,
  ].filter((part) => part !== null);
  return parts.length === 0 ? 'No bots' : parts.join(', ');
}

/** Every command the run's workers answered, whatever the answer. */
export function commandTotal({ placed, canceled, rejected, errors }: BotRun['activity']): number {
  return placed + canceled + rejected + errors;
}

/**
 * Commands per second from the start to the last heartbeat, to two decimals; null before a heartbeat. An average over
 * the whole run: it reads the same on every screen, whenever it was opened.
 */
export function averageRate(run: BotRun): string | null {
  if (run.worker.heartbeatAt === null) {
    return null;
  }
  const seconds = (Date.parse(run.worker.heartbeatAt) - Date.parse(run.startedAt)) / 1000;
  return seconds <= 0 ? null : (commandTotal(run.activity) / seconds).toFixed(2);
}

/** The run that is not over yet, if any: the newest one, unless it has stopped. */
export function activeRun(runs: readonly BotRun[]): BotRun | undefined {
  const newest = runs[0];
  return newest === undefined || newest.status === 'STOPPED' ? undefined : newest;
}

/** Why a run can't start right now, or null when it can. */
export function startBlocked(
  market: MarketStatus['state'],
  active: BotRun | undefined,
  byStrategy: ByStrategy | undefined,
): string | null {
  if (market !== 'OPEN' && market !== 'HALTED') return 'Bots trade only once the market has opened.';
  if (active !== undefined) return 'A run is active; one runs at a time. Stop it first.';
  if (byStrategy === undefined) return null;
  const tagged = byStrategy.marketMakers + byStrategy.noise + byStrategy.momentum;
  if (tagged + byStrategy.untagged === 0) return 'There are no bots; they are created during setup.';
  if (tagged === 0) return 'No bot has a strategy, and runs use only bots tagged with one.';
  return null;
}

type ByStrategy = BotPopulation['byStrategy'];

/** The form's counts to start from: every bot tagged with a strategy runs it. */
export function taggedSplit(byStrategy: ByStrategy | undefined): Pick<RunForm, 'marketMakers' | 'noise' | 'momentum'> {
  return {
    marketMakers: String(byStrategy?.marketMakers ?? 0),
    noise: String(byStrategy?.noise ?? 0),
    momentum: String(byStrategy?.momentum ?? 0),
  };
}

/**
 * Why the counts typed cannot run: the first strategy, in assignment order, asked for more bots than are tagged with
 * it, as the exchange would refuse it. Counts that are not whole numbers are left to the form's own check.
 */
export function shortfallText(form: RunForm, byStrategy: ByStrategy | undefined): string | null {
  if (byStrategy === undefined) return null;
  const strategies: [keyof RunForm & keyof ByStrategy, string, string][] = [
    ['marketMakers', 'market maker', 'market makers'],
    ['noise', 'noise bot', 'noise bots'],
    ['momentum', 'momentum bot', 'momentum bots'],
  ];
  for (const [field, one, many] of strategies) {
    const text = (form[field] as string).trim();
    const tagged = byStrategy[field];
    if (WHOLE.test(text) && Number(text) > tagged) {
      if (tagged === 0) return `No bot is tagged as a ${one}.`;
      return `Only ${groupInteger(tagged)} ${tagged === 1 ? one : many} ${tagged === 1 ? 'exists' : 'exist'}.`;
    }
  }
  return null;
}

/** What the worker is doing, in words. */
export function workerText(run: BotRun): string {
  const { workerId, heartbeatAt } = run.worker;
  const since = heartbeatAt === null ? '' : ` since ${utcTime(heartbeatAt)}`;
  switch (run.status) {
    case 'STARTING':
      return 'Waiting for a simulator worker to take the run.';
    case 'RUNNING':
      return `${workerId ?? 'A worker'} holds the run.`;
    case 'STALE':
      return `No heartbeat from ${workerId ?? 'its worker'}${since}. Its bots place nothing until a worker takes over.`;
    case 'STOPPING':
      return `${workerId ?? 'The worker'} is settling: no new orders, answering what is in flight, then canceling quotes.`;
    case 'STOPPED':
      return run.stoppedAt === null
        ? 'Stopped without a worker to confirm it.'
        : `Stopped and confirmed by ${workerId ?? 'its worker'}.`;
  }
}

/** The start form's text fields, as typed. Blank optional fields fall back to the exchange's defaults. */
export interface RunForm {
  marketMakers: string;
  noise: string;
  momentum: string;
  seed: string;
  commandsPerSecond: string;
  maxOrderQuantity: string;
  maxPosition: string;
  durationMinutes: string;
  /** Ticked instruments; every listed one by default. */
  instrumentIds: readonly string[];
}

const WHOLE = /^\d{1,9}$/;

/**
 * The request to send, or what is wrong with the form. Only shapes are checked here; the exchange checks ranges and
 * answers in its own words. Ticking every instrument sends none, which the exchange reads as all of them.
 */
export function runRequest(
  form: RunForm,
  instruments: readonly Instrument[],
): { request: BotRunRequest } | { error: string } {
  const whole: [keyof RunForm, string, boolean][] = [
    ['marketMakers', 'Market makers', true],
    ['noise', 'Noise', true],
    ['momentum', 'Momentum', true],
    ['seed', 'Seed', false],
    ['maxOrderQuantity', 'Largest order', true],
    ['maxPosition', 'Position limit', true],
    ['durationMinutes', 'Duration', false],
  ];
  const numbers: Partial<Record<keyof RunForm, number>> = {};
  for (const [field, label, required] of whole) {
    const text = (form[field] as string).trim();
    if (text === '' && !required) continue;
    if (!WHOLE.test(text)) return { error: `${label} must be a whole number.` };
    numbers[field] = Number(text);
  }
  if (form.instrumentIds.length === 0) {
    return { error: 'Choose at least one instrument.' };
  }
  const every = instruments.every((item) => form.instrumentIds.includes(item.instrumentId));
  const request: BotRunRequest = {
    // Required above, so present.
    marketMakers: numbers.marketMakers as number,
    noise: numbers.noise as number,
    momentum: numbers.momentum as number,
    commandsPerSecond: form.commandsPerSecond.trim(),
    maxOrderQuantity: numbers.maxOrderQuantity as number,
    maxPosition: numbers.maxPosition as number,
    ...(numbers.seed === undefined ? {} : { seed: numbers.seed }),
    ...(numbers.durationMinutes === undefined ? {} : { durationMinutes: numbers.durationMinutes }),
    ...(every ? {} : { instrumentIds: [...form.instrumentIds] }),
  };
  return { request };
}
