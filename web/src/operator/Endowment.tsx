import type { Instrument } from '../api/types';

/** The default endowment: offered as the forms' starting values, never applied without the operator. */
export const DEFAULT_CASH = '100000.00';
export const DEFAULT_SHARES = '1000';

export type ShareInputs = Record<string, string>;

/** The holdings to send: each listed instrument's typed amount, or the default, leaving out blanks and zeros. */
export function holdingsFrom(instruments: readonly Instrument[], shares: ShareInputs) {
  return instruments
    .map(({ instrumentId }) => ({ instrumentId, text: (shares[instrumentId] ?? DEFAULT_SHARES).trim() }))
    .filter(({ text }) => text !== '' && text !== '0')
    .map(({ instrumentId, text }) => ({ instrumentId, quantity: Number(text) }));
}

/** One shares field per listed instrument, starting from the default. */
export function ShareFields({
  instruments,
  shares,
  onChange,
}: {
  instruments: readonly Instrument[];
  shares: ShareInputs;
  onChange: (shares: ShareInputs) => void;
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
            value={shares[instrument.instrumentId] ?? DEFAULT_SHARES}
            onChange={(event) => onChange({ ...shares, [instrument.instrumentId]: event.target.value })}
            inputMode="numeric"
          />
        </label>
      ))}
    </div>
  );
}
