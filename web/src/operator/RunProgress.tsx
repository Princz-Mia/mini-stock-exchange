import { useId, useState } from 'react';

import type { BotRun } from '../api/types';
import { timeLeftText } from './botJob';
import { durationText } from './compare';
import {
  loadedPercent,
  loadedText,
  loadingSecondsLeft,
  PAUSE_TEXTS,
  PHASE_TITLES,
  pauseReason,
  type RunPhase,
  runPhase,
  runTime,
  runTimeText,
  stepText,
  tradingText,
} from './runPhase';
import { elapsedSeconds, workerText } from './runView';

/** A heartbeat this page saw: when it came, and what the worker reported with it. */
interface Beat {
  at: string | null;
  activity: BotRun['activity'];
  progress: BotRun['progress'];
}

/** A bar with a value, or one that only says the phase is under way: a wait or a stop has no amount to show. */
type Bar = { value: number; max: number; percent: number; text: string } | { text: string } | null;

function barFor(run: BotRun, phase: RunPhase, now: number): Bar {
  const time = runTime(run, now);
  switch (phase) {
    case 'WAITING':
      return { text: PHASE_TITLES.WAITING };
    case 'STOPPING':
      // A stop asked for during loading waits for the worker to finish it, so the loading still has an amount to show.
      return run.progress?.phase === 'LOADING' ? loadingBar(run.progress) : { text: PHASE_TITLES.STOPPING };
    case 'LOADING':
      // In this phase, so present.
      return loadingBar(run.progress as NonNullable<BotRun['progress']>);
    case 'LOST':
      // Where the loading had got to when the heartbeats stopped; a lost trading run has no step to measure.
      return run.progress?.phase === 'LOADING' ? loadingBar(run.progress) : null;
    case 'TRADING':
    case 'HELD':
      return time === null
        ? null
        : {
            value: time.elapsed,
            max: time.length,
            percent: Math.floor((time.elapsed * 100) / time.length),
            text: runTimeText(time),
          };
    case 'STOPPED':
      return null;
  }
}

function loadingBar(progress: NonNullable<BotRun['progress']>): Bar {
  return {
    value: progress.botsLoaded,
    max: progress.botsTotal,
    percent: loadedPercent(progress),
    text: loadedText(progress),
  };
}

/** The figures beside the bar: the step, and what the phase has to show. */
function figuresFor(run: BotRun, phase: RunPhase, now: number): string {
  const step = stepText(phase);
  const lead = step === null ? '' : `${step} · `;
  switch (phase) {
    case 'WAITING':
      return `${lead}waiting ${durationText(elapsedSeconds(run, now))}`;
    case 'LOADING':
      return `${lead}${loadedText(run.progress as NonNullable<BotRun['progress']>)}`;
    case 'LOST':
      return run.progress?.phase === 'LOADING'
        ? `Last reported: ${loadedText(run.progress)}`
        : `Last reported: ${tradingText(run.activity)}`;
    case 'STOPPING':
      return run.progress?.phase === 'LOADING'
        ? `Still loading: ${loadedText(run.progress)}`
        : tradingText(run.activity);
    default:
      return `${lead}${tradingText(run.activity)}`;
  }
}

function detailFor(run: BotRun, phase: RunPhase): string {
  if (phase === 'LOADING') {
    return `${run.worker.workerId ?? 'The worker'} is reading the bots' token files and building them; nothing trades until every bot is loaded.`;
  }
  if (phase === 'STOPPING' && run.progress?.phase === 'LOADING') {
    return `${run.worker.workerId ?? 'The worker'} finishes loading the bots before it stops; then it places nothing and cancels their quotes.`;
  }
  return workerText(run);
}

function beatOf(run: BotRun): Beat {
  return { at: run.worker.heartbeatAt, activity: run.activity, progress: run.progress };
}

/**
 * The heartbeat before the latest one this page saw, or null until it has seen two. A run's counts and its loading
 * move only with its heartbeats, so the difference between the two says what the worker did in between.
 * Kept as state and moved on during render when a new heartbeat arrives, so it needs no effect of its own.
 */
function usePreviousBeat(run: BotRun): Beat | null {
  const [beats, setBeats] = useState({ runId: run.runId, latest: beatOf(run), previous: null as Beat | null });
  if (beats.runId !== run.runId || beats.latest.at !== run.worker.heartbeatAt) {
    const next = {
      runId: run.runId,
      latest: beatOf(run),
      previous: beats.runId === run.runId ? beats.latest : null,
    };
    setBeats(next);
    return next.previous;
  }
  return beats.previous;
}

/**
 * Where a run that is not over stands, with a bar like the bot job's: waiting for a worker, loading its bots n of N
 * with the time left, then trading with its counts and, for a timed run, its time gone of its length. A run whose
 * heartbeats stopped says so, with where it had got to, and a stop asked for during loading keeps the loading's bar
 * until the worker has finished it. While a trading run places nothing and its paused steps say why, that is said too.
 * Everything comes from the worker's heartbeats as the exchange keeps them, so it is the same after a reload and on
 * every operator's screen; it moves when a heartbeat arrives, every few seconds. The time left and the paused reason
 * go by the difference between the last two heartbeats this page saw, so a page just opened waits one heartbeat for
 * them.
 */
export function RunProgress({ run, now }: { run: BotRun; now: number }) {
  const phase = runPhase(run);
  const titleId = useId();
  const previous = usePreviousBeat(run);
  const reason = phase === 'TRADING' || phase === 'HELD' ? pauseReason(run.activity, previous?.activity ?? null) : null;
  // A worker still loading, a stop asked for or not, has a time left; one whose heartbeats stopped has none.
  const loads = (phase === 'LOADING' || phase === 'STOPPING') && run.progress?.phase === 'LOADING';
  const bar = barFor(run, phase, now);
  const measured = bar !== null && 'value' in bar;
  return (
    <div className="bot-job run-progress">
      <h3 id={titleId}>{PHASE_TITLES[phase]}</h3>
      {bar !== null && (
        <div
          role="progressbar"
          aria-labelledby={titleId}
          aria-valuemin={measured ? 0 : undefined}
          aria-valuemax={measured ? bar.max : undefined}
          aria-valuenow={measured ? bar.value : undefined}
          aria-valuetext={bar.text}
          className={measured ? 'job-bar' : 'job-bar job-bar-waiting'}
        >
          <div className="job-bar-fill" style={measured ? { width: `${bar.percent}%` } : undefined} />
        </div>
      )}
      <p className="job-figures">
        <span className="num">{figuresFor(run, phase, now)}</span>
        {loads && <> · {timeLeftText(loadingSecondsLeft(beatOf(run), previous))}</>}
      </p>
      <p className={phase === 'LOST' ? 'form-error' : 'muted'}>{detailFor(run, phase)}</p>
      {/* The words never change while it lasts, so a screen reader says it once, not with every refetch. */}
      {reason !== null && (
        <p className="chart-note" role="status">
          {PAUSE_TEXTS[reason]}
        </p>
      )}
    </div>
  );
}
