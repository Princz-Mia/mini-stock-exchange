import type { BotRun } from '../api/types';
import { groupInteger } from '../format';
import { durationText } from './compare';

/**
 * Where a run that is not over stands, from its status and its worker's last report:
 * - WAITING: no worker has taken it yet;
 * - LOADING: a worker holds it and is reading its bots' token files and building them;
 * - TRADING: every bot is built and the run trades;
 * - HELD: a worker holds it but reports no phase, as one built before phases were reported;
 * - LOST: its heartbeats stopped while it should run;
 * - STOPPING: a stop was asked for; the worker finishes loading the bots, if it still is, then settles.
 */
export type RunPhase = 'WAITING' | 'LOADING' | 'TRADING' | 'HELD' | 'LOST' | 'STOPPING' | 'STOPPED';

export function runPhase(run: BotRun): RunPhase {
  switch (run.status) {
    case 'STARTING':
      return 'WAITING';
    case 'STALE':
      return 'LOST';
    case 'STOPPING':
      return 'STOPPING';
    case 'STOPPED':
      return 'STOPPED';
    case 'RUNNING':
      return run.progress === null ? 'HELD' : run.progress.phase;
  }
}

type Progress = NonNullable<BotRun['progress']>;

/** The whole percentage of the bots built, rounded down, so 100% means every bot is built. A run of none is done. */
export function loadedPercent({ botsLoaded, botsTotal }: Progress): number {
  return botsTotal === 0 ? 100 : Math.floor((Math.min(botsLoaded, botsTotal) * 100) / botsTotal);
}

/** "4,000 of 20,000 bots loaded (20%)": the bar's value in words, and what the panel says beside it. */
export function loadedText(progress: Progress): string {
  return `${groupInteger(progress.botsLoaded)} of ${groupInteger(progress.botsTotal)} bots loaded (${loadedPercent(progress)}%)`;
}

/** A heartbeat of the run as this page saw it: when it came, and how far the loading had got then. */
export interface LoadingBeat {
  at: string | null;
  progress: BotRun['progress'];
}

/**
 * Seconds until every bot is loaded at the pace between two heartbeats of the loading, rounded up. Null with no pace
 * to go by: until two heartbeats of the same loading have been seen, or when no bot was loaded between them, as a
 * worker that took the run over starts its loading again from none.
 */
export function loadingSecondsLeft(current: LoadingBeat, previous: LoadingBeat | null): number | null {
  const now = current.progress;
  const before = previous?.progress ?? null;
  if (now?.phase !== 'LOADING' || before?.phase !== 'LOADING' || before.botsTotal !== now.botsTotal) return null;
  if (current.at === null || previous?.at == null) return null;
  const seconds = (Date.parse(current.at) - Date.parse(previous.at)) / 1000;
  const loaded = now.botsLoaded - before.botsLoaded;
  if (seconds <= 0 || loaded <= 0) return null;
  return Math.ceil(((now.botsTotal - Math.min(now.botsLoaded, now.botsTotal)) * seconds) / loaded);
}

/** The phase's heading, which also names its bar. */
export const PHASE_TITLES: Record<RunPhase, string> = {
  WAITING: 'Waiting for a simulator worker',
  LOADING: 'Loading bots',
  TRADING: 'Trading',
  HELD: 'Running',
  LOST: 'Heartbeats stopped',
  STOPPING: 'Stopping',
  STOPPED: 'Stopped',
};

/** Which of the three steps a run goes through it is on, for the phases that are one of them. */
export function stepText(phase: RunPhase): string | null {
  switch (phase) {
    case 'WAITING':
      return 'Step 1 of 3';
    case 'LOADING':
      return 'Step 2 of 3';
    case 'TRADING':
      return 'Step 3 of 3';
    default:
      return null;
  }
}

/** "150 placed · 40 canceled · 6 rejected": the trading counts at a glance, beside the step. */
export function tradingText({ placed, canceled, rejected }: BotRun['activity']): string {
  return `${groupInteger(placed)} placed · ${groupInteger(canceled)} canceled · ${groupInteger(rejected)} rejected`;
}

/**
 * The bar of a timed run's trading: seconds gone of its length, to whole seconds and at most its length. Null for a
 * run that lasts until stopped, as there is nothing to measure it against.
 */
export function runTime(run: BotRun, now: number): { elapsed: number; length: number } | null {
  if (run.endsAt === null) return null;
  const length = Math.max(1, Math.round((Date.parse(run.endsAt) - Date.parse(run.startedAt)) / 1000));
  const elapsed = Math.min(length, Math.max(0, Math.floor((now - Date.parse(run.startedAt)) / 1000)));
  return { elapsed, length };
}

/** "12 min of 1 h 0 min": a timed run's bar in words. */
export function runTimeText({ elapsed, length }: { elapsed: number; length: number }): string {
  return `${durationText(elapsed)} of ${durationText(length)}`;
}

/**
 * What a screen reader is told about the run: when its phase changes, its end included, and, while it loads, at each
 * quarter of its bots. Never on every poll: the bar has the current value for whoever looks.
 */
export function runMilestone(run: BotRun): string {
  const phase = runPhase(run);
  switch (phase) {
    case 'WAITING':
      return 'The run is waiting for a simulator worker.';
    case 'LOADING': {
      // In this phase, so present.
      const progress = run.progress as Progress;
      const quarter = Math.floor(loadedPercent(progress) / 25) * 25;
      const bots = `${groupInteger(progress.botsTotal)} ${progress.botsTotal === 1 ? 'bot' : 'bots'}`;
      return quarter === 0 ? `Loading ${bots}.` : `${quarter}% of the ${bots} are loaded.`;
    }
    case 'TRADING':
      return 'Every bot is loaded; the run is trading.';
    case 'HELD':
      return 'A simulator worker holds the run.';
    case 'LOST':
      return "The run's heartbeats stopped; its bots place nothing.";
    case 'STOPPING':
      return 'The run is stopping.';
    case 'STOPPED':
      return 'The run has stopped.';
  }
}

export type PauseReason = 'pausedStaleData' | 'pausedMarketNotOpen' | 'pausedOther';

const REASONS: readonly PauseReason[] = ['pausedStaleData', 'pausedMarketNotOpen', 'pausedOther'];

/** The short name of each reason, for the counts. */
export const PAUSE_LABELS: Record<PauseReason, string> = {
  pausedStaleData: 'market data stale',
  pausedMarketNotOpen: 'market not open',
  pausedOther: 'other',
};

/** Why bots that place nothing are paused, in words that do not change while it lasts, so it is said once. */
export const PAUSE_TEXTS: Record<PauseReason, string> = {
  pausedStaleData:
    'Paused: market data is stale. The bots place nothing new while the market view they act on is too old or not live, and go on once it is fresh again.',
  pausedMarketNotOpen:
    'Paused: the market is not open. The bots place nothing new until it opens or resumes trading.',
  pausedOther:
    "Paused: the bots' steps place nothing new, for a reason such as none of the run's instruments being listed yet.",
};

export function pausedTotal(activity: BotRun['activity']): number {
  return REASONS.reduce((sum, reason) => sum + activity[reason], 0);
}

/** "market data stale 1,204 · other 3", leaving out reasons with none. */
export function pausedByReasonText(activity: BotRun['activity']): string {
  return REASONS.filter((reason) => activity[reason] > 0)
    .map((reason) => `${PAUSE_LABELS[reason]} ${groupInteger(activity[reason])}`)
    .join(' · ');
}

/**
 * Why a trading run places nothing, when its paused steps explain it; null when it places orders or nothing says why.
 * Between two heartbeats, `previous` the activity at the one before: nothing more placed while steps were paused
 * means the run is paused now, by the reason that grew most. With no earlier heartbeat seen, only a run that has
 * placed nothing at all is explained, by the reason with the most steps, as cumulative counts cannot say what goes on
 * now in a run that has traded.
 */
export function pauseReason(
  current: BotRun['activity'],
  previous: BotRun['activity'] | null,
): PauseReason | null {
  const placedMore = previous === null ? current.placed : current.placed - previous.placed;
  if (placedMore > 0) return null;
  let best: PauseReason | null = null;
  let most = 0;
  for (const reason of REASONS) {
    const steps = previous === null ? current[reason] : current[reason] - previous[reason];
    if (steps > most) {
      best = reason;
      most = steps;
    }
  }
  return best;
}
