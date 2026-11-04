import { useInfiniteQuery, useQuery } from '@tanstack/react-query';

import { ApiError, api, pollEvery } from '../api/client';
import type { Holding } from '../api/types';
import { groupDigits, groupInteger, utcTime } from '../format';

/**
 * Private data is refetched every five seconds while the page is open, and when the tab regains focus. Polling stops
 * once the server refuses, such as for a user without a trading account.
 */
const REFRESH = { refetchInterval: pollEvery(5_000), refetchOnWindowFocus: true } as const;

function Mark({ holding }: { holding: Holding }) {
  if (holding.mark.source === 'REFERENCE') {
    return (
      <span>
        {groupDigits(holding.mark.price)} <span className="tag">reference</span>
      </span>
    );
  }
  return (
    <span>
      {groupDigits(holding.mark.price)}{' '}
      <span className="muted">last trade{holding.mark.at === null ? '' : ` · ${utcTime(holding.mark.at)}`}</span>
    </span>
  );
}

export function PortfolioPage() {
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio, ...REFRESH });
  const trades = useInfiniteQuery({
    queryKey: ['own-trades'],
    queryFn: ({ pageParam }) => api.ownTrades(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    ...REFRESH,
  });
  const orders = useInfiniteQuery({
    queryKey: ['orders', 'all'],
    queryFn: ({ pageParam }) => api.orders('all', pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    ...REFRESH,
  });

  if (portfolio.isPending) {
    return <p className="page-status">Loading portfolio…</p>;
  }
  if (portfolio.isError) {
    const noAccount = portfolio.error instanceof ApiError && portfolio.error.code === 'NO_TRADING_ACCOUNT';
    return (
      <p role="alert" className="page-status">
        {noAccount
          ? 'You have no trading account yet. An operator creates accounts when the market is set up.'
          : 'Your portfolio is not reachable right now.'}
      </p>
    );
  }
  const { cash, holdings, equity } = portfolio.data;

  return (
    <div className="stack">
      <section className="summary" aria-label="Summary">
        <div className="figure">
          <span className="muted">Estimated equity</span>
          <strong>{groupDigits(equity)}</strong>
        </div>
        <div className="figure">
          <span className="muted">Cash</span>
          <strong>{groupDigits(cash.total)}</strong>
        </div>
        <div className="figure">
          <span className="muted">Available</span>
          <strong>{groupDigits(cash.available)}</strong>
        </div>
        <div className="figure">
          <span className="muted">Reserved for open orders</span>
          <strong>{groupDigits(cash.reserved)}</strong>
        </div>
      </section>

      <section className="panel" aria-labelledby="holdings-title">
        <h2 id="holdings-title">Holdings</h2>
        {holdings.length === 0 ? (
          <p className="muted">No holdings.</p>
        ) : (
          <table aria-label="Holdings">
            <thead>
              <tr>
                <th scope="col">Symbol</th>
                <th scope="col" className="num">
                  Shares
                </th>
                <th scope="col" className="num">
                  Available
                </th>
                <th scope="col" className="num">
                  Reserved
                </th>
                <th scope="col">Mark</th>
                <th scope="col" className="num">
                  Value
                </th>
              </tr>
            </thead>
            <tbody>
              {holdings.map((holding) => (
                <tr key={holding.instrumentId}>
                  <th scope="row">{holding.symbol}</th>
                  <td className="num">{groupInteger(holding.total)}</td>
                  <td className="num">{groupInteger(holding.available)}</td>
                  <td className="num">{groupInteger(holding.reserved)}</td>
                  <td>
                    <Mark holding={holding} />
                  </td>
                  <td className="num">{groupDigits(holding.value)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel" aria-labelledby="trades-title">
        <h2 id="trades-title">Executions</h2>
        {trades.data?.pages[0]?.trades.length === 0 && <p className="muted">No executions yet.</p>}
        {trades.data !== undefined && trades.data.pages[0]?.trades.length !== 0 && (
          <table aria-label="Executions">
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col">Symbol</th>
                <th scope="col">Side</th>
                <th scope="col" className="num">
                  Quantity
                </th>
                <th scope="col" className="num">
                  Price
                </th>
                <th scope="col" className="num">
                  Amount
                </th>
                <th scope="col">Role</th>
              </tr>
            </thead>
            <tbody>
              {trades.data.pages.flatMap((page) =>
                page.trades.map((trade) => (
                  <tr key={trade.tradeId}>
                    <td>{utcTime(trade.executedAt)}</td>
                    <td>{trade.symbol}</td>
                    <td className={trade.side === 'BUY' ? 'side-buy' : 'side-sell'}>
                      {trade.side === 'BUY' ? 'Buy' : 'Sell'}
                    </td>
                    <td className="num">{groupInteger(trade.quantity)}</td>
                    <td className="num">{groupDigits(trade.price)}</td>
                    <td className="num">{groupDigits(trade.notional)}</td>
                    <td>{trade.liquidity === 'MAKER' ? 'Maker' : 'Taker'}</td>
                  </tr>
                )),
              )}
            </tbody>
          </table>
        )}
        {trades.hasNextPage && (
          <button type="button" className="quiet" onClick={() => void trades.fetchNextPage()}>
            Load more executions
          </button>
        )}
      </section>

      <section className="panel" aria-labelledby="orders-title">
        <h2 id="orders-title">Orders</h2>
        {orders.data?.pages[0]?.orders.length === 0 && <p className="muted">No orders yet.</p>}
        {orders.data !== undefined && orders.data.pages[0]?.orders.length !== 0 && (
          <table aria-label="Orders">
            <thead>
              <tr>
                <th scope="col">Placed</th>
                <th scope="col">Symbol</th>
                <th scope="col">Side</th>
                <th scope="col">Type</th>
                <th scope="col" className="num">
                  Price
                </th>
                <th scope="col" className="num">
                  Filled
                </th>
                <th scope="col">State</th>
              </tr>
            </thead>
            <tbody>
              {orders.data.pages.flatMap((page) =>
                page.orders.map((order) => (
                  <tr key={order.orderId}>
                    <td>{utcTime(order.createdAt)}</td>
                    <td>{order.symbol}</td>
                    <td className={order.side === 'BUY' ? 'side-buy' : 'side-sell'}>
                      {order.side === 'BUY' ? 'Buy' : 'Sell'}
                    </td>
                    <td>{order.type === 'LIMIT' ? 'Limit' : 'Protected market'}</td>
                    <td className="num">{groupDigits(order.limitPrice ?? order.protectionPrice ?? '0.00')}</td>
                    <td className="num">
                      {groupInteger(order.filledQuantity)} / {groupInteger(order.requestedQuantity)}
                    </td>
                    <td>
                      {order.state.replace('_', ' ').toLowerCase()}
                      {order.reason !== null && <span className="muted"> · {order.reason}</span>}
                    </td>
                  </tr>
                )),
              )}
            </tbody>
          </table>
        )}
        {orders.hasNextPage && (
          <button type="button" className="quiet" onClick={() => void orders.fetchNextPage()}>
            Load more orders
          </button>
        )}
      </section>
    </div>
  );
}
