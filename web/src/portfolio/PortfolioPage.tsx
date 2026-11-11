import { useQuery } from '@tanstack/react-query';

import { ApiError, api, pollEvery } from '../api/client';
import type { Holding, OrderDetail, OwnTrade } from '../api/types';
import { type Column, PagedTable } from '../app/PagedTable';
import { type Filter, usePagedList } from '../app/paging';
import { useKeyboardScroll } from '../app/useKeyboardScroll';
import { groupDigits, groupInteger, utcTime } from '../format';
import { TermTip } from '../help/TermTip';
import { toCents } from '../terminal/money';

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

const side = (row: { side: 'BUY' | 'SELL' }) => (
  <span className={row.side === 'BUY' ? 'side-buy' : 'side-sell'}>{row.side === 'BUY' ? 'Buy' : 'Sell'}</span>
);

const EXECUTION_COLUMNS: readonly Column<OwnTrade>[] = [
  { key: 'time', header: 'Time', cell: (trade) => utcTime(trade.executedAt), sortValue: (trade) => trade.executedAt },
  { key: 'symbol', header: 'Symbol', cell: (trade) => trade.symbol, sortValue: (trade) => trade.symbol },
  { key: 'side', header: 'Side', cell: side, sortValue: (trade) => trade.side },
  {
    key: 'quantity',
    header: 'Quantity',
    numeric: true,
    cell: (trade) => groupInteger(trade.quantity),
    sortValue: (trade) => trade.quantity,
  },
  {
    key: 'price',
    header: 'Price',
    numeric: true,
    cell: (trade) => groupDigits(trade.price),
    sortValue: (trade) => toCents(trade.price),
  },
  {
    key: 'amount',
    header: 'Amount',
    numeric: true,
    cell: (trade) => groupDigits(trade.notional),
    sortValue: (trade) => toCents(trade.notional),
  },
  {
    key: 'role',
    header: 'Role',
    cell: (trade) => (trade.liquidity === 'MAKER' ? 'Maker' : 'Taker'),
    sortValue: (trade) => trade.liquidity,
  },
];

const orderPrice = (order: OrderDetail) => order.limitPrice ?? order.protectionPrice ?? '0.00';

const ORDER_COLUMNS: readonly Column<OrderDetail>[] = [
  { key: 'placed', header: 'Placed', cell: (order) => utcTime(order.createdAt), sortValue: (order) => order.createdAt },
  { key: 'symbol', header: 'Symbol', cell: (order) => order.symbol, sortValue: (order) => order.symbol },
  { key: 'side', header: 'Side', cell: side, sortValue: (order) => order.side },
  {
    key: 'type',
    header: 'Type',
    cell: (order) => (order.type === 'LIMIT' ? 'Limit' : 'Protected market'),
    sortValue: (order) => order.type,
  },
  {
    key: 'price',
    header: 'Price',
    numeric: true,
    cell: (order) => groupDigits(orderPrice(order)),
    sortValue: (order) => toCents(orderPrice(order)),
  },
  {
    key: 'filled',
    header: 'Filled',
    numeric: true,
    cell: (order) => `${groupInteger(order.filledQuantity)} / ${groupInteger(order.requestedQuantity)}`,
    sortValue: (order) => order.filledQuantity,
  },
  {
    key: 'state',
    header: 'State',
    cell: (order) => (
      <>
        {order.state.replace('_', ' ').toLowerCase()}
        {order.reason !== null && <span className="muted"> · {order.reason}</span>}
      </>
    ),
    sortValue: (order) => order.state,
  },
];

const SIDES: readonly { value: OrderDetail['side']; label: string }[] = [
  { value: 'BUY', label: 'Buy' },
  { value: 'SELL', label: 'Sell' },
];

const SIDE_FILTER: Filter = { name: 'side', label: 'Side', options: SIDES, complete: true };

// Typed against the contract, so a misspelt state does not compile.
const STATES: readonly { value: OrderDetail['state']; label: string }[] = [
  { value: 'OPEN', label: 'Open' },
  { value: 'PARTIALLY_FILLED', label: 'Partly filled' },
  { value: 'FILLED', label: 'Filled' },
  { value: 'CANCELED', label: 'Canceled' },
  { value: 'REJECTED', label: 'Rejected' },
];

const STATE_FILTER: Filter = { name: 'state', label: 'Status', options: STATES, complete: true };

export function PortfolioPage() {
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio, ...REFRESH });
  // The instruments to filter by, by symbol; the terminal reads the same list.
  const instruments = useQuery({ queryKey: ['instruments'], queryFn: api.instruments });
  const symbolFilter: Filter = {
    name: 'instrumentId',
    label: 'Symbol',
    options: (instruments.data?.instruments ?? [])
      .map((instrument) => ({ value: instrument.instrumentId, label: instrument.symbol }))
      .sort((a, b) => a.label.localeCompare(b.label)),
  };
  // One page of each history at a time; only that page is fetched again every five seconds and after account events.
  const trades = usePagedList({
    table: 'executions',
    queryKey: ['own-trades'],
    fetch: async (cursor, { size, order, filters }) => {
      const page = await api.ownTrades(cursor, size, order, filters);
      return { rows: page.trades, nextCursor: page.nextCursor };
    },
    defaults: { size: 25, order: 'newest' },
    filters: [symbolFilter, SIDE_FILTER],
    refetchInterval: REFRESH.refetchInterval,
  });
  const orders = usePagedList({
    table: 'orders',
    queryKey: ['orders', 'all'],
    fetch: async (cursor, { size, order, filters }) => {
      const page = await api.orders('all', cursor, size, order, filters);
      return { rows: page.orders, nextCursor: page.nextCursor };
    },
    defaults: { size: 25, order: 'newest' },
    filters: [symbolFilter, SIDE_FILTER, STATE_FILTER],
    refetchInterval: REFRESH.refetchInterval,
  });
  // The three tables are wider than a phone, so their panels scroll; the keyboard has to reach them too.
  const holdingsPanel = useKeyboardScroll<HTMLElement>();
  const tradesPanel = useKeyboardScroll<HTMLElement>();
  const ordersPanel = useKeyboardScroll<HTMLElement>();

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
          <span className="figure-label">
            <span className="muted">Estimated equity</span>
            <TermTip terms={['equity']} />
          </span>
          <strong>{groupDigits(equity)}</strong>
        </div>
        <div className="figure">
          <span className="muted">Cash</span>
          <strong>{groupDigits(cash.total)}</strong>
        </div>
        <div className="figure">
          <span className="figure-label">
            <span className="muted">Available</span>
            <TermTip terms={['available']} />
          </span>
          <strong>{groupDigits(cash.available)}</strong>
        </div>
        <div className="figure">
          <span className="figure-label">
            <span className="muted">Reserved for open orders</span>
            <TermTip terms={['reserved']} />
          </span>
          <strong>{groupDigits(cash.reserved)}</strong>
        </div>
      </section>

      <section ref={holdingsPanel} className="panel" aria-labelledby="holdings-title">
        <div className="heading-row">
          <h2 id="holdings-title">Holdings</h2>
          <TermTip terms={['mark']} />
        </div>
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

      <section ref={tradesPanel} className="panel" aria-labelledby="trades-title">
        <h2 id="trades-title">Executions</h2>
        <PagedTable
          label="Executions"
          list={trades}
          columns={EXECUTION_COLUMNS}
          rowKey={(trade) => trade.tradeId}
          empty="No executions yet."
          noun="executions"
        />
      </section>

      <section ref={ordersPanel} className="panel" aria-labelledby="orders-title">
        <div className="heading-row">
          <h2 id="orders-title">Orders</h2>
          <TermTip terms={['partial']} />
        </div>
        <PagedTable
          label="Orders"
          list={orders}
          columns={ORDER_COLUMNS}
          rowKey={(order) => order.orderId}
          empty="No orders yet."
          noun="orders"
        />
      </section>
    </div>
  );
}
