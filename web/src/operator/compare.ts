import type { BotRun, RunFigures, StrategyFigures } from '../api/types';
import { groupInteger } from '../format';
import { differingSettings } from './manifest';

/** Runs compared at once: four columns still fit a laptop, and a phone scrolls them inside the panel. */
export const MAX_COMPARED = 4;

const RUN_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * The runs an address names, comma separated: each id once, in the order given, at most four. Anything that is not a
 * run id is dropped before asking the server, which would refuse it; the ids past the fourth are named apart.
 */
export function parseRunIds(param: string | null): { ids: string[]; dropped: string[]; overflow: string[] } {
  const ids: string[] = [];
  const dropped: string[] = [];
  const overflow: string[] = [];
  for (const raw of (param ?? '').split(',')) {
    const id = raw.trim().toLowerCase();
    if (id === '' || ids.includes(id) || overflow.includes(id)) {
      continue;
    }
    if (!RUN_ID.test(id)) {
      dropped.push(raw.trim());
    } else if (ids.length < MAX_COMPARED) {
      ids.push(id);
    } else {
      overflow.push(id);
    }
  }
  return { ids, dropped, overflow };
}

/** The ticked runs after one is ticked or unticked: an unticked run leaves, a ticked one goes last. */
export function withTick(ids: readonly string[], runId: string, on: boolean): string[] {
  const rest = ids.filter((id) => id !== runId);
  return on ? [...rest, runId] : rest;
}

/**
 * Whether a run can be compared: finished once its window has an end; NOT_KEPT for a run recorded before windows were
 * kept, which never gets one; otherwise not finished yet. A stopped run is not necessarily finished: its window ends
 * only when its worker confirms the stop, or the next run starts.
 */
export function comparability(run: BotRun): 'FINISHED' | 'UNFINISHED' | 'NOT_KEPT' {
  if (run.window.endSequence !== null) return 'FINISHED';
  return run.window.endedBy === 'NOT_KEPT' ? 'NOT_KEPT' : 'UNFINISHED';
}

/** "1 of 3 (33%)": market makers among the run's bots, by count, the share rounded to a whole percent. */
export function marketMakerShare({ marketMakers, noise, momentum }: BotRun['config']['strategies']): string {
  const bots = marketMakers + noise + momentum;
  return bots === 0 ? '—' : `${groupInteger(marketMakers)} of ${groupInteger(bots)} (${Math.round((marketMakers / bots) * 100)}%)`;
}

/**
 * How long the run ran: from its start to the end of its window, when its worker confirmed the stop or the next run
 * started. Its stop can't stand in: a worker may confirm a run the next start ended long after its window closed. Until
 * the window ends, the last report stands in and the length is approximate; with neither, it is unknown.
 */
export function runSeconds(run: BotRun): { seconds: number; approximate: boolean } | null {
  const end = run.window.endedAt ?? run.worker.heartbeatAt;
  if (end === null) {
    return null;
  }
  const seconds = (Date.parse(end) - Date.parse(run.startedAt)) / 1000;
  return seconds <= 0 ? null : { seconds, approximate: run.window.endedAt === null };
}

/** "1 h 5 min", "12 min 30 s", "45 s": whole seconds. */
export function durationText(seconds: number): string {
  const whole = Math.round(seconds);
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const rest = whole % 60;
  if (hours > 0) return `${hours} h ${minutes} min`;
  if (minutes > 0) return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
  return `${rest} s`;
}

/** The run's actual length, "about" when approximate. */
export function actualDuration(run: BotRun): string {
  const length = runSeconds(run);
  return length === null ? 'Unknown' : `${length.approximate ? 'About ' : ''}${durationText(length.seconds)}`;
}

/** The length asked for when it started, or until stopped. */
export function plannedDuration(run: BotRun): string {
  return run.endsAt === null ? 'Until stopped' : durationText((Date.parse(run.endsAt) - Date.parse(run.startedAt)) / 1000);
}

/**
 * Commands per second in the run's window, to two decimals: the placements and cancels its bots sent that were
 * answered between its start and its end, over its length. A refusal before the writer (a rate limit) left no record,
 * so it is not counted. Null without figures or a length.
 */
export function achievedRate(run: BotRun, figures: RunFigures | null): string | null {
  const length = runSeconds(run);
  if (figures === null || length === null) {
    return null;
  }
  const commands = figures.strategies.reduce((sum, each) => sum + each.placements + each.cancels, 0);
  return (commands / length.seconds).toFixed(2);
}

/** "62.5%": the quantity filled over the quantity placed, one decimal; a dash when nothing was placed. */
export function fillRatio({ placedQuantity, filledQuantity }: StrategyFigures): string {
  return placedQuantity === 0 ? '—' : `${((filledQuantity / placedQuantity) * 100).toFixed(1)}%`;
}

/** "3 (INSUFFICIENT_CASH 2, MARKET_NOT_OPEN 1)": refusals in all, then by code. */
export function refusalsText({ refusals }: StrategyFigures): string {
  const total = refusals.reduce((sum, each) => sum + each.commands, 0);
  if (total === 0) return '0';
  return `${groupInteger(total)} (${refusals.map((each) => `${each.code} ${groupInteger(each.commands)}`).join(', ')})`;
}

/**
 * Why the runs' figures don't compare like for like: a different requested rate, different instruments, or simulators
 * that differ in their version or settings. The order of the runs makes no difference.
 */
export function comparisonWarnings(runs: readonly BotRun[]): string[] {
  const warnings: string[] = [];
  const rates = [...new Set(runs.map((run) => run.config.commandsPerSecond))];
  if (rates.length > 1) {
    warnings.push(
      `The runs asked for different rates (${rates.sort((a, b) => Number(a) - Number(b)).join(', ')} commands a second), so more trading in one may be only its rate.`,
    );
  }
  const instruments = new Set(
    runs.map((run) => [...run.config.instruments.map((each) => each.symbol)].sort().join(', ')),
  );
  if (instruments.size > 1) {
    warnings.push('The runs traded different instruments, so their market figures are not for the same markets.');
  }
  const differing = differingSettings(runs.map((run) => run.manifest));
  if (differing > 0) {
    warnings.push(
      `The runs' simulators differ in ${groupInteger(differing)} ${differing === 1 ? 'setting' : 'settings'}, marked under Simulator, so a difference may come from the code or its settings rather than the bots.`,
    );
  }
  return warnings;
}
