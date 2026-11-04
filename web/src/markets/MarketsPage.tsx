import { useQuery } from '@tanstack/react-query';

import { api, pollEvery } from '../api/client';
import type { Quote } from '../api/types';
import { FeedStatus } from '../app/FeedStatus';
import { groupDigits } from '../format';

function price(quote: Quote | null): string {
  return quote === null ? '—' : groupDigits(quote.price);
}

/** Every instrument with its quotes. The full terminal grows around this list. */
export function MarketsPage() {
  const instruments = useQuery({
    queryKey: ['instruments'],
    queryFn: api.instruments,
    refetchInterval: pollEvery(5_000),
  });

  return (
    <section className="panel" aria-labelledby="markets-title">
      <div className="panel-header">
        <h1 id="markets-title">Markets</h1>
        {instruments.data && <FeedStatus feed={instruments.data.feed} />}
      </div>
      {instruments.isPending && <p className="muted">Loading markets…</p>}
      {instruments.isError && (
        <p role="alert" className="form-error">
          Market data is not reachable right now.
        </p>
      )}
      {instruments.data?.instruments.length === 0 && (
        <p className="muted">No instruments yet. An operator sets up the market.</p>
      )}
      {instruments.data !== undefined && instruments.data.instruments.length > 0 && (
        <table aria-label="Instruments">
          <thead>
            <tr>
              <th scope="col">Symbol</th>
              <th scope="col" className="num">
                Last
              </th>
              <th scope="col" className="num">
                Bid
              </th>
              <th scope="col" className="num">
                Ask
              </th>
              <th scope="col">Market</th>
            </tr>
          </thead>
          <tbody>
            {instruments.data.instruments.map((instrument) => (
              <tr key={instrument.instrumentId}>
                <th scope="row">{instrument.symbol}</th>
                <td className="num">
                  {instrument.lastPrice === null ? (
                    <span title="Reference price; no trades yet">
                      {groupDigits(instrument.referencePrice)} <span className="tag">ref</span>
                    </span>
                  ) : (
                    groupDigits(instrument.lastPrice)
                  )}
                </td>
                <td className="num">{price(instrument.bestBid)}</td>
                <td className="num">{price(instrument.bestAsk)}</td>
                <td>{instrument.venueState === 'OPEN' ? 'Open' : instrument.venueState.toLowerCase()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
