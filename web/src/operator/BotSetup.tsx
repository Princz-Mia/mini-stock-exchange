import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useEffect, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { BotJob, BotJobRequest, Instrument } from '../api/types';
import { groupInteger, utcTime } from '../format';
import {
  milestone,
  mixText,
  outcomeText,
  percentMade,
  progressText,
  resolveMix,
  stalledText,
  strategiesText,
  timeLeftText,
  USUAL_SHARES,
} from './botJob';
import { CommandStatus } from './CommandStatus';
import { EXAMPLE_CASH, holdingsFrom, ShareFields, type ShareInputs } from './Endowment';
import { refusedFields, refusedHoldings, useFocusOnRefusal } from './refusals';
import { populationText, STRATEGIES } from './strategies';
import { useBotJob, useRefreshOnJobChange } from './useBotJob';
import { useCommand } from './useCommand';

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
}: {
  instruments: readonly Instrument[];
  editable: boolean;
  /** The market has closed: its bots are listed, and trade no more. */
  closed?: boolean;
}) {
  const client = useQueryClient();
  const population = useQuery({ queryKey: ['bots'], queryFn: api.botPopulation });
  // The job as the page last read it: none yet, none at all, or the newest. Read only while bots can be created.
  const read = useBotJob(editable);
  const job = editable ? read.data : undefined;
  useRefreshOnJobChange(job);
  const running = job?.state === 'RUNNING';
  const bots = population.data;
  const start = useStartJob(job?.jobId ?? null);
  const { outcome, retry, forget } = start.command;
  const outcomeId = useId();
  const outcomeLine = useRef<HTMLParagraphElement>(null);

  // When a job ends, a refusal that named it is out of date, and focus lost with its Stop button goes to what the job
  // came to, so a keyboard goes on from there. Focus the user has moved elsewhere stays where it is.
  const wasRunning = useRef(running);
  useEffect(() => {
    const ended = wasRunning.current && !running;
    wasRunning.current = running;
    if (!ended) {
      return;
    }
    if (outcome.kind === 'refused' && outcome.code === 'BOT_JOB_RUNNING') {
      forget();
    }
    if (focusLost()) {
      outcomeLine.current?.focus();
    }
  }, [running, outcome, forget]);

  // The page's own start, or its refusal by the job that took the form's place: focus goes to that job's heading.
  const ownJob =
    (outcome.kind === 'done' && outcome.result.jobId === job?.jobId) ||
    (outcome.kind === 'refused' && outcome.code === 'BOT_JOB_RUNNING');

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
            unreachable={read.isError}
            focusOnOpen={ownJob}
            onStopped={(stopped) => client.setQueryData(['bot-job'], stopped)}
          />
        ) : (
          <>
            {job != null && (
              <p
                ref={outcomeLine}
                tabIndex={-1}
                className={`outcome ${job.state === 'DONE' ? 'outcome-ok' : 'outcome-rejected'}`}
              >
                {outcomeText(job)}
              </p>
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
          {outcome.kind === 'refused' && outcome.code === 'BOT_JOB_RUNNING' ? (
            // The exchange's words quote that job's progress as it was then; the bar above has it as it is now.
            <p role="alert" className="outcome outcome-rejected">
              Not started: a job is already creating bots, shown above. Wait for it to finish, or stop it; what you
              typed is kept for then.
            </p>
          ) : (
            outcome.kind !== 'done' && (
              <CommandStatus outcome={outcome} action="Starting the bot job" done={() => ''} onRetry={() => void retry()} />
            )
          )}
        </div>
      )}
    </section>
  );
}

/** Focus has fallen to the page: the control that had it was disabled or taken off the page. */
function focusLost(): boolean {
  return document.activeElement === null || document.activeElement === document.body;
}

/**
 * Tells a screen reader how the job goes at its start, at each quarter and at its end, in a live region that is on
 * the page from the first: never on every read. The first read is the panel as it opened, so a job found running then
 * is shown, not announced; a job started after a first read that found none is announced from its start.
 */
function Announcer({ job }: { job: BotJob | null | undefined }) {
  // Undefined until the job has been read; null when the read found none.
  const said = job === undefined ? undefined : job === null ? null : milestone(job);
  const last = useRef<string | null | undefined>(undefined);
  const [announced, setAnnounced] = useState('');
  useEffect(() => {
    if (said === undefined) {
      return;
    }
    const before = last.current;
    last.current = said;
    if (before !== undefined && said !== null && said !== before) {
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
  focusOnOpen,
  onStopped,
}: {
  job: BotJob;
  unreachable: boolean;
  /** This page started the job, or was refused by it: focus, lost with the form, goes to its heading once. */
  focusOnOpen: boolean;
  onStopped: (job: BotJob) => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const focused = useRef(false);
  useEffect(() => {
    if (focusOnOpen && !focused.current) {
      focused.current = true;
      if (focusLost()) {
        heading.current?.focus();
      }
    }
  }, [focusOnOpen]);
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
      <h3 id={titleId} ref={heading} tabIndex={-1}>
        Creating bots
      </h3>
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
        <span className="num">{progressText(job)}</span> ·{' '}
        {job.stalled ? stalledText(job) : timeLeftText(job.secondsLeft)}
      </p>
      <p className="muted">{strategiesText(job)}</p>
      {job.resumed > 0 && (
        <p className="muted">
          The exchange restarted during this job and took it up again where it was; no bot is made twice.
        </p>
      )}
      {job.stalled && !unreachable && (
        <p role="alert" className="form-error">
          Interrupted: no bots have been made since {utcTime(job.lastProgressAt)}, so the time left is unknown. The
          exchange may have stopped taking commands. Restarting the exchange takes the job up again where it was, and
          no bot is made twice; Stop keeps the bots made so far.
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
        <CommandStatus
          outcome={stop.outcome}
          action="Stopping the bot job"
          done={() => ''}
          onRetry={() => void stop.retry()}
        />
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
  const { command, sent, setSent, send } = start;
  const { count, setCount, makers, setMakers, momentum, setMomentum, cash, setCash, shares, setShares } = start.fields;
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
    send({ count: resolved.total, strategies: resolved.mix, cash: cash.trim(), holdings });
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
 * Starting a job, kept above the form with what was typed in it: a job that takes the form's place, another
 * operator's that refused this one among them, leaves the figures as they were for when the form comes back. The
 * holdings last sent are kept for the fields a refusal names.
 *
 * A start whose answer was lost is settled by sending it again under its key once a job other than the one
 * `shownId` named when it was sent appears: its own job is answered from its stored answer, and one another
 * operator started gets the refusal it would have had. Never a second job: one runs at a time, and the key is the
 * same.
 */
function useStartJob(shownId: string | null) {
  const client = useQueryClient();
  const startJob = useCallback(
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
  const command = useCommand(startJob);
  const [sent, setSent] = useState<readonly { instrumentId: string }[]>([]);
  const shownAtSend = useRef<string | null>(null);
  const { unresolved, retry, run } = command;
  useEffect(() => {
    if (unresolved && shownId !== null && shownId !== shownAtSend.current) {
      shownAtSend.current = shownId;
      void retry();
    }
  }, [unresolved, shownId, retry]);
  const send = useCallback(
    (job: BotJobRequest) => {
      // A start sent again while unresolved is the same one, sent over the job shown then. One sent before the job
      // was first read counts as sent over none: whatever job appears, sending it again under its key is safe.
      if (!unresolved) {
        shownAtSend.current = shownId;
      }
      void run(job);
    },
    [unresolved, shownId, run],
  );
  const [count, setCount] = useState('');
  const [makers, setMakers] = useState<string>(USUAL_SHARES.marketMakers);
  const [momentum, setMomentum] = useState<string>(USUAL_SHARES.momentum);
  const [cash, setCash] = useState('');
  const [shares, setShares] = useState<ShareInputs>({});
  const fields = { count, setCount, makers, setMakers, momentum, setMomentum, cash, setCash, shares, setShares };
  return { command, send, sent, setSent, fields };
}
