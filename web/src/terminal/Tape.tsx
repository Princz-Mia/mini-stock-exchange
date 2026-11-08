import type { TapeTrade } from '../api/types';
import { groupDigits, groupInteger, utcTime } from '../format';

/**
 * The newest public trades, with the side that took liquidity in words as well as colour. While {@code waiting}, no
 * market frame has arrived yet: the tape is unknown, not empty.
 */
export function Tape({ trades, waiting = false }: { trades: readonly TapeTrade[]; waiting?: boolean }) {
  return (
    <section className="panel" aria-labelledby="tape-title">
      <h2 id="tape-title">Trades</h2>
      {waiting ? (
        <p className="muted">Waiting for the live market…</p>
      ) : trades.length === 0 ? (
        <p className="muted">No trades yet.</p>
      ) : (
        <table aria-label="Recent trades">
          <thead>
            <tr>
              <th scope="col">Time</th>
              <th scope="col" className="num">
                Price
              </th>
              <th scope="col" className="num">
                Shares
              </th>
              <th scope="col">Aggressor</th>
            </tr>
          </thead>
          <tbody>
            {trades.map((trade) => (
              <tr key={trade.tradeId}>
                <td>{utcTime(trade.executedAt)}</td>
                <td className="num">{groupDigits(trade.price)}</td>
                <td className="num">{groupInteger(trade.quantity)}</td>
                <td className={trade.aggressorSide === 'BUY' ? 'side-buy' : 'side-sell'}>
                  {trade.aggressorSide === 'BUY' ? 'Buy' : 'Sell'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
