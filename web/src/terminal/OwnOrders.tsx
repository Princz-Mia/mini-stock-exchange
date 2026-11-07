import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';

import { ApiError, api } from '../api/client';
import type { OrderDetail } from '../api/types';
import { groupDigits, groupInteger, utcTime } from '../format';

/**
 * Open orders with a cancel button each. A cancel keeps its idempotency key until it gets an answer, so pressing
 * it again after a timeout resends the same cancel instead of starting a new one.
 */
export function OwnOrders({ orders }: { orders: readonly OrderDetail[] }) {
  const client = useQueryClient();
  const keys = useRef(new Map<string, string>());
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

  return (
    <section className="panel" aria-labelledby="own-orders-title">
      <h2 id="own-orders-title">Open orders</h2>
      {cancel.isError && (
        <p role="alert" className="form-error">
          {cancel.error instanceof ApiError && cancel.error.code === 'ORDER_NOT_CANCELABLE'
            ? 'That order was already filled.'
            : cancel.error instanceof ApiError && cancel.error.status === 429
              ? 'Too many orders and cancels in a moment. Wait a second and try again; it will not cancel twice.'
              : 'The cancel did not go through. Try again; it will not cancel twice.'}
        </p>
      )}
      {orders.length === 0 ? (
        <p className="muted">No open orders.</p>
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
            {orders.map((order) => (
              <tr key={order.orderId}>
                <td>{utcTime(order.createdAt)}</td>
                <td>{order.symbol}</td>
                <td className={order.side === 'BUY' ? 'side-buy' : 'side-sell'}>{order.side === 'BUY' ? 'Buy' : 'Sell'}</td>
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
    </section>
  );
}
