import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useEffect, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { BotJob, BotJobRequest, Instrument } from '../api/types';
import { groupInteger } from '../format';
import {
  milestone,
  mixText,
  outcomeText,
  percentMade,
  progressText,
  resolveMix,
  strategiesText,
  timeLeftText,
  USUAL_SHARES,
} from './botJob';
import { CommandStatus } from './CommandStatus';
import { EXAMPLE_CASH, holdingsFrom, ShareFields, type ShareInputs } from './Endowment';
import { refusedFields, refusedHoldings, useFocusOnRefusal } from './refusals';
import { populationText, STRATEGIES } from './strategies';
import { useCommand } from './useCommand';

/** The job as the page last read it: none yet, none at all, or the newest, and whether the last read failed. */
export interface JobRead {
  job: BotJob | null | undefined;
  unreachable: boolean;
}

/**
 * The bot population by strategy and, during setup, one form that creates any number of bots, as a job the exchange
 * runs in the background. While it runs, the panel shows its progress as the exchange last said it: the same on
 * every screen, after a reload, and after leaving the page. Every bot of a job gets the same endowment and its own
 * token, which goes to the simulator and is never shown here.
 */
export function BotSetup({
  instruments,
  editable,
  closed = false,
  read = { job: undefined, unreachable: false },
}: {
  instruments: readonly Instrument[];
  editable: boolean;
  /** The market has closed: its bots are listed, and trade no more. */
  closed?: boolean;
  read?: JobRead;
}) {
  const client = useQueryClient();
  const population = useQuery({ queryKey: ['bots'], queryFn: api.botPopulation });
  const job = read.job;
  const running = job?.state === 'RUNNING';
  const bots = population.data;
  const start = useStartJob();
  const outcomeId = useId();

  return (
    <section className="panel" aria-labelledby="bots-title">
      <h2 id="bots-title">Bots</h2>
      {population.isError ? (
        <p role="alert" className="form-error">
          The bot population could not be loaded.
        </p>
      ) : bots === undefined ? (
        <p className="muted">Loading…</p>
      ) : (
        <div className="population">
          <p>
            {bots.population === 0
              ? 'No bots yet'
              : `${groupInteger(bots.population)} ${bots.population === 1 ? 'bot' : 'bots'}`}
          </p>
          {bots.population > 0 && <p className="muted">{populationText(bots.byStrategy)}</p>}
        </div>
      )}
      {editable && <Announcer job={job} />}
      {editable ? (
        running ? (
          <JobProgress
            job={job}
            unreachable={read.unreachable}
            onStopped={(stopped) => client.setQueryData(['bot-job'], stopped)}
          />
        ) : (
          <>
            {job != null && (
              <p className={`outcome ${job.state === 'DONE' ? 'outcome-ok' : 'outcome-rejected'}`}>{outcomeText(job)}</p>
            )}
            <CreateBots instruments={instruments} start={start} outcomeId={outcomeId} />
          </>
        )
      ) : (
        <p className="muted">
          {closed
            ? "The market has closed, so no bot can be created. These bots trade no more: the close revoked every bot's token."
            : 'Bots are created before the market opens; the population is fixed after that.'}
        </p>
      )}
      {editable && (
        <div id={outcomeId}>
          {start.command.outcome.kind !== 'done' && (
            <CommandStatus
              outcome={start.command.outcome}
              done={() => ''}
              onRetry={() => void start.command.retry()}
            />
          )}
        </div>
      )}
    </section>
  );
}

/**
 * Tells a screen reader how the job goes at its start, at each quarter and at its end, in a live region that is on
 * the page from the first: never on every read, and nothing for a job found as it was on opening the page.
 */
function Announcer({ job }: { job: BotJob | null | undefined }) {
  const said = job == null ? null : milestone(job);
  const first = useRef<string | null | undefined>(undefined);
  const [announced, setAnnounced] = useState('');
  useEffect(() => {
    if (first.current === undefined) {
      if (said !== null) {
        first.current = said;
      }
      return;
    }
    if (said !== null && said !== first.current) {
      first.current = said;
      setAnnounced(said);
    }
  }, [said]);
  return (
    <p role="status" className="visually-hidden">
      {announced}
    </p>
  );
}

/** A running job: the bar, the count and percentage, the time left, each strategy, and Stop. */
function JobProgress({
  job,
  unreachable,
  onStopped,
}: {
  job: BotJob;
  unreachable: boolean;
  onStopped: (job: BotJob) => void;
}) {
  const send = useCallback(
    async (_: null, key: string) => {
      const stopped = await api.stopBotJob(job.jobId, key);
      onStopped(stopped);
      return stopped;
    },
    [job.jobId, onStopped],
  );
  const stop = useCommand(send);
  const titleId = useId();
  const stopHint = useId();
  const percent = percentMade(job);
  return (
    <div className="bot-job">
      <h3 id={titleId}>Creating bots</h3>
      <div
        role="progressbar"
        aria-labelledby={titleId}
        aria-valuemin={0}
        aria-valuemax={job.total}
        aria-valuenow={job.created}
        aria-valuetext={progressText(job)}
        className="job-bar"
      >
        <div className="job-bar-fill" style={{ width: `${percent}%` }} />
      </div>
      <p className="job-figures">
        <span className="num">{progressText(job)}</span> · {timeLeftText(job.secondsLeft)}
      </p>
      <p className="muted">{strategiesText(job)}</p>
      {job.resumed > 0 && (
        <p className="muted">
          The exchange restarted during this job and took it up again where it was; no bot is made twice.
        </p>
      )}
      {unreachable && (
        <p role="alert" className="form-error">
          Interrupted: the exchange is not answering, so this progress may be out of date. The job goes on at the
          exchange, and its progress shows here again once the exchange answers.
        </p>
      )}
      <p className="muted hint">
        The exchange goes on with this when you leave or reload the page, and every operator sees the same progress.
        The market can open once it is done or stopped.
      </p>
      <div className="job-actions">
        <button
          type="button"
          className="quiet"
          onClick={() => void stop.run(null)}
          disabled={stop.outcome.kind === 'sending'}
          aria-describedby={stopHint}
        >
          Stop creating bots
        </button>
        <span id={stopHint} className="muted">
          The bots made so far stay.
        </span>
      </div>
      {stop.outcome.kind !== 'done' && (
        <CommandStatus outcome={stop.outcome} done={() => ''} onRetry={() => void stop.retry()} />
      )}
    </div>
  );
}

/**
 * Any number of bots, with the usual split preselected: a fifth market makers, a fifth momentum and the rest noise,
 * as the demo seed makes them. The exact count of each is shown as it is typed, and that is what is sent. The command
 * keeps its key until the exchange gives a final answer, as every operator command does.
 */
function CreateBots({
  instruments,
  start,
  outcomeId,
}: {
  instruments: readonly Instrument[];
  start: ReturnType<typeof useStartJob>;
  outcomeId: string;
}) {
  const { command, sent, setSent } = start;
  const [count, setCount] = useState('');
  const [makers, setMakers] = useState<string>(USUAL_SHARES.marketMakers);
  const [momentum, setMomentum] = useState<string>(USUAL_SHARES.momentum);
  const [cash, setCash] = useState('');
  const [shares, setShares] = useState<ShareInputs>({});
  const form = useRef<HTMLFormElement>(null);
  const mixId = useId();
  const invalid = refusedFields(command.outcome);
  const invalidShares = refusedHoldings(invalid, sent);
  useFocusOnRefusal(command.outcome.kind, form);
  const marked = (field: string) => ({
    'aria-invalid': invalid.has(field) || undefined,
    'aria-describedby': invalid.has(field) ? outcomeId : undefined,
  });
  const resolved = resolveMix(count, { marketMakers: makers, momentum });
  const noiseShare = (() => {
    const left = 100 - Number(makers.trim()) - Number(momentum.trim());
    return Number.isInteger(left) && left >= 0 && left <= 100 ? `${left}%` : '—';
  })();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if ('problem' in resolved) {
      return;
    }
    const holdings = holdingsFrom(instruments, shares);
    setSent(holdings);
    void command.run({ count: resolved.total, strategies: resolved.mix, cash: cash.trim(), holdings });
  };

  return (
    <>
      <form ref={form} onSubmit={submit} className="setup-form" aria-label="Create bots">
        <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
          <div className="field-row">
            <label>
              Number of bots
              <input
                value={count}
                onChange={(event) => setCount(event.target.value)}
                inputMode="numeric"
                placeholder="e.g. 20,000"
                aria-describedby={invalid.has('count') ? outcomeId : mixId}
                aria-invalid={invalid.has('count') || undefined}
              />
            </label>
            <label>
              Cash each
              <input
                value={cash}
                onChange={(event) => setCash(event.target.value)}
                inputMode="decimal"
                placeholder={`e.g. ${EXAMPLE_CASH}`}
                {...marked('cash')}
              />
            </label>
          </div>
          <fieldset className="choices strategies">
            <legend>Strategy mix</legend>
            <div className="field-row">
              <label>
                Market makers, %
                <input
                  value={makers}
                  onChange={(event) => setMakers(event.target.value)}
                  inputMode="numeric"
                  aria-describedby={mixId}
                  aria-invalid={invalid.has('strategies') || undefined}
                />
              </label>
              <label>
                Momentum, %
                <input
                  value={momentum}
                  onChange={(event) => setMomentum(event.target.value)}
                  inputMode="numeric"
                  aria-describedby={mixId}
                  aria-invalid={invalid.has('strategies') || undefined}
                />
              </label>
              <p className="mix-rest">
                Noise: the rest, <span className="num">{noiseShare}</span>
              </p>
            </div>
            <p id={mixId} className={'problem' in resolved && count.trim() !== '' ? 'form-error' : 'muted'}>
              {'problem' in resolved
                ? resolved.problem
                : `That is ${mixText(resolved.mix)}: ${groupInteger(resolved.total)} in all.`}
            </p>
            <ul className="muted strategy-notes">
              {STRATEGIES.map((strategy) => (
                <li key={strategy.value}>
                  <strong>{strategy.label}:</strong> {strategy.description}
                </li>
              ))}
            </ul>
          </fieldset>
          <ShareFields
            instruments={instruments}
            shares={shares}
            onChange={setShares}
            invalid={invalidShares}
            describedBy={outcomeId}
          />
          <p className="muted hint">
            Any number, made in the background in parts while you go on setting up. Each bot gets the same endowment
            and its own token for the simulator; a blank or 0 gives none of that instrument. Each share is rounded
            down, and noise gets the rest.
          </p>
          <button
            type="submit"
            className="primary"
            disabled={'problem' in resolved}
            aria-describedby={'problem' in resolved ? mixId : undefined}
          >
            Create bots
          </button>
        </fieldset>
      </form>
    </>
  );
}

/**
 * Starting a job, kept above the form: a refusal for a job another operator started shows beside that job's progress,
 * which takes the form's place. The holdings last sent are kept for the fields a refusal names.
 */
function useStartJob() {
  const client = useQueryClient();
  const send = useCallback(
    async (request: BotJobRequest, key: string) => {
      try {
        const started = await api.startBotJob(request, key);
        client.setQueryData(['bot-job'], started);
        return started;
      } finally {
        // A refusal for a job another operator started shows that job here.
        void client.invalidateQueries({ queryKey: ['bot-job'] });
      }
    },
    [client],
  );
  const command = useCommand(send);
  const [sent, setSent] = useState<readonly { instrumentId: string }[]>([]);
  return { command, sent, setSent };
}
