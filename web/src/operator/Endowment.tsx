import type { Instrument } from '../api/types';

/**
 * Shown as examples inside the empty fields. The forms start empty: nothing is sent that the operator did not type,
 * and these values are never filled in for them.
 */
export const EXAMPLE_CASH = '100000.00';
export const EXAMPLE_SHARES = '1000';

export type ShareInputs = Record<string, string>;

/** The holdings to send: each listed instrument's typed amount, leaving out blanks and zeros. */
export function holdingsFrom(instruments: readonly Instrument[], shares: ShareInputs) {
  return instruments
    .map(({ instrumentId }) => ({ instrumentId, text: (shares[instrumentId] ?? '').trim() }))
    .filter(({ text }) => text !== '' && text !== '0')
    .map(({ instrumentId, text }) => ({ instrumentId, quantity: Number(text) }));
}

/**
 * One shares field per listed instrument, empty until the operator types an amount. The instruments a refusal names
 * are marked invalid and described by it.
 */
export function ShareFields({
  instruments,
  shares,
  onChange,
  invalid = new Set(),
  describedBy,
}: {
  instruments: readonly Instrument[];
  shares: ShareInputs;
  onChange: (shares: ShareInputs) => void;
  invalid?: ReadonlySet<string>;
  describedBy?: string;
}) {
  if (instruments.length === 0) {
    return null;
  }
  return (
    <div className="field-row">
      {instruments.map((instrument) => (
        <label key={instrument.instrumentId}>
          {instrument.symbol} shares
          <input
            value={shares[instrument.instrumentId] ?? ''}
            onChange={(event) => onChange({ ...shares, [instrument.instrumentId]: event.target.value })}
            inputMode="numeric"
            placeholder={`e.g. ${EXAMPLE_SHARES}`}
            aria-invalid={invalid.has(instrument.instrumentId) || undefined}
            aria-describedby={invalid.has(instrument.instrumentId) ? describedBy : undefined}
          />
        </label>
      ))}
    </div>
  );
}
