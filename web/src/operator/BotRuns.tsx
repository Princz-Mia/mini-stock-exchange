import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useState } from 'react';

import { api, pollEvery } from '../api/client';
import type { BotRun, BotRunRequest, Instrument, MarketStatus } from '../api/types';
import { groupInteger, utcTime } from '../format';
import {
  activeRun,
  averageRate,
  type RunForm,
  runRequest,
  STATUS_LABELS,
  startBlocked,
  statusClass,
  strategiesText,
  suggestedSplit,
  workerText,
} from './runView';
import { CommandStatus } from './CommandStatus';
import { useCommand } from './useCommand';

type Typed = Partial<Omit<RunForm, 'instrumentIds'>> & { instrumentIds?: readonly string[] };

const DEFAULTS = {
  seed: '',
  commandsPerSecond: '4.00',
  maxOrderQuantity: '10',
  maxPosition: '100',
  durationMinutes: '',
};

/**
 * Bot runs: the one that is running with its worker and activity, a form to start the next, and the recent ones.
 * Everything shown is what Execution committed and what the worker last reported, refetched every two seconds.
 */
export function BotRuns({ market, instruments }: { market: MarketStatus['state']; instruments: readonly Instrument[] }) {
  const runs = useQuery({ queryKey: ['bot-runs'], queryFn: api.botRuns, refetchInterval: pollEvery(2_000) });
  const population = useQuery({ queryKey: ['bots'], queryFn: api.botPopulation });
  const list = runs.data?.runs ?? [];
  const active = activeRun(list);
  const past = list.filter((run) => run !== active).slice(0, 5);

  return (
    <section className="panel" aria-labelledby="runs-title">
      <h2 id="runs-title">Bot runs</h2>
      {runs.isError && (
        <p role="alert" className="form-error">
          The bot runs could not be loaded.
        </p>
      )}
      {runs.isPending ? (
        <p className="muted">Loading…</p>
      ) : active !== undefined ? (
        <ActiveRun run={active} />
      ) : (
        <StartForm
          market={market}
          instruments={instruments}
          population={population.data?.population}
          blocked={runs.isError ? 'The bot runs could not be loaded.' : null}
        />
      )}
      {past.length > 0 && <RecentRuns runs={past} />}
    </section>
  );
}

function ActiveRun({ run }: { run: BotRun }) {
  const client = useQueryClient();
  const send = useCallback(
    async (runId: string, key: string) => {
      try {
        return await api.stopBotRun(runId, key);
      } finally {
        void client.invalidateQueries({ queryKey: ['bot-runs'] });
      }
    },
    [client],
  );
  const command = useCommand(send);
  const stopping = run.desiredState === 'STOPPED';
  const rate = averageRate(run);
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
          disabled={stopping || command.outcome.kind === 'sending' || command.unresolved}
          aria-describedby={stopping ? 'stop-hint' : undefined}
          onClick={() => void command.run(run.runId)}
        >
          Stop run
        </button>
      </div>
      {stopping && (
        <p id="stop-hint" className="muted">
          A stop was asked for at {run.stopRequestedAt === null ? 'its end time' : utcTime(run.stopRequestedAt)}; the
          run shows Stopped once the worker confirms.
        </p>
      )}
      <p className={run.status === 'STALE' ? 'form-error' : 'muted'}>{workerText(run)}</p>
      <dl className="balances">
        <dt>Bots</dt>
        <dd>{strategiesText(config.strategies)}</dd>
        <dt>Instruments</dt>
        <dd>{config.instruments.map((item) => item.symbol).join(', ')}</dd>
        <dt>Seed</dt>
        <dd className="num">{config.seed}</dd>
        <dt>Started</dt>
        <dd>{utcTime(run.startedAt)}</dd>
        <dt>Ends</dt>
        <dd>{run.endsAt === null ? 'When stopped' : utcTime(run.endsAt)}</dd>
        <dt>Last heartbeat</dt>
        <dd>{run.worker.heartbeatAt === null ? 'None yet' : utcTime(run.worker.heartbeatAt)}</dd>
      </dl>
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
          <dt>Per second</dt>
          <dd>
            {rate ?? '—'} <span className="muted">of {config.commandsPerSecond}</span>
          </dd>
        </div>
      </dl>
      <p className="muted hint">
        Counts are what the worker last reported. Per second is the average since the start, against the run&apos;s
        budget.
      </p>
      {activity.stopFailures > 0 && (
        <p role="alert" className="form-error">
          {groupInteger(activity.stopFailures)} {activity.stopFailures === 1 ? 'order' : 'orders'} could not be
          canceled while stopping.
        </p>
      )}
      <CommandStatus
        outcome={command.outcome}
        done={() => 'Stop requested. New orders stop now; quotes are canceled before the worker confirms.'}
        onRetry={() => void command.retry()}
      />
    </div>
  );
}

function StartForm({
  market,
  instruments,
  population,
  blocked: unavailable,
}: {
  market: MarketStatus['state'];
  instruments: readonly Instrument[];
  population: number | undefined;
  blocked: string | null;
}) {
  const client = useQueryClient();
  const [typed, setTyped] = useState<Typed>({});
  const [invalid, setInvalid] = useState<string | null>(null);
  const send = useCallback(
    async (request: BotRunRequest, key: string) => {
      try {
        return await api.startBotRun(request, key);
      } finally {
        void client.invalidateQueries({ queryKey: ['bot-runs'] });
      }
    },
    [client],
  );
  const command = useCommand(send);
  const form: RunForm = {
    ...suggestedSplit(population ?? 0),
    ...DEFAULTS,
    instrumentIds: instruments.map((item) => item.instrumentId),
    ...typed,
  };
  const blocked = unavailable ?? startBlocked(market, undefined, population);
  const field = (name: keyof Omit<RunForm, 'instrumentIds'>) => ({
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
    void command.run(built.request);
  };

  return (
    <form onSubmit={submit} className="setup-form" aria-label="Start a bot run">
      <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
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
            <input {...field('commandsPerSecond')} inputMode="decimal" />
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
        <div className="field-row">
          <label>
            Seed
            <input {...field('seed')} inputMode="numeric" placeholder="Chosen for you" />
          </label>
          <label>
            Duration in minutes
            <input {...field('durationMinutes')} inputMode="numeric" placeholder="Until stopped" />
          </label>
        </div>
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
          {population !== undefined && `${groupInteger(population)} bots exist. `}
          Market makers are assigned first, then noise, then momentum bots, in the order the bots were created. The
          same seed and observations give the same decisions.
        </p>
        {blocked !== null && (
          <p id="start-blocked" className="muted">
            {blocked}
          </p>
        )}
        <button
          type="submit"
          className="primary"
          disabled={blocked !== null}
          aria-describedby={blocked === null ? undefined : 'start-blocked'}
        >
          Start run
        </button>
      </fieldset>
      {invalid !== null && (
        <p role="alert" className="outcome outcome-rejected">
          {invalid}
        </p>
      )}
      <CommandStatus
        outcome={command.outcome}
        done={(started) => `Started a run of ${groupInteger(started.bots)} bots.`}
        onRetry={() => void command.retry()}
      />
    </form>
  );
}

function RecentRuns({ runs }: { runs: readonly BotRun[] }) {
  return (
    <table aria-label="Recent runs">
      <thead>
        <tr>
          <th scope="col">Started</th>
          <th scope="col">Bots</th>
          <th scope="col" className="num">
            Placed
          </th>
          <th scope="col" className="num">
            Canceled
          </th>
          <th scope="col">Ended</th>
        </tr>
      </thead>
      <tbody>
        {runs.map((run) => (
          <tr key={run.runId}>
            <th scope="row">{utcTime(run.startedAt)}</th>
            <td>{strategiesText(run.config.strategies)}</td>
            <td className="num">{groupInteger(run.activity.placed)}</td>
            <td className="num">{groupInteger(run.activity.canceled)}</td>
            <td>
              {run.stoppedAt === null ? 'Not confirmed' : utcTime(run.stoppedAt)}
              {run.activity.stopFailures > 0 && ` · ${groupInteger(run.activity.stopFailures)} not canceled`}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
