import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { MarketStatus, SimulationStarted } from '../api/types';
import { TermTip } from '../help/TermTip';
import { CommandStatus } from './CommandStatus';
import { refusedFields, useFocusOnRefusal } from './refusals';
import { type CommandOutcome, useCommand } from './useCommand';

export interface StartCommand {
  outcome: CommandOutcome<SimulationStarted>;
  /** The simulation on screen when the start was sent: null for none, undefined before one was sent. */
  sentFrom: string | null | undefined;
  /** A new start, unless one is unresolved: then that one is sent again, unchanged. */
  run: (name: string, from: string | null) => void;
  retry: () => void;
}

/**
 * The start of a new simulation, kept by the operator page rather than by the panel it was sent from: that panel goes
 * with the closed market's page as soon as the new simulation is on screen, and the start's answer is said on the new
 * one. Its key is kept until the exchange gives a final answer, as for every operator command, but not through a
 * change of page, as a close's is: at most one simulation is not closed, so a start sent again under a new key after
 * one has gone through is refused, and two can never start.
 */
export function useStartCommand(): StartCommand {
  const client = useQueryClient();
  const send = useCallback(
    async (name: string, key: string) => {
      try {
        const started = await api.startSimulation({ name }, key);
        // The page becomes the new simulation's at once, not at the status's next read. A read already on its way
        // could only bring the closed market back.
        await client.cancelQueries({ queryKey: ['market'] });
        client.setQueryData<MarketStatus>(['market'], {
          state: started.state,
          simulationId: started.simulationId,
          name: started.name,
          openedAt: null,
          closedAt: null,
          commandSequence: started.commandSequence,
        });
        return started;
      } finally {
        void client.invalidateQueries({ queryKey: ['market'] });
      }
    },
    [client],
  );
  const command = useCommand(send);
  const [sentFrom, setSentFrom] = useState<string | null | undefined>(undefined);
  const { run: runCommand, retry: retryCommand } = command;
  const run = useCallback(
    (name: string, from: string | null) => {
      if (command.outcome.kind !== 'unresolved' && command.outcome.kind !== 'sending') {
        setSentFrom(from);
      }
      void runCommand(name);
    },
    [command.outcome.kind, runCommand],
  );
  const retry = useCallback(() => void retryCommand(), [retryCommand]);
  return { outcome: command.outcome, sentFrom, run, retry };
}

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
  onRetry,
}: {
  outcome: CommandOutcome<SimulationStarted>;
  elsewhere: boolean;
  onRetry: () => void;
}) {
  switch (outcome.kind) {
    case 'idle':
      return null;
    case 'sending':
      return (
        <p role="status" className="outcome outcome-pending">
          {elsewhere ? "Asking for the start's answer…" : 'Starting the simulation…'}
        </p>
      );
    case 'done':
      return (
        <p role="status" className="outcome outcome-ok">
          {elsewhere
            ? `${outcome.result.name} has started, in setup. Nothing was copied from the market before: add its instruments, fund the traders again and create its bots. Trader logins are kept.`
            : `Started ${outcome.result.name}.`}
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
        return <CommandStatus outcome={outcome} done={() => ''} onRetry={onRetry} />;
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
export function StartSimulation({ start, market }: { start: StartCommand; market: MarketStatus }) {
  const first = market.simulationId === null;
  const title = first ? 'Start the first simulation' : 'Start a new simulation';
  const [name, setName] = useState('');
  const form = useRef<HTMLFormElement>(null);
  const hintId = useId();
  const outcomeId = useId();
  // Only what was sent from this market's page: a start sent from another is not this one's to answer.
  const outcome: CommandOutcome<SimulationStarted> = start.sentFrom === market.simulationId ? start.outcome : { kind: 'idle' };
  const invalid =
    refusedFields(outcome).has('name') || (outcome.kind === 'refused' && outcome.code === 'SIMULATION_NAME_TAKEN');
  useFocusOnRefusal(outcome.kind, form);

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
        <fieldset disabled={outcome.kind === 'sending' || outcome.kind === 'unresolved'}>
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
          <button type="submit" className="primary">
            Start simulation
          </button>
        </fieldset>
      </form>
      <div id={outcomeId}>
        <StartStatus outcome={outcome} elsewhere={false} onRetry={start.retry} />
      </div>
    </section>
  );
}

/**
 * At the top of a simulation's setup page when it started while this page was on the market before: whether it is the
 * start sent from here, and what is to be set up again.
 */
export function StartNotice({ start, market }: { start: StartCommand; market: MarketStatus }) {
  const { outcome } = start;
  const shown =
    market.state === 'SETUP' &&
    start.sentFrom !== undefined &&
    start.sentFrom !== market.simulationId &&
    (outcome.kind === 'done'
      ? outcome.result.simulationId === market.simulationId
      : outcome.kind === 'refused'
        ? outcome.code === 'SIMULATION_NOT_CLOSED'
        : outcome.kind !== 'idle');
  if (!shown) {
    return null;
  }
  return (
    <section className="panel" aria-labelledby="started-title">
      <h2 id="started-title">A new simulation</h2>
      <StartStatus outcome={outcome} elsewhere onRetry={start.retry} />
    </section>
  );
}
