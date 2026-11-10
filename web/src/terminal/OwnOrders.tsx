import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type KeyboardEvent, useId, useRef, useState } from 'react';

import { ApiError, api, pollEvery } from '../api/client';
import type { OrderDetail } from '../api/types';
import { groupDigits, groupInteger, utcTime } from '../format';

/** How many finished orders the Recent tab shows: those that ended most recently. */
export const RECENT_ORDERS = 20;

/**
 * How an order ended, in words: filled, canceled with what had filled, or rejected and why. A cancel the trader made
 * reads as a cancel; the exchange canceling what a protected market order could not fill says so.
 */
export function outcome(order: OrderDetail): string {
  const filled = `${groupInteger(order.filledQuantity)} of ${groupInteger(order.requestedQuantity)}`;
  switch (order.state) {
    case 'FILLED':
      return `Filled · ${filled}`;
    case 'CANCELED': {
      const how = order.filledQuantity > 0 ? `Canceled · ${filled} filled` : 'Canceled';
      if (order.reason === 'IOC_REMAINDER_CANCELED') {
        return `${how} · the rest found no match`;
      }
      return order.reason === null || order.reason === 'CANCELED_BY_OWNER' ? how : `${how} · ${order.reason}`;
    }
    case 'REJECTED':
      return order.reason === null ? 'Rejected' : `Rejected · ${order.reason}`;
    default:
      return order.state;
  }
}

const sideText = (order: OrderDetail) => (order.side === 'BUY' ? 'Buy' : 'Sell');
const price = (order: OrderDetail) => order.limitPrice ?? order.protectionPrice ?? '0.00';

type Tab = 'open' | 'recent';

/**
 * The trader's orders, in two tabs: open ones with a cancel button each, and the most recently ended ones with how
 * they ended. Both can be narrowed to the instrument on screen. A cancel keeps its idempotency key until it gets an answer,
 * so pressing it again after a timeout resends the same cancel instead of starting a new one.
 */
export function OwnOrders({
  orders,
  instrument,
}: {
  /** The open orders, all of them: at most the per-account limit, so they come whole. */
  orders: readonly OrderDetail[];
  /** The instrument on screen, to narrow the lists to. */
  instrument: { instrumentId: string; symbol: string } | null;
}) {
  const client = useQueryClient();
  const id = useId();
  const [tab, setTab] = useState<Tab>('open');
  const [onlyThis, setOnlyThis] = useState(false);
  const narrowTo = onlyThis && instrument !== null ? instrument : null;
  const keys = useRef(new Map<string, string>());

  const recent = useQuery({
    queryKey: ['orders', 'recent', narrowTo?.instrumentId ?? null],
    // The exchange lists finished orders by when they ended, so an order resting for hours and canceled just now is
    // first, not behind every order placed after it.
    queryFn: async () => {
      const page = await api.orders(
        'finished',
        undefined,
        RECENT_ORDERS,
        undefined,
        narrowTo === null ? {} : { instrumentId: narrowTo.instrumentId },
      );
      return page.orders;
    },
    enabled: tab === 'recent',
    refetchInterval: pollEvery(5_000),
  });

  const cancel = useMutation({
    mutationFn: (orderId: string) => {
      let key = keys.current.get(orderId);
      if (key === undefined) {
        key = crypto.randomUUID();
        keys.current.set(orderId, key);
      }
      return api.cancelOrder(orderId, key);
    },
    onSettled: (_data, error, orderId) => {
      // A 429, 503 or 504 settled nothing, so the next press resends the same cancel under the same key.
      const answered = error === null || (error instanceof ApiError && ![429, 503, 504].includes(error.status));
      if (answered) {
        keys.current.delete(orderId);
      }
      void client.invalidateQueries({ queryKey: ['orders'] });
      void client.invalidateQueries({ queryKey: ['portfolio'] });
    },
  });

  const open = narrowTo === null ? orders : orders.filter((order) => order.instrumentId === narrowTo.instrumentId);
  const tabs: { key: Tab; label: string }[] = [
    { key: 'open', label: `Open orders (${groupInteger(open.length)})` },
    { key: 'recent', label: 'Recent orders' },
  ];
  // Arrow keys move between the tabs, as a tab list does.
  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      const next: Tab = tab === 'open' ? 'recent' : 'open';
      setTab(next);
      document.getElementById(`${id}-tab-${next}`)?.focus();
      event.preventDefault();
    }
  };

  return (
    <section className="panel" aria-labelledby={`${id}-title`}>
      <div className="panel-header">
        <h2 id={`${id}-title`}>Your orders</h2>
        {instrument !== null && (
          <label className="inline-check">
            <input type="checkbox" checked={onlyThis} onChange={(event) => setOnlyThis(event.target.checked)} />
            Only {instrument.symbol}
          </label>
        )}
      </div>
      <div className="tabs" role="tablist" aria-label="Your orders">
        {tabs.map((each) => (
          <button
            key={each.key}
            id={`${id}-tab-${each.key}`}
            type="button"
            role="tab"
            aria-selected={tab === each.key}
            aria-controls={`${id}-panel-${each.key}`}
            tabIndex={tab === each.key ? 0 : -1}
            className="tab"
            onClick={() => setTab(each.key)}
            onKeyDown={onTabKey}
          >
            {each.label}
          </button>
        ))}
      </div>
      {/* Each panel takes focus, as the tab list pattern asks, so the keyboard reaches a table wider than the panel. */}
      <div
        id={`${id}-panel-open`}
        role="tabpanel"
        aria-labelledby={`${id}-tab-open`}
        hidden={tab !== 'open'}
        tabIndex={0}
      >
        {cancel.isError && (
          <p role="alert" className="form-error">
            {cancel.error instanceof ApiError && cancel.error.code === 'ORDER_NOT_CANCELABLE'
              ? 'That order was already filled.'
              : cancel.error instanceof ApiError && cancel.error.status === 429
                ? 'Too many orders and cancels in a moment. Wait a second and try again; it will not cancel twice.'
                : 'The cancel did not go through. Try again; it will not cancel twice.'}
          </p>
        )}
        {open.length === 0 ? (
          <p className="muted">{narrowTo === null ? 'No open orders.' : `No open orders in ${narrowTo.symbol}.`}</p>
        ) : (
          <table aria-label="Open orders">
            <thead>
              <tr>
                <th scope="col">Placed</th>
                <th scope="col">Symbol</th>
                <th scope="col">Side</th>
                <th scope="col" className="num">
                  Limit
                </th>
                <th scope="col" className="num">
                  Filled
                </th>
                <th scope="col" className="num">
                  Working
                </th>
                <th scope="col">
                  <span className="visually-hidden">Action</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {open.map((order) => (
                <tr key={order.orderId}>
                  <td>{utcTime(order.createdAt)}</td>
                  <td>{order.symbol}</td>
                  <td className={order.side === 'BUY' ? 'side-buy' : 'side-sell'}>{sideText(order)}</td>
                  <td className="num">{groupDigits(order.limitPrice ?? '0.00')}</td>
                  <td className="num">{groupInteger(order.filledQuantity)}</td>
                  <td className="num">{groupInteger(order.workingQuantity)}</td>
                  <td>
                    <button
                      type="button"
                      className="quiet"
                      onClick={() => cancel.mutate(order.orderId)}
                      disabled={cancel.isPending && cancel.variables === order.orderId}
                      aria-label={`Cancel ${order.side === 'BUY' ? 'buy' : 'sell'} ${order.symbol} at ${order.limitPrice ?? ''}`}
                    >
                      Cancel
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <div
        id={`${id}-panel-recent`}
        role="tabpanel"
        aria-labelledby={`${id}-tab-recent`}
        hidden={tab !== 'recent'}
        tabIndex={0}
      >
        {tab === 'recent' && recent.isError && recent.data !== undefined && (
          <p role="alert" className="form-error">
            Recent orders could not be refreshed; showing the last loaded.
          </p>
        )}
        {tab === 'recent' &&
          (recent.isPending ? (
            <p className="muted">Loading…</p>
          ) : recent.isError && recent.data === undefined ? (
            <p role="alert" className="form-error">
              Recent orders could not be loaded.
            </p>
          ) : recent.data.length === 0 ? (
            <p className="muted">
              {narrowTo === null ? 'No finished orders yet.' : `No finished orders in ${narrowTo.symbol} yet.`}
            </p>
          ) : (
            <table aria-label="Recent orders">
              <thead>
                <tr>
                  <th scope="col">Placed</th>
                  <th scope="col">Symbol</th>
                  <th scope="col">Side</th>
                  <th scope="col" className="num">
                    Price
                  </th>
                  <th scope="col">How it ended</th>
                  <th scope="col">Ended</th>
                </tr>
              </thead>
              <tbody>
                {recent.data.map((order) => (
                  <tr key={order.orderId}>
                    <td>{utcTime(order.createdAt)}</td>
                    <td>{order.symbol}</td>
                    <td className={order.side === 'BUY' ? 'side-buy' : 'side-sell'}>{sideText(order)}</td>
                    <td className="num">{groupDigits(price(order))}</td>
                    <td>{outcome(order)}</td>
                    <td>{utcTime(order.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
      </div>
    </section>
  );
}
