import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router';

import { pollEvery } from '../api/client';
import { useInstruments, useMarketStatus, useRefreshOnMarketChange } from '../app/market';
import { groupInteger, utcDateTime } from '../format';
import { AccountSetup } from './AccountSetup';
import { BotRuns } from './BotRuns';
import { BotSetup } from './BotSetup';
import { useCloseCommand } from './closeCommand';
import { HealthPanel, useHealthSummary } from './HealthPanel';
import { InstrumentSetup } from './InstrumentSetup';
import { MarketControl } from './MarketControl';
import type { CommandOutcome } from './useCommand';

/**
 * What this page shows of one simulation and no other. All of it is read again when the market closes, whoever closed
 * it: a close ends the runs and revokes the bots' tokens, and each of these lists would otherwise show the market as it
 * was until its own next poll, or for good where it has none. When the exchange names another simulation, all of it is
 * dropped first: none of it is the new market's.
 */
const PER_SIMULATION = [['instruments'], ['bot-runs'], ['bots'], ['accounts'], ['traders'], ['admin-health']] as const;

/**
 * The operator's screen: the authoritative trading phase with its controls and, once open, how trading is keeping
 * up; bot runs, and the SETUP editor for
 * instruments, bots and endowments. Once the market opens, bot runs come first. A market that has closed is shown as
 * ended: what it left is listed read-only, and nothing is offered that a closed market would refuse. Everything shown
 * is what the exchange committed, refetched after each command and every five seconds.
 */
export function OperatorPage() {
  const market = useMarketStatus();
  // Goes up when the exchange names another simulation. The page is drawn anew then, so that nothing typed, answered
  // or paged through in the simulation before is left on the page of the new one.
  const generation = useRefreshOnMarketChange(market.data, PER_SIMULATION);
  // The runs ticked for a comparison are in the address, and are the simulation's they were ticked in.
  const [, setParams] = useSearchParams();
  const drawn = useRef(generation);
  useEffect(() => {
    if (drawn.current !== generation) {
      drawn.current = generation;
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          next.delete('compare');
          return next;
        },
        { replace: true },
      );
    }
  }, [generation, setParams]);
  // This market's instruments only: the list is asked for by the simulation the status names.
  const instruments = useInstruments(market, { refetchInterval: pollEvery(5_000) });

  if (market.isPending) {
    return <p className="page-status">Loading the market…</p>;
  }
  // Only when there is nothing to show: a failed poll after a good read keeps the page, and every form's unconfirmed
  // command with its key and Retry, on screen with the last known state.
  if (market.data === undefined) {
    return (
      <p role="alert" className="page-status">
        The exchange is not reachable right now.
      </p>
    );
  }
  const listed = instruments.instruments;
  const editable = market.data.state === 'NOT_SET_UP' || market.data.state === 'SETUP';
  const closed = market.data.state === 'CLOSED';

  return (
    <div className="operator" key={generation}>
      <div className="operator-side">
        {market.isError && (
          <p role="alert" className="form-error">
            The exchange is not answering, so the market state shown may be out of date.
          </p>
        )}
        <MarketControl market={market.data} instruments={instruments.isPending ? undefined : listed.length} />
        {!editable && (
          <HealthPanel
            instruments={listed}
            feed={
              instruments.isError
                ? { kind: 'unreachable' }
                : instruments.feed === undefined
                  ? { kind: 'checking' }
                  : { kind: 'read', feed: instruments.feed }
            }
          />
        )}
      </div>
      <div className="operator-setup">
        {instruments.isError && (
          <p role="alert" className="form-error">
            Market data is not reachable, so the instrument list may be incomplete.
          </p>
        )}
        {closed && <Ended closedAt={market.data.closedAt} simulationId={market.data.simulationId} />}
        {!editable && <BotRuns market={market.data.state} closedAt={market.data.closedAt} instruments={listed} />}
        <InstrumentSetup instruments={listed} editable={editable} closed={closed} />
        <BotSetup instruments={listed} editable={editable} closed={closed} />
        <AccountSetup instruments={listed} editable={editable} closed={closed} />
        {editable && <BotRuns market={market.data.state} closedAt={null} instruments={listed} />}
      </div>
    </div>
  );
}

/**
 * What is known of the orders a close cancels. The market reads CLOSED from the close's first transaction, while the
 * orders are cancelled in the ones after it, or not until a restart when the close was left unfinished. So that they
 * are all cancelled is said only once the close has answered so; before that, what is known of them is said instead.
 */
export function restingOrdersText(known: {
  close: CommandOutcome<unknown>['kind'];
  /**
   * At most how many orders an unfinished close left resting, null if it did not say; undefined when it was not
   * unfinished.
   */
  stillResting: number | null | undefined;
  /** The exchange says it has stopped taking commands. */
  writerHalted: boolean;
}): string {
  if (known.stillResting !== undefined) {
    if (known.stillResting === 0) {
      return 'The close was left unfinished, though the exchange counted no order still resting when it stopped. It finishes the close when it is restarted.';
    }
    // What the exchange gives is an upper bound: its own count when it stopped, of which some may be cancelled already.
    const count =
      known.stillResting === null
        ? 'Some orders still rest, with their holds'
        : known.stillResting === 1
          ? 'At most 1 order still rests, with its hold'
          : `At most ${groupInteger(known.stillResting)} orders still rest, with their holds`;
    return `${count}: the close was left unfinished. The exchange has stopped, and cancels ${known.stillResting === 1 ? 'it' : 'them'} when it is restarted.`;
  }
  switch (known.close) {
    case 'done':
      return 'Every order that still rested was cancelled and its hold released.';
    case 'sending':
      return 'The orders that still rested are being cancelled and their holds released; until the close answers, some still rest.';
    case 'unresolved':
      return 'The close has not answered yet, so some orders may still rest with their holds.';
    default:
      return known.writerHalted
        ? 'A close cancels every order that still rested and releases its hold. The exchange has stopped taking commands: any order the close had not cancelled by then rests, with its hold, until the exchange is restarted.'
        : 'A close cancels every order that still rested and releases its hold, which on a full market takes up to about half a minute.';
  }
}

/**
 * First on the page of a market that has closed: that it has ended and when, what the close did, and what is still
 * here to read. The panels under it say the same of themselves in a line each, in place of their forms.
 */
function Ended({ closedAt, simulationId }: { closedAt: string | null; simulationId: string | null }) {
  const close = useCloseCommand(simulationId);
  const health = useHealthSummary();
  return (
    <section className="panel ended" aria-labelledby="ended-title">
      <h2 id="ended-title">This market has ended</h2>
      <p>
        It closed{closedAt === null ? '' : ` on ${utcDateTime(closedAt)}`}. Trading is over for good: every bot run
        ended, and every bot&apos;s token stopped working.
      </p>
      <p>
        {restingOrdersText({
          close: close.outcome.kind,
          stillResting: close.stillResting,
          writerHalted: health.data?.writer.available === false,
        })}
      </p>
      <p className="muted">
        Nothing was deleted. The bot runs and their comparison, the instruments, the bots and the accounts stay below
        to read, and traders keep their portfolios and their history. Nothing here can be changed any more.
      </p>
    </section>
  );
}
