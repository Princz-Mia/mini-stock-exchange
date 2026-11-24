import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';

import { api } from '../api/client';
import type { BotJob, MarketClosed, MarketStatus, MarketTransition } from '../api/types';
import { CloseTime, scheduledCloseWords } from '../app/closeTime';
import { groupInteger, utcDateTime, utcTime } from '../format';
import { TermTip } from '../help/TermTip';
import { progressText } from './botJob';
import { useCloseCommand } from './closeCommand';
import { CloseMarketDialog } from './CloseMarketDialog';
import { CommandStatus, Waiting } from './CommandStatus';
import { type Freshness, notReadYet } from './freshRead';
import { useOperatorCommand } from './operatorCommands';
import { useBotJob } from './useBotJob';
import type { CommandOutcome } from './useCommand';

type Transition = 'open' | 'halt' | 'resume';
type Action = Transition | 'close';

/** A transition as sent: an opening may carry a duration, in minutes. */
interface Sent {
  action: Transition;
  durationMinutes?: number;
}

interface Done {
  action: Transition;
  transition: MarketTransition;
}

const STATE_LABELS: Record<MarketStatus['state'], string> = {
  NOT_SET_UP: 'Not set up',
  SETUP: 'Setup',
  OPEN: 'Open',
  HALTED: 'Halted',
  CLOSED: 'Closed',
};

const DONE: Record<Transition, string> = {
  open: 'The market is open.',
  halt: 'Trading is halted. Cancels still work.',
  resume: 'Trading has resumed.',
};

/** Each transition while it is on its way. */
const SENDING: Record<Transition, string> = {
  open: 'Opening the market',
  halt: 'Halting trading',
  resume: 'Resuming trading',
};

/** Whether the market's state shows what a transition asked for: open for an opening, halted for a halt. */
export function transitionReached(action: Transition, state: MarketStatus['state']): boolean {
  switch (action) {
    case 'open':
      return state === 'OPEN' || state === 'HALTED' || state === 'CLOSED';
    case 'halt':
      return state === 'HALTED';
    case 'resume':
      return state === 'OPEN';
  }
}

/** A transition that had no answer here, and that the market's state now shows went through. */
const WENT_THROUGH: Record<Transition, string> = {
  open: 'Your earlier opening already went through: the market has opened.',
  halt: 'Your earlier halt already went through: trading is halted.',
  resume: 'Your earlier resume already went through: trading has resumed.',
};

/** A transition refused because the market was already where it asked to go, with no earlier one known here. */
const ALREADY: Record<Transition, string> = {
  open: 'The market has already opened: an earlier opening already went through, so this one changed nothing.',
  halt: 'Trading is already halted: an earlier halt already went through, so this one changed nothing.',
  resume: 'Trading has already resumed: an earlier resume already went through, so this one changed nothing.',
};

/**
 * A refused transition, in words. One refused only because the market already is where it asked to go, as the state
 * read now shows, is said as the earlier command having gone through, not as a failure: the operator's own earlier
 * one, when one went unanswered here, or else whoever's it was.
 */
export function transitionRefusal(
  outcome: Extract<CommandOutcome<unknown>, { kind: 'refused' }>,
  action: Transition,
  state: MarketStatus['state'],
  earlier: Transition | undefined,
): string {
  const refusedIn = outcome.problem?.state as MarketStatus['state'] | undefined;
  const already =
    outcome.code === 'MARKET_TRANSITION_NOT_ALLOWED' &&
    refusedIn !== undefined &&
    transitionReached(action, refusedIn) &&
    transitionReached(action, state);
  if (!already) {
    return outcome.message;
  }
  return earlier === action ? `${WENT_THROUGH[action]} This one changed nothing.` : ALREADY[action];
}

/** What a transition did, and for an opening with a duration when the exchange closes the market. */
function doneText({ action, transition }: Done): string {
  const closesAt = transition.closesAt ?? null;
  return action === 'open' && closesAt !== null
    ? `The market is open. The exchange closes it by itself at ${utcDateTime(closesAt)}.`
    : DONE[action];
}

/** The shortest and longest set time an opening may have, as the exchange takes them: 5 minutes to 30 days. */
export const MIN_OPEN_MINUTES = 5;
export const MAX_OPEN_MINUTES = 30 * 24 * 60;

/** How long the market is to stay open, as typed: until it is closed, or for hours and minutes. */
export interface MarketLength {
  timed: boolean;
  hours: string;
  minutes: string;
}

/**
 * The duration in minutes a market length asks for: undefined for one that stays open until it is closed, or why the
 * hours and minutes typed cannot be sent.
 */
export function openDuration(length: MarketLength): { minutes: number | undefined } | { problem: string } {
  if (!length.timed) {
    return { minutes: undefined };
  }
  const hours = length.hours.trim() === '' ? '0' : length.hours.trim();
  const minutes = length.minutes.trim() === '' ? '0' : length.minutes.trim();
  if (!/^\d{1,4}$/.test(hours) || !/^\d{1,4}$/.test(minutes)) {
    return { problem: 'Give the set time in whole hours and minutes.' };
  }
  if (Number(minutes) > 59) {
    return { problem: 'Minutes go up to 59; give a longer time in hours.' };
  }
  const total = Number(hours) * 60 + Number(minutes);
  if (total < MIN_OPEN_MINUTES) {
    return { problem: `A set time is at least ${MIN_OPEN_MINUTES} minutes.` };
  }
  if (total > MAX_OPEN_MINUTES) {
    return { problem: 'A set time is at most 30 days (720 hours).' };
  }
  return { minutes: total };
}

/**
 * How long the market stays open once it opens: until an operator closes it, the default, or for a set time, after
 * which the exchange closes it by itself. The same choice as a bot run's length.
 */
function MarketLengthChoice({
  length,
  onChange,
  problem,
  disabled,
}: {
  length: MarketLength;
  onChange: (length: MarketLength) => void;
  problem: string | null;
  disabled: boolean;
}) {
  return (
    <fieldset className="choices run-length" disabled={disabled}>
      <legend>Market length</legend>
      <div className="run-length-choice">
        <label>
          <input
            type="radio"
            name="market-length"
            checked={!length.timed}
            onChange={() => onChange({ ...length, timed: false })}
            aria-describedby="market-length-open"
          />
          Until I close it
        </label>
        <p id="market-length-open" className="muted">
          Trades until someone selects Close the market.
        </p>
      </div>
      <div className="run-length-choice">
        <label>
          <input
            type="radio"
            name="market-length"
            checked={length.timed}
            onChange={() => onChange({ ...length, timed: true })}
            aria-describedby="market-length-timed"
          />
          For a set time
        </label>
        <p id="market-length-timed" className="muted">
          The exchange closes the market by itself once the time is up, from 5 minutes to 30 days, counted on its own
          clock from the opening. You can still close it sooner.
        </p>
        <div className="field-row">
          <label className="run-length-minutes">
            Hours
            <input
              value={length.hours}
              onChange={(event) => onChange({ ...length, hours: event.target.value })}
              inputMode="numeric"
              disabled={!length.timed}
              aria-invalid={length.timed && problem !== null}
              aria-describedby={problem === null ? undefined : 'market-length-problem'}
            />
          </label>
          <label className="run-length-minutes">
            Minutes
            <input
              value={length.minutes}
              onChange={(event) => onChange({ ...length, minutes: event.target.value })}
              inputMode="numeric"
              disabled={!length.timed}
              aria-invalid={length.timed && problem !== null}
              aria-describedby={problem === null ? undefined : 'market-length-problem'}
            />
          </label>
        </div>
        {length.timed && problem !== null && (
          <p id="market-length-problem" className="field-error" role="alert">
            {problem}
          </p>
        )}
      </div>
    </fieldset>
  );
}

/**
 * What the market is called on this page: the name it was started with. One nobody named, which the first setup
 * command created, is said to have none, and no name is shown before any market exists.
 */
function marketName(market: Pick<MarketStatus, 'name' | 'simulationId'>): string {
  if (market.name !== null && market.name !== undefined) {
    return market.name;
  }
  return market.simulationId === null ? 'No market yet' : 'Not named';
}

/** What a close did, from its answer: how many resting orders it cancelled and how many bot runs it ended. */
export function closedText(closed: MarketClosed): string {
  const orders =
    closed.canceledOrders === 0
      ? 'No order was resting'
      : `${groupInteger(closed.canceledOrders)} resting ${closed.canceledOrders === 1 ? 'order was' : 'orders were'} cancelled`;
  const runs =
    closed.endedRuns === 0
      ? 'no bot run was going'
      : `${groupInteger(closed.endedRuns)} bot ${closed.endedRuns === 1 ? 'run' : 'runs'} ended`;
  return `The market is closed. ${orders}, and ${runs}.`;
}

/**
 * Why an action is unavailable right now, or null when it is allowed. The instruments are counted once their list has
 * been read; until then a market in setup is not said to have none. Bots are made only in setup, so a market does
 * not open while a job is still making them.
 */
function blocked(
  action: Action,
  state: MarketStatus['state'],
  instruments: number | undefined,
  job: BotJob | null | undefined,
  read: Freshness,
): string | null {
  // The state shown may be from before this page opened, and a command sent meanwhile may have changed it.
  const unread = notReadYet(read);
  if (unread !== null) {
    return unread;
  }
  switch (action) {
    case 'open':
      if (state === 'NOT_SET_UP') return 'Start a simulation or add an instrument first; either creates the market.';
      if (state !== 'SETUP') return 'The market has already opened. It opens only once.';
      if (job?.state === 'RUNNING') {
        const moving = job.stalled ? `, but nothing has moved since ${utcTime(job.lastProgressAt)}` : '';
        return `Bots are still being created${moving}: ${progressText(job)}. The market can open once the job is done or stopped.`;
      }
      if (instruments === undefined) return 'Reading the instruments…';
      return instruments === 0 ? 'Add at least one instrument first.' : null;
    case 'halt':
      return state === 'OPEN' ? null : 'Only an open market can be halted.';
    case 'resume':
      return state === 'HALTED' ? null : 'Only a halted market can be resumed.';
    case 'close':
      return state === 'OPEN' || state === 'HALTED' ? null : 'Only a market that has opened can be closed.';
  }
}

const ACTIONS: readonly { action: Action; label: string }[] = [
  { action: 'open', label: 'Open market' },
  { action: 'halt', label: 'Halt trading' },
  { action: 'resume', label: 'Resume trading' },
  { action: 'close', label: 'Close the market' },
];

/**
 * A close left unfinished, in words. The count is an upper bound: it is the exchange's own from when it stopped, and
 * a transaction of the close whose outcome it could not learn may have cancelled some of those orders already. So it
 * is said as "at most", never as how many rest.
 */
export function unfinishedText(stillResting: number | null): string {
  if (stillResting === 0) {
    return 'The market is closed and the exchange counted no order still resting, but the close did not finish. The exchange has stopped trading and finishes the close when it is restarted.';
  }
  const resting =
    stillResting === null
      ? 'some orders still rest'
      : `at most ${groupInteger(stillResting)} ${stillResting === 1 ? 'order still rests' : 'orders still rest'}`;
  const bound = stillResting === null ? '' : " The count is the exchange's own from when it stopped; fewer may rest.";
  return `The market is closed, but ${resting}. The exchange has stopped trading and cancels ${stillResting === 1 ? 'it' : 'them'} when it is restarted.${bound}`;
}

/**
 * A refused close, in words. The exchange says which state the market was in: a close that found it closed already
 * changed nothing, which is not a failure to report as one.
 */
function closeRefusal(
  outcome: Extract<CommandOutcome<MarketClosed>, { kind: 'refused' }>,
  scheduled: string | null,
): string {
  if (outcome.code !== 'MARKET_TRANSITION_NOT_ALLOWED') {
    return outcome.message;
  }
  switch (outcome.problem?.state) {
    case 'CLOSED':
      return scheduled !== null
        ? `The market had already closed ${scheduled}, so this close changed nothing.`
        : 'The market had already closed, so this close changed nothing.';
    case 'SETUP':
      return 'The market has not opened yet, and only a market that has opened can be closed. Nothing was changed.';
    default:
      return outcome.message;
  }
}

/**
 * What became of a close, which has more answers than the other transitions: it can take long, and it can stop half
 * way with the market already closed. Each is said as what it is, and the same key is sent again only where asking
 * again can bring something new. The market's own state is part of it: once the status says CLOSED, a close with no
 * answer is not said to be unknown, whatever the exchange answered or failed to.
 */
function CloseStatus({
  outcome,
  state,
  scheduled,
  stillResting,
  askedBefore,
  onRetry,
}: {
  outcome: CommandOutcome<MarketClosed>;
  state: MarketStatus['state'];
  /**
   * When the exchange closed the market because its set time had come, how that is put ("at its set time"); then no
   * close sent from here closed it. Null otherwise.
   */
  scheduled: string | null;
  /** Set once the exchange has said the close is unfinished: how many orders still rested, if it said. */
  stillResting: number | null | undefined;
  /** The key has been sent before, so what is on its way asks for an answer rather than closing. */
  askedBefore: boolean;
  onRetry: () => void;
}) {
  switch (outcome.kind) {
    case 'idle':
      return null;
    case 'sending':
      // Asked again for a market that is closed already: nothing is being closed, and after an unfinished close
      // nothing is being cancelled either until the exchange is restarted.
      return stillResting !== undefined || (askedBefore && state === 'CLOSED') ? (
        <Waiting action="Asking for the close's answer" since={outcome.since} />
      ) : (
        <>
          <Waiting action="Closing the market" since={outcome.since} />
          <p className="muted">
            Every resting order is being cancelled, which can take up to about half a minute on a full market.
          </p>
        </>
      );
    case 'done':
      return (
        <p role="status" className="outcome outcome-ok">
          {closedText(outcome.result)}
        </p>
      );
    case 'refused':
      return (
        <p role="alert" className="outcome outcome-rejected">
          {closeRefusal(outcome, scheduled)}
        </p>
      );
    case 'unresolved': {
      const code = outcome.problem?.code;
      if (stillResting !== undefined) {
        return (
          <div role="alert" className="outcome outcome-pending">
            <p>{unfinishedText(stillResting)} Nothing needs to be sent again.</p>
            <p>After the restart, the close&apos;s answer says how many orders it cancelled and how many runs it ended.</p>
            <button type="button" className="quiet" onClick={onRetry}>
              Ask for the close&apos;s answer
            </button>
          </div>
        );
      }
      if (state === 'CLOSED' && scheduled !== null) {
        // Only one close applies, and the status says it was the exchange's own for the set time: this one, whatever
        // became of it, closed nothing, and nothing is left to ask it.
        return (
          <p role="status" className="outcome outcome-ok">
            The market closed {scheduled}, so this close changed nothing.
          </p>
        );
      }
      if (state === 'CLOSED') {
        // Whatever was answered, or nothing at all: the status settles whether it closed. What is still open is
        // what the close did, and whether it has finished.
        return (
          <div role="alert" className="outcome outcome-pending">
            <p>
              The market is closed, but the close has not given its answer here, so how many orders it cancelled and
              how many runs it ended is not known yet. Until it answers, some orders may still rest: it may still be
              cancelling them, or the exchange may have stopped, and then cancels them when it is restarted.
            </p>
            <p>Asking sends the same key, so nothing is closed twice.</p>
            <button type="button" className="quiet" onClick={onRetry}>
              Ask for the close&apos;s answer
            </button>
          </div>
        );
      }
      if (code === 'COMMAND_OUTCOME_UNKNOWN') {
        return (
          <div role="alert" className="outcome outcome-pending">
            <p>
              The close is taking longer than the exchange waits before it answers, so its outcome is not known yet. It
              goes on by itself. Asking again sends the same key, so the market can never be closed twice.
            </p>
            <button type="button" className="quiet" onClick={onRetry}>
              Ask again
            </button>
          </div>
        );
      }
      if (code === 'TRADING_UNAVAILABLE') {
        return (
          <div role="alert" className="outcome outcome-pending">
            <p>
              Trading is unavailable: the exchange has stopped taking commands, so whether the market closed is not
              known here. Once the exchange has been restarted, asking again sends the same key and returns the
              close&apos;s answer.
            </p>
            <button type="button" className="quiet" onClick={onRetry}>
              Ask again
            </button>
          </div>
        );
      }
      return <CommandStatus outcome={outcome} action="Closing the market" done={() => ''} onRetry={onRetry} />;
    }
  }
}

/**
 * What became of a transition. One with no answer that the state now shows went through is said as such, and one
 * refused only because an earlier one already did it is said that way too.
 */
function TransitionStatus({
  command,
  state,
}: {
  command: ReturnType<typeof useOperatorCommand<Sent, Done>>;
  state: MarketStatus['state'];
}) {
  const { outcome, args } = command;
  if (args !== undefined && command.settled) {
    return (
      <p role="status" className="outcome outcome-ok">
        {WENT_THROUGH[args.action]}
      </p>
    );
  }
  if (args !== undefined && outcome.kind === 'refused') {
    return (
      <p role="alert" className="outcome outcome-rejected">
        {transitionRefusal(outcome, args.action, state, command.after?.action)}
      </p>
    );
  }
  return (
    <CommandStatus
      outcome={outcome}
      action={args === undefined ? 'Sending' : SENDING[args.action]}
      done={doneText}
      onRetry={() => command.retry()}
    />
  );
}

/**
 * The trading phase and its transitions, each enabled only when the exchange would allow it. One command at a time:
 * while one is on its way, or its outcome is not known, none of the others is offered. Closing asks first, since it
 * cannot be undone. A closed market has no transition left, so none is shown.
 */
export function MarketControl({
  market,
  instruments,
  read = 'fresh',
}: {
  market: MarketStatus;
  instruments: number | undefined;
  /** Whether the market has been read since the page opened: nothing is offered from a state older than the page. */
  read?: Freshness;
}) {
  const client = useQueryClient();
  // The newest bot job, while the market is in setup: an opening waits for one that is running.
  const settingUp = market.state === 'SETUP' || market.state === 'NOT_SET_UP';
  const job = useBotJob(settingUp);
  const botJob = settingUp ? job.data : undefined;
  const send = useCallback(
    async ({ action, durationMinutes }: Sent, key: string): Promise<Done> => {
      try {
        return { action, transition: await api.controlMarket(action, key, durationMinutes) };
      } finally {
        void client.invalidateQueries({ queryKey: ['market'] });
        // An opening refused for a job that is running shows that job's progress.
        void client.invalidateQueries({ queryKey: ['bot-job'] });
      }
    },
    [client],
  );
  // Kept outside this panel, as the close is, so leaving the page and coming back finds it as it was, key and all.
  const command = useOperatorCommand<Sent, Done>('market', market.simulationId ?? '', send, {
    reached: ({ action }) => read === 'fresh' && transitionReached(action, market.state),
  });
  const close = useCloseCommand(market.simulationId);
  const closing = close.outcome.kind !== 'idle';
  const busy = command.outcome.kind === 'sending' || close.outcome.kind === 'sending';
  const unresolved = command.unresolved || close.outcome.kind === 'unresolved';
  const [confirming, setConfirming] = useState(false);
  const [length, setLength] = useState<MarketLength>({ timed: false, hours: '1', minutes: '0' });
  const duration = openDuration(length);
  const lengthProblem = 'problem' in duration ? duration.problem : null;
  const closeButton = useRef<HTMLButtonElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const run = (action: Transition) => {
    // Its answer takes the place of a close that was refused.
    close.forget();
    // Only an opening has a length; one that cannot be sent keeps its button disabled.
    const minutes = action === 'open' && 'minutes' in duration ? duration.minutes : undefined;
    command.run(minutes === undefined ? { action } : { action, durationMinutes: minutes });
  };

  // Not closing puts focus back where it was. A browser does that when a dialog closes; this keeps it so wherever
  // it does not.
  const kept = useRef(false);
  useEffect(() => {
    if (!confirming && kept.current) {
      kept.current = false;
      closeButton.current?.focus();
    }
  }, [confirming]);
  // Sending disables the button the close was started from, and focus is lost to the page with it. Once the close is
  // answered, focus goes to this panel's heading, beside the answer, unless the user has moved it meanwhile. Only for
  // a close this panel saw on its way: one found answered on coming back to the page takes no focus.
  const closeKind = close.outcome.kind;
  const sawSending = useRef(false);
  useEffect(() => {
    if (closeKind === 'sending') {
      sawSending.current = true;
      return;
    }
    const lost = document.activeElement === null || document.activeElement === document.body;
    if (sawSending.current && closeKind !== 'idle') {
      sawSending.current = false;
      if (lost) {
        heading.current?.focus();
      }
    }
  }, [closeKind]);

  const closed = market.state === 'CLOSED';
  // A closed market may be read days later, so its times carry their dates.
  const when = closed ? utcDateTime : utcTime;
  const scheduled = closed ? scheduledCloseWords(market) : null;

  return (
    <section className="panel market-control" aria-labelledby="market-title">
      <div className="panel-header">
        <div className="heading-row">
          <h2 id="market-title" ref={heading} tabIndex={-1}>
            Market
          </h2>
          <TermTip terms={closed ? ['closed'] : ['halted', 'closed']} />
        </div>
        <span className={`state-badge state-${market.state.toLowerCase()}`} role="status">
          {STATE_LABELS[market.state]}
        </span>
      </div>
      <dl className="balances">
        <dt>Name</dt>
        <dd>{marketName(market)}</dd>
        <dt>Opened</dt>
        <dd>{market.openedAt === null ? 'Not yet' : when(market.openedAt)}</dd>
        {typeof market.closesAt === 'string' && (
          <>
            {/* Kept once closed: the time it was set to close, beside the time it did. */}
            <dt>{closed ? 'Set time' : 'Closes'}</dt>
            <dd>{utcDateTime(market.closesAt)}</dd>
          </>
        )}
        {market.closedAt !== null && (
          <>
            <dt>Closed</dt>
            <dd>
              {when(market.closedAt)}
              {scheduled !== null && ` · ${scheduled}`}
            </dd>
          </>
        )}
        <dt>Last command</dt>
        <dd className="num">{market.commandSequence ?? '—'}</dd>
      </dl>
      <CloseTime market={market} className="close-time" />
      {market.state === 'SETUP' && (
        <MarketLengthChoice length={length} onChange={setLength} problem={lengthProblem} disabled={busy || unresolved} />
      )}
      {closed ? (
        <p className="muted">
          {scheduled !== null ? `The market closed ${scheduled}. ` : ''}Closing is final: this market cannot
          be opened, halted or resumed again.
        </p>
      ) : (
        <ul className="control-list">
          {ACTIONS.map(({ action, label }) => {
            const reason = blocked(action, market.state, instruments, botJob, read);
            // Why it can't be used now; for the close, while it can, what it is: the one transition with no way back.
            const note = reason ?? (action === 'close' ? 'Ends this market for good. Asks first.' : null);
            const hint = `${action}-hint`;
            return (
              <li key={action}>
                <button
                  ref={action === 'close' ? closeButton : undefined}
                  type="button"
                  className={action === 'halt' || action === 'close' ? 'primary danger' : 'primary'}
                  disabled={reason !== null || busy || unresolved || (action === 'open' && lengthProblem !== null)}
                  aria-describedby={note === null ? undefined : hint}
                  aria-haspopup={action === 'close' ? 'dialog' : undefined}
                  onClick={() => (action === 'close' ? setConfirming(true) : run(action))}
                >
                  {label}
                </button>
                {note !== null && (
                  <span id={hint} className="muted">
                    {note}
                  </span>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {/* Beside the buttons that send them, not at the foot of the panel. */}
      {closing ? (
        <CloseStatus
          outcome={close.outcome}
          state={market.state}
          scheduled={scheduledCloseWords(market)}
          stillResting={close.stillResting}
          askedBefore={close.askedBefore}
          onRetry={close.run}
        />
      ) : (
        <TransitionStatus command={command} state={market.state} />
      )}
      {market.state === 'SETUP' && (
        <p className="muted hint">Opening freezes the instruments and every account&apos;s endowment.</p>
      )}
      {confirming && (
        <CloseMarketDialog
          onKeep={() => {
            kept.current = true;
            setConfirming(false);
          }}
          onClose={() => {
            setConfirming(false);
            close.run();
          }}
        />
      )}
    </section>
  );
}
