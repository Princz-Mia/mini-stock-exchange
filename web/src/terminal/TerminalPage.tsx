import { useQuery } from '@tanstack/react-query';
import { createContext, useContext } from 'react';
import { useSearchParams } from 'react-router';

import { api, pollEvery } from '../api/client';
import { FeedStatus } from '../app/FeedStatus';
import { groupDigits } from '../format';
import { OrderBook } from './OrderBook';
import { OrderTicket } from './OrderTicket';
import { OwnOrders } from './OwnOrders';
import { PriceChart } from './PriceChart';
import { Tape } from './Tape';
import { type StreamFactory, useMarketStream } from './useMarketStream';
import { Watchlist } from './Watchlist';

/** How live streams are opened; tests swap in a fake, the browser uses EventSource. */
export const StreamContext = createContext<StreamFactory | undefined>(undefined);

const TRANSPORT_LABELS = { connecting: 'Connecting…', open: 'Connected', stale: 'No updates for 15 s' } as const;

/**
 * The trading terminal: the selected instrument's live book, tape and chart from the market stream, the order
 * ticket beside them, and the trader's open orders. Public market data and private balances are separate sources
 * and are not claimed to be one snapshot; each shows its own freshness.
 */
export function TerminalPage() {
  const [params, setParams] = useSearchParams();
  const create = useContext(StreamContext);
  const instruments = useQuery({ queryKey: ['instruments'], queryFn: api.instruments, refetchInterval: pollEvery(5_000) });
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio, refetchInterval: pollEvery(5_000) });
  const orders = useQuery({
    queryKey: ['orders', 'active'],
    queryFn: () => api.orders('active'),
    refetchInterval: pollEvery(5_000),
  });

  const list = instruments.data?.instruments ?? [];
  const selectedId = params.get('instrument') ?? list[0]?.instrumentId ?? null;
  const selected = list.find((instrument) => instrument.instrumentId === selectedId) ?? null;
  const stream = useMarketStream(selected?.instrumentId ?? null, create === undefined ? {} : { create });
  const frame = stream.frame;

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
        onSelect={(instrumentId) => setParams({ instrument: instrumentId })}
      />
      {selected === null ? (
        <p className="muted terminal-empty">Choose an instrument to trade.</p>
      ) : (
        <>
          <div className="terminal-market">
            <div className="market-header">
              <h1>{selected.symbol}</h1>
              <span className="last-price num">
                {frame?.lastPrice != null ? groupDigits(frame.lastPrice) : `${groupDigits(selected.referencePrice)} ref`}
              </span>
              <FeedStatus feed={stream.feed ?? instruments.data.feed} />
              <span className={`transport transport-${stream.transport}`} role="status">
                {TRANSPORT_LABELS[stream.transport]}
              </span>
            </div>
            <PriceChart trades={frame?.recentTrades ?? []} />
            <div className="market-split">
              <OrderBook bids={frame?.bids ?? []} asks={frame?.asks ?? []} />
              <Tape trades={frame?.recentTrades ?? []} />
            </div>
          </div>
          <OrderTicket key={selected.instrumentId} instrument={selected} portfolio={portfolio.data} />
        </>
      )}
      <div className="terminal-orders">
        <OwnOrders orders={orders.data?.orders ?? []} />
      </div>
    </div>
  );
}
