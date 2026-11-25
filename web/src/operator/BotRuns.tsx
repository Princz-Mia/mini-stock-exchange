import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';

import { api, pollEvery, pollUntilRead } from '../api/client';
import type {
  BotPopulation,
  BotRun,
  BotRunManifest,
  BotRunRequest,
  BotRunStarted,
  BotRunStopping,
  Instrument,
  MarketStatus,
} from '../api/types';
import { type Column, PagedTable } from '../app/PagedTable';
import { SkeletonRows } from '../app/Skeleton';
import { usePagedList } from '../app/paging';
import { useKeyboardScroll } from '../app/useKeyboardScroll';
import { groupInteger, utcTime } from '../format';
import {
  activeRun,
  averageRate,
  botsAskedFor,
  defaultRate,
  elapsedSeconds,
  leftRunning,
  paceText,
  ranForSeconds,
  ranForText,
  type RunForm,
  runRequest,
  STATUS_LABELS,
  shortfallText,
  startBlocked,
  stopRecordedByClose,
  statusClass,
  strategiesText,
  taggedSplit,
  timeLeftText,
} from './runView';
import { pausedByReasonText, pausedTotal, runMilestone } from './runPhase';
import { RunProgress } from './RunProgress';
import { populationText } from './strategies';
import { Announcer } from './Announcer';
import { CommandStatus } from './CommandStatus';
import { type Freshness, notReadYet, useFreshRead } from './freshRead';
import { useOperatorCommand } from './operatorCommands';
import { comparability, durationText, MAX_COMPARED, parseRunIds, withTick } from './compare';
import { SimulatorSettings } from './SimulatorSettings';

type Typed = Partial<Omit<RunForm, 'instrumentIds'>> & { instrumentIds?: readonly string[] };
type TextField = Exclude<keyof RunForm, 'instrumentIds' | 'untilStopped'>;

// A run ends by itself after an hour unless the operator says otherwise: one with no end is easy to forget. Its rate
// follows the bots asked for until one is typed.
const DEFAULTS = {
  seed: '',
  maxOrderQuantity: '10',
  maxPosition: '100',
  durationMinutes: '60',
  untilStopped: false,
};

/**
 * Bot runs: the one that is running with its worker and activity, a form to start the next, and every run a page at a
 * time. Everything shown is what Execution committed and what the worker last reported. The current run is refetched
 * every two seconds; the history every five, and at once whenever the newest run changes, so the two agree. Neither
 * is polled once the market has closed and a read has come back, as nothing about its runs can change any more. How
 * long the run has been going is counted up to the last answer, on the exchange's clock as that answer gave it: it
 * moves with each refetch, needs no timer of its own, and does not depend on the browser's clock.
 */
export function BotRuns({
  market,
  simulationId = null,
  closedAt,
  instruments,
}: {
  market: MarketStatus['state'];
  /** The simulation on screen, whose start and stop commands are kept for it. */
  simulationId?: string | null;
  /** When the market closed, or null while it has not: the close leaves its time on runs it did not end. */
  closedAt: string | null;
  instruments: readonly Instrument[];
}) {
  // A close ends every run in its own transaction, and a worker's report on an ended run is refused, so a closed
  // market's runs are read once, when the page opens or the market closes, and polled only until a read comes back.
  const closed = market === 'CLOSED';
  const runs = useQuery({
    queryKey: ['bot-runs'],
    queryFn: api.botRuns,
    refetchInterval: closed ? pollUntilRead(2_000) : pollEvery(2_000),
  });
  const population = useQuery({ queryKey: ['bots'], queryFn: api.botPopulation });
  const list = runs.data?.runs ?? [];
  const active = activeRun(list);
  const now = runs.data?.answeredAt ?? runs.dataUpdatedAt;
  // The runs as cached may be from before this page opened, and a start sent meanwhile may have gone through: no form
  // is offered until they have been read since.
  const read = useFreshRead(runs);
  const start = useStartRun(simulationId ?? '', read === 'fresh' && active !== undefined);
  // A start that is answered, or settled by a run seen going, is let go of once the run has ended, so the form comes
  // back as new: a started run once it is listed as ended, and otherwise once a read made after the answer shows no
  // run going. A refusal of what was typed stays with the form.
  const { forget, outcome: started } = start;
  const finished =
    started.kind === 'done'
      ? list.some((run) => run.runId === started.result.runId)
      : start.settled || (started.kind === 'refused' && started.code === 'RUN_ACTIVE');
  const readAfter = start.answeredAt !== null && runs.dataUpdatedAt > start.answeredAt;
  useEffect(() => {
    if (finished && readAfter && read === 'fresh' && active === undefined) {
      forget();
    }
  }, [finished, readAfter, read, active, forget]);
  const history = usePagedList({
    table: 'runs',
    queryKey: ['bot-runs', 'history'],
    fetch: async (cursor, { size, order }, read) => {
      const page = await api.botRunPage(cursor, size, order, read);
      return { rows: page.runs, nextCursor: page.nextCursor };
    },
    defaults: { size: 10, order: 'newest' },
    refetchInterval: closed ? pollUntilRead(5_000) : pollEvery(5_000),
  });
  const client = useQueryClient();
  const newest = list[0];
  const newestState = newest === undefined ? '' : `${newest.runId}|${newest.status}|${newest.stoppedAt ?? ''}`;
  useEffect(() => {
    if (newestState !== '') {
      void client.invalidateQueries({ queryKey: ['bot-runs', 'history'] });
    }
  }, [client, newestState]);
  // The runs ticked for a comparison live in the address, so going back from the comparison finds them ticked.
  const [params, setParams] = useSearchParams();
  const picked = parseRunIds(params.get('compare')).ids;
  const pick = (runId: string, on: boolean) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        const ids = withTick(parseRunIds(current.get('compare')).ids, runId, on);
        if (ids.length > 0) next.set('compare', ids.join(','));
        else next.delete('compare');
        return next;
      },
      { replace: true },
    );
  const clear = () =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        next.delete('compare');
        return next;
      },
      { replace: true },
    );
  const columns = [compareColumn(picked, pick), STARTED_COLUMN, ranForColumn(now), ...RUN_COLUMNS, endedColumn(closedAt)];
  // The screen's own address goes along, so coming back keeps the table's page size and order.
  const from = useLocation().search;

  return (
    <section className="panel" aria-labelledby="runs-title">
      <h2 id="runs-title">Bot runs</h2>
      {/* The newest run, stopped ones too, so a run's end is said as it leaves the panel to the form. */}
      <Announcer said={runs.data === undefined ? undefined : newest === undefined ? null : runMilestone(newest)} />
      {runs.isError && (
        <p role="alert" className="form-error">
          The bot runs could not be loaded.
        </p>
      )}
      {runs.isPending ? (
        <SkeletonRows />
      ) : active !== undefined ? (
        <>
          {/* The start's answer stays in sight once its form has given way to the run. */}
          <StartStatus start={start} active />
          <ActiveRun run={active} now={now} simulationId={simulationId ?? ''} read={read} />
        </>
      ) : read !== 'fresh' && !runs.isError ? (
        <p className="muted" role="status">
          {notReadYet(read)}
        </p>
      ) : market === 'CLOSED' ? (
        // No form: nothing in it could be sent. The runs the market had stay below, with their comparison.
        <p className="muted">The market has closed, so no run can start. Its runs stay listed, and can still be compared.</p>
      ) : (
        <StartForm
          market={market}
          instruments={instruments}
          population={population.data}
          blocked={runs.isError ? 'The bot runs could not be loaded.' : null}
          start={start}
        />
      )}
      {list.length > 0 && (
        <>
          <h3>Recent runs</h3>
          <p className="compare-picks">
            {picked.length >= 2 ? (
              <Link to={`/operator/runs/compare?runs=${picked.join(',')}`} state={{ from }}>
                Compare {picked.length} runs
              </Link>
            ) : (
              <span className="muted">Tick two to four finished runs to compare them.</span>
            )}
            {picked.length > 0 && (
              <button type="button" className="quiet" onClick={clear}>
                Clear {picked.length === 1 ? 'the tick' : `${picked.length} ticks`}
              </button>
            )}
          </p>
          <PagedTable
            label="Recent runs"
            list={history}
            columns={columns}
            rowKey={(run) => run.runId}
            empty="No runs yet."
            noun="runs"
          />
        </>
      )}
    </section>
  );
}

/** The runs, which show what a start or a stop did. Read again with each answer, with the history under them. */
const RUNS = ['bot-runs'] as const;

/**
 * A bot run's start, kept beside the cached queries rather than in its form, which gives way to the run once it is
 * going: leaving the page and coming back finds it on its way or with its answer, under the same key. `going` says a
 * run has been read as going since the page opened, which settles a start whose answer never came.
 */
function useStartRun(simulationId: string, going: boolean) {
  return useOperatorCommand<BotRunRequest, BotRunStarted>('run-start', simulationId, api.startBotRun, {
    reached: () => going,
    confirmedBy: RUNS,
  });
}

type StartRun = ReturnType<typeof useStartRun>;

/**
 * What became of a start. One with no answer is said to have gone through once a run is read as going; one refused
 * because a run is going is said as an earlier start having gone through, whoever's it was. A start with no answer is
 * never followed by one under a new key while it is open, so a refusal is never the operator's own earlier start.
 */
function StartStatus({ start, active }: { start: StartRun; active: boolean }) {
  const { outcome } = start;
  if (start.settled) {
    return (
      <p role="status" className="outcome outcome-ok">
        Your earlier start already went through: the run is going.
      </p>
    );
  }
  if (outcome.kind === 'refused' && outcome.code === 'RUN_ACTIVE' && active) {
    return (
      <p role="alert" className="outcome outcome-rejected">
        A run is already going: an earlier start already went through, so nothing new was started.
      </p>
    );
  }
  return (
    <CommandStatus
      outcome={outcome}
      action="Starting the run"
      done={(started) => `Started a run of ${groupInteger(started.bots)} bots.`}
      onRetry={() => start.retry()}
    />
  );
}

function ActiveRun({
  run,
  now,
  simulationId,
  read,
}: {
  run: BotRun;
  now: number;
  simulationId: string;
  read: Freshness;
}) {
  const stopping = run.desiredState === 'STOPPED';
  const command = useOperatorCommand<string, BotRunStopping>('run-stop', simulationId, api.stopBotRun, {
    reached: (runId) => read === 'fresh' && runId === run.runId && stopping,
    confirmedBy: RUNS,
  });
  // The stop kept is one run's, and outlives that run's panel. A stop sent for a run that has since ended is finished
  // business: never shown beside the next run, never sent again for it, and let go of once it has an answer, if any.
  const earlier = command.args !== undefined && command.args !== run.runId;
  const earlierSending = earlier && command.outcome.kind === 'sending';
  const { forget } = command;
  useEffect(() => {
    if (earlier && !earlierSending) {
      forget();
    }
  }, [earlier, earlierSending, forget]);
  const stop = () => {
    if (earlier) {
      forget();
    }
    command.run(run.runId);
  };
  // A stop answered but not read back yet: the run as shown is not stopping yet, and is not offered a second stop.
  const ownSending = !earlier && (command.outcome.kind === 'sending' || command.awaitingRead);
  const ownUnresolved = !earlier && command.unresolved;
  const unread = notReadYet(read);
  const rate = averageRate(run);
  const timeLeft = timeLeftText(run, now);
  const forgotten = leftRunning(run, now);
  const { activity, config } = run;

  return (
    <div className="run" role="group" aria-label="Current run">
      <div className="panel-header">
        <span className={statusClass(run.status)} role="status" aria-label={`Run status: ${STATUS_LABELS[run.status]}`}>
          {STATUS_LABELS[run.status]}
        </span>
        <button
          type="button"
          className="primary danger"
          disabled={stopping || unread !== null || earlierSending || ownSending || ownUnresolved}
          aria-describedby={
            stopping
              ? 'stop-hint'
              : unread !== null
                ? 'stop-unread'
                : earlierSending
                  ? 'stop-earlier'
                  : forgotten
                    ? 'left-running'
                    : undefined
          }
          onClick={stop}
        >
          Stop run
        </button>
      </div>
      {unread !== null && !stopping && (
        <p id="stop-unread" className="muted">
          {unread}
        </p>
      )}
      {earlierSending && !stopping && (
        <p id="stop-earlier" className="muted">
          A stop sent for the run before is still waiting for its answer.
        </p>
      )}
      {/* Beside the button that sent it, and only this run's. */}
      {earlier ? null : command.settled ? (
        <p role="status" className="outcome outcome-ok">
          Your earlier stop already went through: the run is stopping.
        </p>
      ) : (
        <CommandStatus
          outcome={command.outcome}
          action="Stopping the run"
          done={() => 'Stop requested. New orders stop now; quotes are canceled before the worker confirms.'}
          onRetry={() => command.retry()}
        />
      )}
      {/* The words never change while it shows, so a screen reader says it once, not with every refetch. */}
      {forgotten && (
        <p id="left-running" className="chart-note" role="status">
          <strong>Running for more than a day with no end time.</strong> It keeps trading until someone stops it, and
          takes up again after every restart. Select Stop run to end it.
        </p>
      )}
      {stopping && (
        <p id="stop-hint" className="muted">
          A stop was asked for at {run.stopRequestedAt === null ? 'its end time' : utcTime(run.stopRequestedAt)}; the
          run shows Stopped once the worker confirms.
        </p>
      )}
      <RunProgress run={run} now={now} />
      <dl className="balances">
        <dt>Bots</dt>
        <dd>{strategiesText(config.strategies)}</dd>
        <dt>Instruments</dt>
        <dd>{config.instruments.map((item) => item.symbol).join(', ')}</dd>
        <dt>Seed</dt>
        <dd className="num">{config.seed}</dd>
        <dt>Started</dt>
        <dd>{utcTime(run.startedAt)}</dd>
        <dt>Elapsed</dt>
        <dd>{durationText(elapsedSeconds(run, now))}</dd>
        <dt>Ends</dt>
        <dd>{run.endsAt === null ? 'When stopped' : utcTime(run.endsAt)}</dd>
        {timeLeft !== null && (
          <>
            <dt>Time left</dt>
            <dd>{timeLeft}</dd>
          </>
        )}
        <dt>Last heartbeat</dt>
        <dd>{run.worker.heartbeatAt === null ? 'None yet' : utcTime(run.worker.heartbeatAt)}</dd>
        <dt>Simulator</dt>
        <dd>{run.manifest === null ? 'Not reported yet' : run.manifest.version}</dd>
      </dl>
      {run.manifest !== null && run.manifest.reports > 1 && (
        <p className="chart-note">
          The simulator changed during the run: {run.manifest.reports} different reports. The version and settings shown
          are the first, from {run.manifest.workerId} at {utcTime(run.manifest.reportedAt)}.
        </p>
      )}
      <dl className="counters" aria-label="Activity">
        <div>
          <dt>Placed</dt>
          <dd>{groupInteger(activity.placed)}</dd>
        </div>
        <div>
          <dt>Canceled</dt>
          <dd>{groupInteger(activity.canceled)}</dd>
        </div>
        <div>
          <dt>Rejected</dt>
          <dd>{groupInteger(activity.rejected)}</dd>
        </div>
        <div>
          <dt>Errors</dt>
          <dd>{groupInteger(activity.errors)}</dd>
        </div>
        <div>
          <dt>In flight</dt>
          <dd>{groupInteger(activity.inFlight)}</dd>
        </div>
        <div>
          <dt>Paused</dt>
          <dd>{groupInteger(pausedTotal(activity))}</dd>
        </div>
        <div>
          <dt>Per second</dt>
          <dd>
            {rate ?? '—'} <span className="muted">of {config.commandsPerSecond}</span>
          </dd>
        </div>
      </dl>
      {pausedTotal(activity) > 0 && <p className="muted">Paused steps by reason: {pausedByReasonText(activity)}.</p>}
      <p className="muted hint">
        Counts are what the worker last reported. Per second is the average since the start, against the run&apos;s
        budget. Paused counts the bots&apos; steps that could place nothing new, such as while market data was stale.
      </p>
      {run.manifest !== null && <RunSettings manifest={run.manifest} />}
      {activity.stopFailures > 0 && (
        <p role="alert" className="form-error">
          {groupInteger(activity.stopFailures)} {activity.stopFailures === 1 ? 'order' : 'orders'} could not be
          canceled while stopping.
        </p>
      )}
    </div>
  );
}

function StartForm({
  market,
  instruments,
  population,
  blocked: unavailable,
  start: command,
}: {
  market: MarketStatus['state'];
  instruments: readonly Instrument[];
  population: BotPopulation | undefined;
  blocked: string | null;
  start: StartRun;
}) {
  const [typed, setTyped] = useState<Typed>({});
  const [invalid, setInvalid] = useState<string | null>(null);
  const counts = { ...taggedSplit(population?.byStrategy), ...typed };
  const form: RunForm = {
    ...taggedSplit(population?.byStrategy),
    ...DEFAULTS,
    commandsPerSecond: defaultRate(botsAskedFor(counts)),
    instrumentIds: instruments.map((item) => item.instrumentId),
    ...typed,
  };
  const pace = paceText(form);
  const blocked =
    unavailable ?? startBlocked(market, undefined, population?.byStrategy) ?? shortfallText(form, population?.byStrategy);
  const field = (name: TextField) => ({
    value: form[name],
    onChange: (event: { target: { value: string } }) => setTyped({ ...typed, [name]: event.target.value }),
  });
  const toggle = (instrumentId: string, checked: boolean) =>
    setTyped({
      ...typed,
      instrumentIds: checked
        ? [...form.instrumentIds, instrumentId]
        : form.instrumentIds.filter((id) => id !== instrumentId),
    });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const built = runRequest(form, instruments);
    if ('error' in built) {
      setInvalid(built.error);
      return;
    }
    setInvalid(null);
    command.run(built.request);
  };
  // A start answered but not read back yet: the runs as shown have none going, and a second start would be refused.
  const locked = command.outcome.kind === 'sending' || command.unresolved || command.awaitingRead;

  return (
    <form onSubmit={submit} className="setup-form" aria-label="Start a bot run">
      <fieldset disabled={locked}>
        <div className="field-row">
          <label>
            Market makers
            <input {...field('marketMakers')} inputMode="numeric" />
          </label>
          <label>
            Noise
            <input {...field('noise')} inputMode="numeric" />
          </label>
          <label>
            Momentum
            <input {...field('momentum')} inputMode="numeric" />
          </label>
        </div>
        <div className="field-row">
          <label>
            Commands per second
            <input
              {...field('commandsPerSecond')}
              inputMode="decimal"
              aria-describedby={pace === null ? undefined : 'run-pace'}
            />
          </label>
          <label>
            Largest order
            <input {...field('maxOrderQuantity')} inputMode="numeric" />
          </label>
          <label>
            Position limit
            <input {...field('maxPosition')} inputMode="numeric" />
          </label>
        </div>
        {pace !== null && (
          <p id="run-pace" className="muted hint">
            {pace}
          </p>
        )}
        <div className="field-row">
          <label>
            Seed
            <input {...field('seed')} inputMode="numeric" placeholder="Chosen for you" />
          </label>
        </div>
        <fieldset className="choices run-length">
          <legend>Run length</legend>
          <div className="run-length-choice">
            <label>
              <input
                type="radio"
                name="run-length"
                checked={!form.untilStopped}
                onChange={() => setTyped({ ...typed, untilStopped: false })}
                aria-describedby="run-length-timed"
              />
              For a set time
            </label>
            <p id="run-length-timed" className="muted">
              Ends by itself after the minutes given, from 1 to 1440 (24 hours).
            </p>
            <label className="run-length-minutes">
              Duration in minutes
              <input {...field('durationMinutes')} inputMode="numeric" disabled={form.untilStopped} />
            </label>
          </div>
          <div className="run-length-choice">
            <label>
              <input
                type="radio"
                name="run-length"
                checked={form.untilStopped}
                onChange={() => setTyped({ ...typed, untilStopped: true })}
                aria-describedby="run-length-open"
              />
              Until I stop it
            </label>
            <p id="run-length-open" className="muted">
              Keeps trading until someone selects Stop run, and takes up again after every restart.
            </p>
          </div>
        </fieldset>
        {instruments.length > 0 && (
          <fieldset className="choices">
            <legend>Instruments</legend>
            {instruments.map((item) => (
              <label key={item.instrumentId}>
                <input
                  type="checkbox"
                  checked={form.instrumentIds.includes(item.instrumentId)}
                  onChange={(event) => toggle(item.instrumentId, event.target.checked)}
                />
                {item.symbol}
              </label>
            ))}
          </fieldset>
        )}
        <p className="muted hint">
          {population !== undefined &&
            population.population > 0 &&
            `${groupInteger(population.population)} ${population.population === 1 ? 'bot exists' : 'bots exist'}: ` +
              `${populationText(population.byStrategy)}. `}
          Each strategy is run by the oldest bots tagged with it; untagged bots never run. The same seed and
          observations give the same decisions.
        </p>
        {blocked !== null && (
          <p id="start-blocked" className="muted">
            {blocked}
          </p>
        )}
      </fieldset>
      {/* Outside the fieldset, so that Retry stays usable while the form is locked, and beside the button. */}
      <div className="command-row">
        <button
          type="submit"
          className="primary"
          disabled={blocked !== null || locked}
          aria-describedby={blocked === null ? undefined : 'start-blocked'}
        >
          Start run
        </button>
        <StartStatus start={command} active={false} />
      </div>
      {invalid !== null && (
        <p role="alert" className="outcome outcome-rejected">
          {invalid}
        </p>
      )}
    </form>
  );
}

/**
 * When a run ended, or where it stands while it has not. A run the market's close ended says so: its time is the
 * close's, not a stop its worker confirmed. A run that had ended before the close, with no worker confirming, stays
 * not confirmed, though the close has since recorded its own time on it.
 */
function ended(run: BotRun, closedAt: string | null): string {
  const failures = run.activity.stopFailures > 0 ? ` · ${groupInteger(run.activity.stopFailures)} not canceled` : '';
  if (run.stoppedAt !== null && !stopRecordedByClose(run, closedAt)) {
    return utcTime(run.stoppedAt) + (run.window.endedBy === 'MARKET_CLOSE' ? ' · Market close' : '') + failures;
  }
  return (run.status === 'STOPPED' ? 'Not confirmed' : STATUS_LABELS[run.status]) + failures;
}

function endedColumn(closedAt: string | null): Column<BotRun> {
  return { key: 'ended', header: 'Ended', cell: (run) => ended(run, closedAt) };
}

/** The settings the run's simulator reported, collapsed: the table is drawn only while open. */
function RunSettings({ manifest }: { manifest: BotRunManifest }) {
  const [open, setOpen] = useState(false);
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary id="run-settings-title">Simulator settings</summary>
      {open && <RunSettingsTable manifest={manifest} />}
    </details>
  );
}

/** Scrolls inside the panel on a phone, with the keyboard too, rather than widen it. */
function RunSettingsTable({ manifest }: { manifest: BotRunManifest }) {
  const scroll = useKeyboardScroll<HTMLDivElement>();
  return (
    <div ref={scroll} className="run-settings">
        <SimulatorSettings
          labelledBy="run-settings-title"
          head={
            <thead>
              <tr>
                <th scope="col">Setting</th>
                <th scope="col">Value</th>
              </tr>
            </thead>
          }
          manifests={[manifest]}
        />
    </div>
  );
}

/**
 * A tick per run for the comparison: only a finished run can be ticked, and four at most. One that can't be ticked
 * says why beside it, and in its description, as a disabled control can't be focused to find out.
 */
function compareColumn(picked: readonly string[], pick: (runId: string, on: boolean) => void): Column<BotRun> {
  return {
    key: 'compare',
    header: <span className="visually-hidden">Compare</span>,
    cell: (run) => {
      const on = picked.includes(run.runId);
      const state = comparability(run);
      const reason =
        state === 'NOT_KEPT'
          ? 'No window kept'
          : state === 'UNFINISHED'
            ? 'Not finished'
            : picked.length >= MAX_COMPARED
              ? `${MAX_COMPARED} ticked`
              : null;
      const blocked = !on && reason !== null;
      const why = `compare-why-${run.runId}`;
      return (
        <span className="compare-pick">
          <input
            type="checkbox"
            checked={on}
            disabled={blocked}
            aria-label={`Compare the run started ${utcTime(run.startedAt)}`}
            aria-describedby={blocked ? why : undefined}
            onChange={(event) => pick(run.runId, event.target.checked)}
          />
          {blocked && (
            <span id={why} className="muted">
              {reason}
            </span>
          )}
        </span>
      );
    },
  };
}

/**
 * How long each run lasted, right after its start: on a phone, where the table scrolls inside its panel, both are in
 * view before any scrolling. A run that is not over is counted to now, the time of the last answer about the current
 * run, as its own readout is.
 */
function ranForColumn(now: number): Column<BotRun> {
  return {
    key: 'ran',
    header: 'Ran for',
    cell: (run) => ranForText(run, now),
    sortValue: (run) => ranForSeconds(run, now),
  };
}

const STARTED_COLUMN: Column<BotRun> = {
  key: 'started',
  header: 'Started',
  rowHeader: true,
  cell: (run) => utcTime(run.startedAt),
  sortValue: (run) => run.startedAt,
};

const RUN_COLUMNS: readonly Column<BotRun>[] = [
  { key: 'bots', header: 'Bots', cell: (run) => strategiesText(run.config.strategies) },
  {
    key: 'placed',
    header: 'Placed',
    numeric: true,
    cell: (run) => groupInteger(run.activity.placed),
    sortValue: (run) => run.activity.placed,
  },
  {
    key: 'canceled',
    header: 'Canceled',
    numeric: true,
    cell: (run) => groupInteger(run.activity.canceled),
    sortValue: (run) => run.activity.canceled,
  },
];
