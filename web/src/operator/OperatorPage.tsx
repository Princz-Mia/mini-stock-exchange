import { type QueryClient, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { isRefusal } from '../api/client';
import type { Instruments } from '../api/types';
import { closedPhrase } from '../app/closeTime';
import { lastUpdatedText } from '../app/LastUpdated';
import { OperatorSkeleton } from '../app/Skeleton';
import { useForgetInAddress, useInstruments, useMarketStatus, useRefreshOnMarketChange } from '../app/market';
import { groupInteger } from '../format';
import { AccountSetup } from './AccountSetup';
import { addedInstruments, readQuickly, WAITING_POLL_MS, waitingFor } from './addedInstruments';
import { BotRuns } from './BotRuns';
import { BotSetup } from './BotSetup';
import { useCloseCommand } from './closeCommand';
import { freshness, PageOpened } from './freshRead';
import { HealthPanel, useHealthSummary } from './HealthPanel';
import { InstrumentSetup } from './InstrumentSetup';
import { MarketControl } from './MarketControl';
import { StartNotice, StartSimulation, useStartCommand } from './StartSimulation';
import type { CommandOutcome } from './useCommand';

/**
 * What this page shows of one simulation and no other. All of it is read again when the market closes, whoever closed
 * it: a close ends the runs and revokes the bots' tokens, and each of these lists would otherwise show the market as it
 * was until its own next poll, or for good where it has none. When the exchange names another simulation, all of it is
 * dropped first: none of it is the new market's.
 */
const PER_SIMULATION = [
  ['instruments'],
  ['bot-runs'],
  ['bots'],
  ['bot-job'],
  ['accounts'],
  ['traders'],
  ['admin-health'],
] as const;

/** The runs ticked for a comparison are in the address, and are the simulation's they were ticked in. */
const TICKED = ['compare'] as const;

/** How often the setup list is read otherwise. */
export const LIST_POLL_MS = 5_000;

/**
 * How often the setup list is read: quickly while an instrument the exchange has added is not in it yet, for a
 * while, and otherwise every five seconds. Worked out again with every read, so it slows down by itself once the
 * instrument is listed or has waited long enough.
 *
 * A closed market's list is read no more once market data has caught up with it: the catalogue is frozen and nothing
 * trades, so nothing this page shows of it can change. Until then the feed it reports may still be behind, with the
 * close's own last events. A read that failed is asked again, whatever the list read before it said.
 */
export function instrumentPace(client: QueryClient, simulation: string | null, closed = false) {
  return (query: { state: { data: Instruments | undefined; error: unknown } }): number | false => {
    if (isRefusal(query.state.error)) {
      return false;
    }
    const waiting = waitingFor(addedInstruments(client), simulation, query.state.data?.instruments ?? []);
    if (readQuickly(waiting, Date.now())) {
      return WAITING_POLL_MS;
    }
    const feed = query.state.data?.feed;
    return closed && feed?.state === 'LIVE' && feed.lag === 0 && query.state.error == null ? false : LIST_POLL_MS;
  };
}

/**
 * The operator's screen: the authoritative trading phase with its controls and, once open, how trading is keeping
 * up; bot runs, and the SETUP editor for
 * instruments, bots and endowments. Once the market opens, bot runs come first. A market that has closed is shown as
 * ended: what it left is listed read-only, nothing is offered that a closed market would refuse, and a new simulation
 * can be started, as the first one can on a stack with none. Everything shown is what the exchange committed,
 * refetched after each command and every five seconds.
 */
export function OperatorPage() {
  const client = useQueryClient();
  const market = useMarketStatus();
  // Goes up when the exchange names another simulation. The page is drawn anew then, so that nothing typed, answered
  // or paged through in the simulation before is left on the page of the new one.
  const generation = useRefreshOnMarketChange(market.data, PER_SIMULATION);
  useForgetInAddress(generation, TICKED);
  // Above the page drawn for each simulation, so that the new one's page can say what became of it.
  const start = useStartCommand();
  // What is offered is offered from reads made since now: the cache may hold the market as it was before a command
  // sent from this page, before the operator looked elsewhere, went through.
  const [opened] = useState(() => Date.now());
  const marketRead = freshness(market, opened);
  // This market's instruments only: the list is asked for by the simulation the status names.
  const instruments = useInstruments(market, {
    refetchInterval: instrumentPace(client, market.data?.simulationId ?? null, market.data?.state === 'CLOSED'),
  });

  // Every panel here waits for the market's status, so the page's layout is drawn as bars until it comes.
  if (market.isPending) {
    return <OperatorSkeleton />;
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
    <PageOpened.Provider value={opened}>
      <div className="operator" key={generation}>
        <div className="operator-side">
          {market.isError && (
            <p role="alert" className="form-error">
              The exchange is not answering, so the market state shown may be out of date.{' '}
              {lastUpdatedText(market.dataUpdatedAt, !isRefusal(market.error))}.
            </p>
          )}
          <MarketControl
            market={market.data}
            instruments={instruments.isPending ? undefined : listed.length}
            read={marketRead}
          />
          {!editable && (
            <HealthPanel
              instruments={listed}
              closed={closed}
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
              {instruments.updatedAt !== undefined && <> {lastUpdatedText(instruments.updatedAt, !instruments.refused)}.</>}
            </p>
          )}
          {market.data.simulationId === null && (
            <StartSimulation start={start} market={market.data} read={marketRead} />
          )}
          <StartNotice start={start} market={market.data} />
          {closed && (
            <Ended
              closedAt={market.data.closedAt}
              closesAt={market.data.closesAt}
              onSchedule={market.data.closedOnSchedule}
              simulationId={market.data.simulationId}
            />
          )}
          {closed && <StartSimulation start={start} market={market.data} read={marketRead} />}
          {!editable && (
            <BotRuns
              market={market.data.state}
              simulationId={market.data.simulationId}
              closedAt={market.data.closedAt}
              instruments={listed}
            />
          )}
          <InstrumentSetup
            instruments={listed}
            editable={editable}
            closed={closed}
            simulationId={market.data.simulationId}
          />
          <BotSetup
            instruments={listed}
            editable={editable}
            closed={closed}
            simulationId={market.data.simulationId}
          />
          <AccountSetup instruments={listed} editable={editable} closed={closed} />
          {editable && (
            <BotRuns
              market={market.data.state}
              simulationId={market.data.simulationId}
              closedAt={null}
              instruments={listed}
            />
          )}
        </div>
      </div>
    </PageOpened.Provider>
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
  /**
   * The exchange closed the market because its set time had come. A close sent from here then closed nothing, and
   * whether it ever answers says nothing of the orders: the exchange's own close cancels them.
   */
  closedOnSchedule?: boolean;
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
  // The exchange's own close for the set time cancels the orders, so one sent from here and never answered, or still
  // on its way, says nothing of them.
  const close = known.closedOnSchedule === true && known.close !== 'done' ? 'idle' : known.close;
  switch (close) {
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
function Ended({
  closedAt,
  closesAt,
  onSchedule,
  simulationId,
}: {
  closedAt: string | null;
  /** The time it was set to close at, if it had one. */
  closesAt: string | null;
  /** The exchange closed it because its set time had come, and no operator. */
  onSchedule: boolean;
  simulationId: string | null;
}) {
  const close = useCloseCommand(simulationId);
  const health = useHealthSummary();
  return (
    <section className="panel ended" aria-labelledby="ended-title">
      <h2 id="ended-title">This market has ended</h2>
      <p>
        It {closedPhrase(closedAt, onSchedule, closesAt)}. Trading is over for good: every bot run
        ended, and every bot&apos;s token stopped working.
      </p>
      <p>
        {restingOrdersText({
          close: close.outcome.kind,
          stillResting: close.stillResting,
          writerHalted: health.data?.writer.available === false,
          closedOnSchedule: onSchedule,
        })}
      </p>
      <p className="muted">
        Nothing was deleted. The bot runs and their comparison, the instruments, the bots and the accounts stay below
        to read, and traders keep their portfolios and their history. Nothing here can be changed any more.
      </p>
    </section>
  );
}
