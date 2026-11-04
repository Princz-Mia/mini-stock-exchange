import type { Instrument } from '../api/types';
import { groupDigits } from '../format';

/** Every instrument, its latest price or labelled reference, and which one the terminal shows. */
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
          {instruments.map((instrument) => (
            <li key={instrument.instrumentId}>
              <button
                type="button"
                className="watchlist-item"
                aria-pressed={instrument.instrumentId === selected}
                onClick={() => onSelect(instrument.instrumentId)}
              >
                <span className="symbol">{instrument.symbol}</span>
                <span className="num">
                  {instrument.lastPrice === null ? (
                    <>
                      {groupDigits(instrument.referencePrice)} <span className="tag">ref</span>
                    </>
                  ) : (
                    groupDigits(instrument.lastPrice)
                  )}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
