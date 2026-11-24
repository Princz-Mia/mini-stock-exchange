import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';

import { api } from '../api/client';
import type { MarketClosed, MarketStatus, MarketTransition } from '../api/types';
import { groupInteger, utcDateTime, utcTime } from '../format';
import { TermTip } from '../help/TermTip';
import { useCloseCommand } from './closeCommand';
import { CloseMarketDialog } from './CloseMarketDialog';
import { CommandStatus } from './CommandStatus';
import { type CommandOutcome, useCommand } from './useCommand';

type Transition = 'open' | 'halt' | 'resume';
type Action = Transition | 'close';

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
function closeRefusal(outcome: Extract<CommandOutcome<MarketClosed>, { kind: 'refused' }>): string {
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
 * again can bring something new. The market's own state is part of it: once the status says CLOSED, a close with no
 * answer is not said to be unknown, whatever the exchange answered or failed to.
 */
function CloseStatus({
  outcome,
  state,
  stillResting,
  askedBefore,
  onRetry,
}: {
  outcome: CommandOutcome<MarketClosed>;
  state: MarketStatus['state'];
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
        <p role="status" className="outcome outcome-pending">
          Asking for the close&apos;s answer…
        </p>
      ) : (
        <p role="status" className="outcome outcome-pending">
          Closing the market… Every resting order is being cancelled, which can take up to about half a minute on a
          full market.
        </p>
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
          {closeRefusal(outcome)}
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
  const send = useCallback(
    async (action: Transition, key: string): Promise<Done> => {
      try {
        return { action, transition: await api.controlMarket(action, key) };
      } finally {
        void client.invalidateQueries({ queryKey: ['market'] });
      }
    },
    [client],
  );
  const command = useCommand(send);
  // The close is kept outside this panel, so leaving the page and coming back finds it as it was, key and all.
  const close = useCloseCommand(market.simulationId);
  const closing = close.outcome.kind !== 'idle';
  const busy = command.outcome.kind === 'sending' || close.outcome.kind === 'sending';
  const unresolved = command.unresolved || close.outcome.kind === 'unresolved';
  const [confirming, setConfirming] = useState(false);
  const closeButton = useRef<HTMLButtonElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const run = (action: Transition) => {
    // Its answer takes the place of a close that was refused.
    close.forget();
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
                  disabled={reason !== null || busy || unresolved}
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
            close.run();
          }}
        />
      )}
      {closing ? (
        <CloseStatus
          outcome={close.outcome}
          state={market.state}
          stillResting={close.stillResting}
          askedBefore={close.askedBefore}
          onRetry={close.run}
        />
      ) : (
        <CommandStatus outcome={command.outcome} done={(result) => DONE[result.action]} onRetry={() => void command.retry()} />
      )}
    </section>
  );
}
