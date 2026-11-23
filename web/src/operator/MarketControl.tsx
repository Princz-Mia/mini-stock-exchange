import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';

import { ApiError, api } from '../api/client';
import type { MarketClosed, MarketStatus, MarketTransition } from '../api/types';
import { groupInteger, utcDateTime, utcTime } from '../format';
import { TermTip } from '../help/TermTip';
import { CloseMarketDialog } from './CloseMarketDialog';
import { CommandStatus } from './CommandStatus';
import { type CommandOutcome, useCommand } from './useCommand';

type Action = 'open' | 'halt' | 'resume' | 'close';

type Done =
  | { action: Exclude<Action, 'close'>; transition: MarketTransition }
  | { action: 'close'; closed: MarketClosed };

const STATE_LABELS: Record<MarketStatus['state'], string> = {
  NOT_SET_UP: 'Not set up',
  SETUP: 'Setup',
  OPEN: 'Open',
  HALTED: 'Halted',
  CLOSED: 'Closed',
};

const DONE: Record<Exclude<Action, 'close'>, string> = {
  open: 'The market is open.',
  halt: 'Trading is halted. Cancels still work.',
  resume: 'Trading has resumed.',
};

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
 * been read; until then a market in setup is not said to have none.
 */
function blocked(action: Action, state: MarketStatus['state'], instruments: number | undefined): string | null {
  switch (action) {
    case 'open':
      if (state === 'NOT_SET_UP') return 'Add an instrument first; that creates the market.';
      if (state !== 'SETUP') return 'The market has already opened. It opens only once.';
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
 * A refused close, in words. The exchange says which state the market was in: a close that found it closed already
 * changed nothing, which is not a failure to report as one.
 */
function closeRefusal(outcome: Extract<CommandOutcome<Done>, { kind: 'refused' }>): string {
  if (outcome.code !== 'MARKET_TRANSITION_NOT_ALLOWED') {
    return outcome.message;
  }
  switch (outcome.problem?.state) {
    case 'CLOSED':
      return 'The market had already closed, so this close changed nothing.';
    case 'SETUP':
      return 'The market has not opened yet, and only a market that has opened can be closed. Nothing was changed.';
    default:
      return outcome.message;
  }
}

/**
 * What became of a close, which has more answers than the other transitions: it can take long, and it can stop half
 * way with the market already closed. Each is said as what it is, and the same key is sent again only where asking
 * again can bring something new.
 */
function CloseStatus({
  outcome,
  stillResting,
  onRetry,
}: {
  outcome: CommandOutcome<Done>;
  /** Set once the exchange has said the close is unfinished: how many orders still rested, if it said. */
  stillResting: number | null | undefined;
  onRetry: () => void;
}) {
  switch (outcome.kind) {
    case 'idle':
      return null;
    case 'sending':
      return (
        <p role="status" className="outcome outcome-pending">
          Closing the market… Every resting order is being cancelled, which can take up to about half a minute on a
          full market.
        </p>
      );
    case 'done':
      return (
        <p role="status" className="outcome outcome-ok">
          {outcome.result.action === 'close' ? closedText(outcome.result.closed) : DONE[outcome.result.action]}
        </p>
      );
    case 'refused':
      return (
        <p role="alert" className="outcome outcome-rejected">
          {closeRefusal(outcome)}
        </p>
      );
    case 'unresolved': {
      const code = outcome.problem?.code;
      if (stillResting !== undefined) {
        return (
          <div role="alert" className="outcome outcome-pending">
            <p>
              The market is closed, but{' '}
              {stillResting === null
                ? 'some orders still rest'
                : `${groupInteger(stillResting)} ${stillResting === 1 ? 'order still rests' : 'orders still rest'}`}
              . The exchange has stopped trading and cancels them when it is restarted. Nothing needs to be sent again.
            </p>
            <p>After the restart, the close&apos;s answer says how many orders it cancelled and how many runs it ended.</p>
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
      return <CommandStatus outcome={outcome} done={() => ''} onRetry={onRetry} />;
    }
  }
}

/**
 * The trading phase and its transitions, each enabled only when the exchange would allow it. One command at a time:
 * while one is on its way, or its outcome is not known, none of the others is offered. Closing asks first, since it
 * cannot be undone. A closed market has no transition left, so none is shown.
 */
export function MarketControl({ market, instruments }: { market: MarketStatus; instruments: number | undefined }) {
  const client = useQueryClient();
  // Unset until the exchange says a close is unfinished; from then on the market is known to be closed, whatever a
  // later answer under the same key says while the exchange is still stopped.
  const [stillResting, setStillResting] = useState<number | null | undefined>(undefined);
  const send = useCallback(
    async (action: Action, key: string): Promise<Done> => {
      try {
        if (action === 'close') {
          const closed = await api.closeMarket(key);
          setStillResting(undefined);
          return { action, closed };
        }
        return { action, transition: await api.controlMarket(action, key) };
      } catch (error) {
        if (action === 'close' && error instanceof ApiError && error.code === 'MARKET_CLOSE_UNFINISHED') {
          setStillResting(error.problem?.restingOrders ?? null);
        }
        throw error;
      } finally {
        void client.invalidateQueries({ queryKey: ['market'] });
      }
    },
    [client],
  );
  const command = useCommand(send);
  const busy = command.outcome.kind === 'sending';
  const [last, setLast] = useState<Action | null>(null);
  const [confirming, setConfirming] = useState(false);
  const closeButton = useRef<HTMLButtonElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const run = (action: Action) => {
    setLast(action);
    void command.run(action);
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
  // answered, focus goes to this panel's heading, beside the answer, unless the user has moved it meanwhile.
  const answered = last === 'close' && command.outcome.kind !== 'idle' && command.outcome.kind !== 'sending';
  useEffect(() => {
    const lost = document.activeElement === null || document.activeElement === document.body;
    if (answered && lost) {
      heading.current?.focus();
    }
  }, [answered]);

  const closed = market.state === 'CLOSED';
  // A closed market may be read days later, so its times carry their dates.
  const when = closed ? utcDateTime : utcTime;

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
        <dt>Opened</dt>
        <dd>{market.openedAt === null ? 'Not yet' : when(market.openedAt)}</dd>
        {market.closedAt !== null && (
          <>
            <dt>Closed</dt>
            <dd>{when(market.closedAt)}</dd>
          </>
        )}
        <dt>Last command</dt>
        <dd className="num">{market.commandSequence ?? '—'}</dd>
      </dl>
      {closed ? (
        <p className="muted">Closing is final: this market cannot be opened, halted or resumed again.</p>
      ) : (
        <ul className="control-list">
          {ACTIONS.map(({ action, label }) => {
            const reason = blocked(action, market.state, instruments);
            // Why it can't be used now; for the close, while it can, what it is: the one transition with no way back.
            const note = reason ?? (action === 'close' ? 'Ends this market for good. Asks first.' : null);
            const hint = `${action}-hint`;
            return (
              <li key={action}>
                <button
                  ref={action === 'close' ? closeButton : undefined}
                  type="button"
                  className={action === 'halt' || action === 'close' ? 'primary danger' : 'primary'}
                  disabled={reason !== null || busy || command.unresolved}
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
            run('close');
          }}
        />
      )}
      {last === 'close' ? (
        <CloseStatus outcome={command.outcome} stillResting={stillResting} onRetry={() => void command.retry()} />
      ) : (
        <CommandStatus
          outcome={command.outcome}
          done={(result) => (result.action === 'close' ? closedText(result.closed) : DONE[result.action])}
          onRetry={() => void command.retry()}
        />
      )}
    </section>
  );
}
