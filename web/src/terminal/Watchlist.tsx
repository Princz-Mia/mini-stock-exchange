import type { Instrument } from '../api/types';
import { groupDigits } from '../format';
import { arrow, direction, signed } from './session';
import { Sparkline } from './Sparkline';

/**
 * Every instrument: its last price or labelled reference, the change since trading began with a sign and an arrow, a
 * trend line of recent minutes, and its best bid and ask. Which one the terminal shows is pressed.
 */
export function Watchlist({
  instruments,
  selected,
  onSelect,
}: {
  instruments: readonly Instrument[];
  selected: string | null;
  onSelect: (instrumentId: string) => void;
}) {
  return (
    <section className="panel watchlist" aria-labelledby="watchlist-title">
      <h2 id="watchlist-title">Watchlist</h2>
      {instruments.length === 0 ? (
        <p className="muted">No instruments yet. An operator sets up the market.</p>
      ) : (
        <ul className="watchlist-items">
          {instruments.map((instrument) => {
            const { session } = instrument;
            const way = direction(session);
            // The snapshot's last price, as the header shows it; the trade feed's while the snapshot has none yet.
            const last = instrument.lastPrice ?? session.last;
            return (
              <li key={instrument.instrumentId}>
                <button
                  type="button"
                  className="watchlist-item"
                  aria-pressed={instrument.instrumentId === selected}
                  onClick={() => onSelect(instrument.instrumentId)}
                >
                  <span className="watchlist-main">
                    {/* Spaces between the parts, so a screen reader hears "NOVA 101.03", not "NOVA101.03". */}
                    <span className="symbol">{instrument.symbol}</span> <Sparkline session={session} />{' '}
                    <span className="num watchlist-price">
                      {last === null ? (
                        <>
                          {groupDigits(instrument.referencePrice)} <span className="tag">ref</span>
                        </>
                      ) : (
                        groupDigits(last)
                      )}
                    </span>
                  </span>{' '}
                  <span className="watchlist-detail">
                    <span className="watchlist-quotes num muted">
                      <span>Bid {instrument.bestBid === null ? '—' : groupDigits(instrument.bestBid.price)}</span>{' '}
                      <span>Ask {instrument.bestAsk === null ? '—' : groupDigits(instrument.bestAsk.price)}</span>
                    </span>{' '}
                    {way !== null && session.changePercent !== null && (
                      <span className={`num change change-${way}`}>
                        <span aria-hidden="true">{arrow(way)} </span>
                        <span className="visually-hidden">{way === 'flat' ? 'unchanged,' : way}</span>{' '}
                        {signed(session.changePercent)}%
                      </span>
                    )}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
