import { useQuery } from '@tanstack/react-query';
import { createContext, useContext } from 'react';
import { Link, useSearchParams } from 'react-router';

import { ApiError, api, pollEvery } from '../api/client';
import type { MarketStatus } from '../api/types';
import { CloseTime } from '../app/closeTime';
import { FeedStatus } from '../app/FeedStatus';
import {
  useAskAgainWhenFunded,
  useForgetInAddress,
  useInstruments,
  useMarketStatus,
  useRefreshOnMarketChange,
} from '../app/market';
import { PRIVATE_QUERIES } from '../app/useAccountEvents';
import { isOperator, useSession } from '../auth/session';
import { groupDigits } from '../format';
import { TermTip } from '../help/TermTip';
import { InstrumentFigures } from './InstrumentFigures';
import { OrderBook } from './OrderBook';
import { ClosedTicket, OrderTicket } from './OrderTicket';
import { OwnOrders } from './OwnOrders';
import { PriceChart } from './PriceChart';
import { Tape } from './Tape';
import { type StreamFactory, useMarketStream } from './useMarketStream';
import { Watchlist } from './Watchlist';

/** How live streams are opened; tests swap in a fake, the browser uses EventSource. */
export const StreamContext = createContext<StreamFactory | undefined>(undefined);

const TRANSPORT_LABELS = { connecting: 'Connecting…', open: 'Connected', stale: 'No updates for 15 s' } as const;

/** Why new orders cannot be placed right now. Cancels are never paused. */
const PAUSED: Record<Exclude<MarketStatus['state'], 'OPEN'>, { badge: string; reason: string }> = {
  HALTED: { badge: 'Trading halted', reason: 'Trading is halted. New orders are paused; you can still cancel open orders.' },
  SETUP: { badge: 'Not open yet', reason: 'The market has not opened yet.' },
  NOT_SET_UP: { badge: 'Not set up', reason: 'The market has not been set up yet.' },
  CLOSED: { badge: 'Market closed', reason: 'The market has closed and takes no more orders.' },
};

/** The instrument chosen is in the address, and is the simulation's it was chosen in. */
const CHOSEN = ['instrument'] as const;

/**
 * The trading terminal: the selected instrument's live book, tape and chart from the market stream, the order
 * ticket beside them, and the trader's open and recently finished orders. Public market data and private balances are separate sources
 * and are not claimed to be one snapshot; each shows its own freshness.
 */
export function TerminalPage() {
  const [params, setParams] = useSearchParams();
  const create = useContext(StreamContext);
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio, refetchInterval: pollEvery(5_000) });
  // The authoritative phase. The venue state in market data can lag; if this cannot be read, nothing is paused here
  // and the exchange still decides.
  const market = useMarketStatus();
  // The current simulation's instruments, as that status names it: never the list of a simulation before it.
  const instruments = useInstruments(market, { refetchInterval: pollEvery(5_000) });
  const paused = market.data === undefined || market.data.state === 'OPEN' ? null : PAUSED[market.data.state];
  // A close cancels every open order and releases what it reserved. The trader's own lists are read again when the
  // status says so, without waiting for their next poll or for the account's own notification. In another simulation
  // none of them is the trader's any more: they are dropped, and read for that one. Nothing else of the page is kept
  // either, as it waits for the new simulation's instruments, and the instrument chosen in the one before is let go.
  const generation = useRefreshOnMarketChange(market.data, PRIVATE_QUERIES);
  useForgetInAddress(generation, CHOSEN);
  // A trader with no account in this market is asked about again once the status says one may have been created.
  useAskAgainWhenFunded(market.data, PRIVATE_QUERIES);
  const orders = useQuery({
    queryKey: ['orders', 'active'],
    queryFn: () => api.orders('active'),
    refetchInterval: pollEvery(5_000),
  });
  // Final, unlike a halt: the ticket gives way to a statement, and nothing here says trading will come back. The
  // statement goes by the orders still listed as open: the market is closed before the last of them is cancelled.
  const closed =
    market.data?.state === 'CLOSED'
      ? { closedAt: market.data.closedAt, onSchedule: market.data.closedOnSchedule, stillOpen: orders.data?.orders.length }
      : null;
  // A trader login without an account yet, such as an operator who also trades before funding itself: the market is
  // shown, the ticket is not.
  const noAccount = portfolio.error instanceof ApiError && portfolio.error.code === 'NO_TRADING_ACCOUNT';
  const operator = isOperator(useSession().data);

  const list = instruments.instruments;
  // The instrument the address names, if this market lists it. A bookmark from a simulation that has closed, or a tab
  // left open over a change of simulation, names one it does not: the first listed instrument is shown then, as with
  // no instrument in the address, and the page says so.
  const asked = params.get('instrument');
  const selected = list.find((instrument) => instrument.instrumentId === asked) ?? list[0] ?? null;
  const selectedId = selected?.instrumentId ?? null;
  const notListed = asked !== null && selected !== null && selected.instrumentId !== asked;
  const stream = useMarketStream(selected?.instrumentId ?? null, create === undefined ? {} : { create });
  const frame = stream.frame;
  const feed = stream.feed ?? instruments.feed;
  // The newer of the live frame and the polled list, by market revision: a stream that dropped leaves its last frame
  // behind the list. The header's price, figures, bid and ask all come from the same one, so they never disagree.
  const latest =
    selected === null
      ? null
      : frame !== null && BigInt(frame.sourceCommandSequence) >= BigInt(selected.sourceCommandSequence)
        ? { lastPrice: frame.lastPrice, session: frame.session, bid: frame.bids[0]?.price, ask: frame.asks[0]?.price }
        : {
            lastPrice: selected.lastPrice,
            session: selected.session,
            bid: selected.bestBid?.price,
            ask: selected.bestAsk?.price,
          };
  // The snapshot's last price, or the trade feed's while the snapshot has none yet.
  const lastPrice = latest === null ? null : (latest.lastPrice ?? latest.session.last);

  if (instruments.isPending) {
    return <p className="page-status">Loading markets…</p>;
  }
  if (instruments.isError) {
    return (
      <p role="alert" className="page-status">
        Market data is not reachable right now.
      </p>
    );
  }

  return (
    <div className="terminal">
      <Watchlist
        instruments={list}
        selected={selectedId}
        onSelect={(instrumentId) =>
          setParams((current) => {
            // The chart's settings stay as they are when another instrument is chosen.
            const next = new URLSearchParams(current);
            next.set('instrument', instrumentId);
            return next;
          })
        }
      />
      {selected === null ? (
        <p className="muted terminal-empty" role="status">
          {market.data?.state === 'SETUP'
            ? 'The market is being set up and has no instruments yet, so there is nothing to trade.'
            : asked !== null
              ? 'The instrument in this address is not part of this market, and the market has no instruments yet.'
              : 'This market has no instruments yet, so there is nothing to trade.'}
        </p>
      ) : (
        <>
          <div className="terminal-market">
            <div className="market-header">
              <h1>{selected.symbol}</h1>
              <span className="last-price num">
                {lastPrice !== null ? groupDigits(lastPrice) : `${groupDigits(selected.referencePrice)} ref`}
              </span>
              {paused !== null && (
                <>
                  <span className="state-badge state-halted">{paused.badge}</span>
                  {market.data?.state === 'HALTED' && <TermTip terms={['halted']} />}
                  {closed !== null && <TermTip terms={['closed']} />}
                </>
              )}
              {feed !== undefined && <FeedStatus feed={feed} />}
              <span className={`transport transport-${stream.transport}`} role="status">
                {TRANSPORT_LABELS[stream.transport]}
              </span>
            </div>
            {/* When the market closes by itself, counted down on the exchange's clock. */}
            <CloseTime market={market.data} className="chart-note close-time" />
            {notListed && (
              <p className="chart-note" role="status">
                The instrument in this address is not part of this market. Showing {selected.symbol} instead.
              </p>
            )}
            {latest !== null && (
              <InstrumentFigures session={latest.session} bid={latest.bid ?? null} ask={latest.ask ?? null} />
            )}
            <PriceChart instrumentId={selected.instrumentId} trades={frame?.recentTrades ?? null} />
            <div className="market-split">
              <OrderBook bids={frame?.bids ?? []} asks={frame?.asks ?? []} waiting={frame === null} />
              <Tape trades={frame?.recentTrades ?? []} waiting={frame === null} />
            </div>
          </div>
          {noAccount ? (
            // Getting an account is setup advice, and a closed market is past setting up.
            closed !== null ? (
              <ClosedTicket closedAt={closed.closedAt} onSchedule={closed.onSchedule} account={false} />
            ) : (
              <NoAccount operator={operator} />
            )
          ) : (
            <OrderTicket
              key={selected.instrumentId}
              instrument={selected}
              portfolio={portfolio.data}
              paused={paused?.reason ?? null}
              closed={closed}
            />
          )}
        </>
      )}
      <div className="terminal-orders">
        <OwnOrders
          orders={orders.data?.orders ?? []}
          read={orders.data !== undefined}
          instrument={selected === null ? null : { instrumentId: selected.instrumentId, symbol: selected.symbol }}
          closed={closed !== null}
        />
      </div>
    </div>
  );
}

/** In the ticket's place while the user has no trading account: what is missing, and who provides it. */
function NoAccount({ operator }: { operator: boolean }) {
  return (
    <section className="panel ticket" aria-labelledby="no-account-title">
      <h2 id="no-account-title">No trading account</h2>
      <p role="status">
        {operator ? (
          <>
            You have no trading account in this market yet. While a market is being set up, give yourself one under
            Accounts on the{' '}
            <Link to="/operator">operator screen</Link>.
          </>
        ) : (
          'You have no trading account in this market yet. An operator creates accounts while a market is being set up.'
        )}
      </p>
    </section>
  );
}
