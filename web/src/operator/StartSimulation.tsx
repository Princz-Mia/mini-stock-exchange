import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, type RefObject, useCallback, useEffect, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { MarketStatus, SimulationStarted } from '../api/types';
import { TermTip } from '../help/TermTip';
import { CommandStatus, Waiting } from './CommandStatus';
import { type Freshness, notReadYet } from './freshRead';
import { useOperatorCommand } from './operatorCommands';
import { refusedFields, useFocusOnRefusal } from './refusals';
import type { CommandOutcome } from './useCommand';

export interface StartCommand {
  outcome: CommandOutcome<SimulationStarted>;
  /** The simulation on screen when the start was sent: null for none, undefined before one was sent. */
  sentFrom: string | null | undefined;
  /**
   * A new start, unless one is unresolved: then that one is sent again, unchanged. An unresolved start sent from the
   * page of another market is let go of instead, and the new one sent under a new key.
   */
  run: (name: string, from: string | null) => void;
  retry: () => void;
  /**
   * True the first time it is asked about this answer, and then only once what took focus to it has left the page:
   * whichever panel shows an answer first takes focus to it, and another takes it only from one drawn anew or gone.
   */
  claimFocus: (answer: CommandOutcome<SimulationStarted>, by: Element | null) => boolean;
}

interface StartArgs {
  name: string;
  from: string | null;
}

/**
 * The start of a new simulation, kept beside the cached queries rather than by the panel it was sent from: that panel
 * goes with the closed market's page as soon as the new simulation is on screen, and the start's answer is said on
 * the new one. Its key is kept until the exchange gives a final answer, as for every operator command, and through a
 * change of page, but not once the page has shown another simulation: at most one simulation is not closed, so a
 * start sent again under a new key after one has gone through is refused, and two can never start. It belongs to no
 * one market, so it is kept for the operator rather than for the simulation on screen.
 */
export function useStartCommand(): StartCommand {
  const client = useQueryClient();
  const send = useCallback(
    async ({ name, from }: StartArgs, key: string) => {
      try {
        const started = await api.startSimulation({ name }, key);
        // The page becomes the new simulation's at once, not at the status's next read. A read already on its way
        // could only bring the closed market back. A page that has shown another simulation since the start was sent
        // is left as it is: its status is newer than an answer given again for an earlier start.
        await client.cancelQueries({ queryKey: ['market'] });
        if (client.getQueryData<MarketStatus>(['market'])?.simulationId === from) {
          client.setQueryData<MarketStatus>(['market'], {
            state: started.state,
            simulationId: started.simulationId,
            name: started.name,
            openedAt: null,
            closesAt: null,
            closedAt: null,
            closedOnSchedule: false,
            commandSequence: started.commandSequence,
          });
        }
        return started;
      } finally {
        void client.invalidateQueries({ queryKey: ['market'] });
      }
    },
    [client],
  );
  const command = useOperatorCommand<StartArgs, SimulationStarted>('simulation-start', 'operator', send);
  const { outcome, run: runCommand, retry: retryCommand, forget } = command;
  const sentFrom = command.args?.from;
  const run = useCallback(
    (name: string, from: string | null) => {
      // The page has shown another simulation since that start was sent, so it is settled: it is the simulation that
      // started, or it was refused for it. Sent again, it would start nothing, or something under the name typed then.
      if (outcome.kind === 'unresolved' && from !== sentFrom) {
        forget();
      }
      runCommand({ name, from });
    },
    [outcome.kind, sentFrom, forget, runCommand],
  );
  const retry = useCallback(() => retryCommand(), [retryCommand]);
  const claimed = useRef<{ answer: CommandOutcome<SimulationStarted>; by: Element | null } | null>(null);
  const claimFocus = useCallback((answer: CommandOutcome<SimulationStarted>, by: Element | null) => {
    const last = claimed.current;
    if (last !== null && last.answer === answer && last.by?.isConnected === true) {
      return false;
    }
    claimed.current = { answer, by };
    return true;
  }, []);
  return { outcome, sentFrom, run, retry, claimFocus };
}

/** Focus was lost to the page, as when what had it was disabled or taken away; focus the user moved stays. */
function focusLost(): boolean {
  return document.activeElement === null || document.activeElement === document.body;
}

/** Once an answer leaves nothing to do but ask again, focus goes to that button, unless another panel took it there. */
function useFocusOnAskAgain(
  start: StartCommand,
  outcome: CommandOutcome<SimulationStarted>,
  status: RefObject<HTMLElement | null>,
) {
  const { claimFocus } = start;
  useEffect(() => {
    if (outcome.kind === 'unresolved' && claimFocus(outcome, status.current) && focusLost()) {
      status.current?.querySelector('button')?.focus();
    }
  }, [outcome, claimFocus, status]);
}

const IDLE: CommandOutcome<SimulationStarted> = { kind: 'idle' };

const NOT_CLOSED: Record<string, string> = {
  SETUP:
    'Nothing was started: a simulation is already being set up. This page now shows it; set that one up instead.',
  OPEN: 'Nothing was started: a simulation that has not closed is open. This page now shows it; close it before starting another.',
  HALTED:
    'Nothing was started: a simulation that has not closed is halted. This page now shows it; close it before starting another.',
};

/** A refused start, in words: what was refused, and what to do instead. A refused key is spent either way. */
export function startRefusal(outcome: Extract<CommandOutcome<SimulationStarted>, { kind: 'refused' }>): string {
  switch (outcome.code) {
    case 'SIMULATION_NOT_CLOSED':
      return (
        NOT_CLOSED[outcome.problem?.state ?? ''] ??
        'Nothing was started: a simulation that has not closed exists. This page now shows it.'
      );
    case 'SIMULATION_NAME_TAKEN':
      return 'Nothing was started: another simulation already has this name, whatever its case. Choose another name.';
    default:
      return outcome.message;
  }
}

/**
 * What became of a start. Here is the panel it was sent from; elsewhere is the page of a simulation that has started
 * since, where it is said whether that one is the start sent from here.
 */
function StartStatus({
  outcome,
  elsewhere,
  settingUp = false,
  onRetry,
}: {
  outcome: CommandOutcome<SimulationStarted>;
  elsewhere: boolean;
  /** The simulation on screen is still being set up. */
  settingUp?: boolean;
  onRetry: () => void;
}) {
  switch (outcome.kind) {
    case 'idle':
      return null;
    case 'sending':
      return (
        <Waiting
          action={elsewhere ? "Asking for the start's answer" : 'Starting the simulation'}
          since={outcome.since}
        />
      );
    case 'done':
      return (
        <p role="status" className="outcome outcome-ok">
          {!elsewhere
            ? `Started ${outcome.result.name}.`
            : settingUp
              ? `${outcome.result.name} has started, in setup. Nothing was copied from the market before: add its instruments, fund the traders again and create its bots. Trader logins are kept.`
              : `The start sent from here started this simulation, ${outcome.result.name}.`}
        </p>
      );
    case 'refused':
      return (
        <p role="alert" className="outcome outcome-rejected">
          {startRefusal(outcome)}
        </p>
      );
    case 'unresolved': {
      const code = outcome.problem?.code;
      const said = elsewhere
        ? "A new simulation has started, but the start sent from here has not given its answer, so whether it is this one is not known. Asking again sends the same key and name: it returns that start's answer and never starts another."
        : code === 'COMMAND_OUTCOME_UNKNOWN'
          ? 'The start did not answer in time, so whether the simulation started is not known yet. Asking again sends the same key and name, so it can never start twice.'
          : code === 'TRADING_UNAVAILABLE'
            ? "Trading is unavailable: the exchange has stopped taking commands, so whether the simulation started is not known here. Once the exchange has been restarted, asking again sends the same key and returns the start's answer."
            : null;
      if (said === null) {
        return <CommandStatus outcome={outcome} action="Starting the simulation" done={() => ''} onRetry={onRetry} />;
      }
      return (
        <div role="alert" className="outcome outcome-pending">
          <p>{said}</p>
          <button type="button" className="quiet" onClick={onRetry}>
            Ask again
          </button>
        </div>
      );
    }
  }
}

/**
 * On the page of a market that has closed, and on a stack with no market at all: a name and one button, which start a
 * new simulation in setup. The exchange checks the name, and a refusal marks the field, as on the page's other forms.
 */
export function StartSimulation({
  start,
  market,
  read = 'fresh',
}: {
  start: StartCommand;
  market: MarketStatus;
  /** Whether the market has been read since the page opened: a start is offered only from a state that recent. */
  read?: Freshness;
}) {
  const first = market.simulationId === null;
  const title = first ? 'Start the first simulation' : 'Start a new simulation';
  const [name, setName] = useState('');
  const form = useRef<HTMLFormElement>(null);
  const hintId = useId();
  const outcomeId = useId();
  const unreadId = useId();
  const unread = notReadYet(read);
  // Only what was sent from this market's page: a start sent from another is not this one's to answer.
  const outcome: CommandOutcome<SimulationStarted> = start.sentFrom === market.simulationId ? start.outcome : IDLE;
  const invalid =
    refusedFields(outcome).has('name') || (outcome.kind === 'refused' && outcome.code === 'SIMULATION_NAME_TAKEN');
  useFocusOnRefusal(outcome.kind, form);
  const status = useRef<HTMLDivElement>(null);
  useFocusOnAskAgain(start, outcome, status);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    start.run(name.trim(), market.simulationId);
  };

  return (
    <section className="panel" aria-labelledby="start-title">
      <div className="heading-row">
        <h2 id="start-title">{title}</h2>
        <TermTip terms={['simulation']} />
      </div>
      {first ? (
        <p>
          A market by name, in setup. Or add an instrument below: that creates a market too, with no name.
        </p>
      ) : (
        <>
          <p>
            A fresh market, in setup, with its own instruments, accounts and bots to set up again: nothing is copied
            from this one. Trader logins stay.
          </p>
          <p className="muted">This market and its history are kept, but these screens then show the new one.</p>
        </>
      )}
      <form ref={form} onSubmit={submit} className="setup-form" aria-label={title}>
        {/* One start at a time: also while one sent from another market's page is on its way. */}
        <fieldset disabled={start.outcome.kind === 'sending' || outcome.kind === 'unresolved'}>
          <div className="field-row">
            <label>
              Name
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                autoComplete="off"
                placeholder="e.g. Thursday seminar"
                aria-invalid={invalid || undefined}
                aria-describedby={[hintId, invalid ? outcomeId : undefined].filter(Boolean).join(' ')}
              />
            </label>
          </div>
          <p id={hintId} className="muted hint">
            1 to 80 characters, different from every earlier simulation&apos;s name. Traders see it at the top of their
            screens.
          </p>
          {unread !== null && (
            <p id={unreadId} className="muted">
              {unread}
            </p>
          )}
          <button
            type="submit"
            className="primary"
            disabled={unread !== null}
            aria-describedby={unread === null ? undefined : unreadId}
          >
            Start simulation
          </button>
        </fieldset>
      </form>
      <div id={outcomeId} ref={status}>
        <StartStatus outcome={outcome} elsewhere={false} onRetry={start.retry} />
      </div>
    </section>
  );
}

/**
 * At the top of the page of a simulation that started while this page was on the market before: whether it is the
 * start sent from here, and what is to be set up again. Also when that start was refused because a simulation that has
 * not closed exists, or has no answer yet: on that simulation's page, whatever its state.
 */
export function StartNotice({ start, market }: { start: StartCommand; market: MarketStatus }) {
  const { outcome, claimFocus } = start;
  const heading = useRef<HTMLHeadingElement>(null);
  const status = useRef<HTMLDivElement>(null);
  const statusId = useId();
  const shown =
    market.state !== 'CLOSED' &&
    start.sentFrom !== undefined &&
    start.sentFrom !== market.simulationId &&
    (outcome.kind === 'done'
      ? outcome.result.simulationId === market.simulationId
      : outcome.kind === 'refused'
        ? outcome.code === 'SIMULATION_NOT_CLOSED'
        : outcome.kind !== 'idle');
  // The page was drawn anew for this simulation, so focus was lost with the button that sent the start: it goes to
  // the answer, which the heading reads out with it.
  useEffect(() => {
    if (shown && (outcome.kind === 'done' || outcome.kind === 'refused') && claimFocus(outcome, heading.current) && focusLost()) {
      heading.current?.focus();
    }
  }, [shown, outcome, claimFocus]);
  useFocusOnAskAgain(start, shown ? outcome : IDLE, status);
  if (!shown) {
    return null;
  }
  return (
    <section className="panel" aria-labelledby="started-title">
      <h2 id="started-title" ref={heading} tabIndex={-1} aria-describedby={statusId}>
        A new simulation
      </h2>
      <div id={statusId} ref={status}>
        <StartStatus outcome={outcome} elsewhere settingUp={market.state === 'SETUP'} onRetry={start.retry} />
      </div>
    </section>
  );
}
