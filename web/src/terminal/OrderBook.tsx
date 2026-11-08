import type { Quote } from '../api/types';
import { groupDigits, groupInteger } from '../format';
import { fromCents, toCents } from './money';

function Side({ label, quotes, side }: { label: string; quotes: readonly Quote[]; side: 'buy' | 'sell' }) {
  return (
    <table aria-label={label} className="book-side">
      <thead>
        <tr>
          <th scope="col" className="num">
            Price
          </th>
          <th scope="col" className="num">
            Shares
          </th>
          <th scope="col" className="num">
            Orders
          </th>
        </tr>
      </thead>
      <tbody>
        {quotes.length === 0 ? (
          <tr>
            <td colSpan={3} className="muted">
              No {side === 'buy' ? 'bids' : 'asks'}
            </td>
          </tr>
        ) : (
          quotes.map((quote) => (
            <tr key={quote.price}>
              <td className={`num side-${side}`}>{groupDigits(quote.price)}</td>
              <td className="num">{groupInteger(quote.quantity)}</td>
              <td className="num">{quote.orderCount}</td>
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}

/**
 * Aggregate depth, best first on both sides, with the spread between them. While {@code waiting}, no market frame has
 * arrived yet, so the book is unknown rather than empty and says so.
 */
export function OrderBook({
  bids,
  asks,
  waiting = false,
}: {
  bids: readonly Quote[];
  asks: readonly Quote[];
  waiting?: boolean;
}) {
  const bestBid = bids[0];
  const bestAsk = asks[0];
  const spread =
    bestBid !== undefined && bestAsk !== undefined
      ? fromCents(toCents(bestAsk.price) - toCents(bestBid.price))
      : null;
  return (
    <section className="panel" aria-labelledby="book-title">
      <div className="panel-header">
        <h2 id="book-title">Order book</h2>
        {spread !== null && <span className="muted">Spread {groupDigits(spread)}</span>}
      </div>
      {waiting ? (
        <p className="muted">Waiting for the live market…</p>
      ) : (
        <div className="book">
          <Side label="Bids" quotes={bids} side="buy" />
          <Side label="Asks" quotes={asks} side="sell" />
        </div>
      )}
    </section>
  );
}
