import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useState } from 'react';

import { api } from '../api/client';
import type { Instrument, InstrumentRequest } from '../api/types';
import { groupDigits } from '../format';
import { CommandStatus } from './CommandStatus';
import { useCommand } from './useCommand';

/** The spec's starting scenario, offered as quick fills; the operator still adds each one. */
export const DEFAULT_INSTRUMENTS: readonly InstrumentRequest[] = [
  { symbol: 'NOVA', referencePrice: '100.00' },
  { symbol: 'ORBT', referencePrice: '50.00' },
  { symbol: 'HELI', referencePrice: '200.00' },
  { symbol: 'QUAD', referencePrice: '25.00' },
  { symbol: 'VELO', referencePrice: '80.00' },
];

/** The catalogue and, while the market is being set up, a form to add to it. */
export function InstrumentSetup({ instruments, editable }: { instruments: readonly Instrument[]; editable: boolean }) {
  const client = useQueryClient();
  const [symbol, setSymbol] = useState('');
  const [price, setPrice] = useState('');
  const send = useCallback(async (request: InstrumentRequest, key: string) => {
    const created = await api.addInstrument(request, key);
    setSymbol('');
    setPrice('');
    void client.invalidateQueries({ queryKey: ['instruments'] });
    void client.invalidateQueries({ queryKey: ['market'] });
    return created;
  }, [client]);
  const command = useCommand(send);
  const listed = new Set(instruments.map((instrument) => instrument.symbol));
  const suggestions = DEFAULT_INSTRUMENTS.filter((suggestion) => !listed.has(suggestion.symbol));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void command.run({ symbol: symbol.trim().toUpperCase(), referencePrice: price.trim() });
  };

  return (
    <section className="panel" aria-labelledby="instruments-title">
      <h2 id="instruments-title">Instruments</h2>
      {instruments.length === 0 ? (
        <p className="muted">No instruments yet.</p>
      ) : (
        <table aria-label="Instruments">
          <thead>
            <tr>
              <th scope="col">Symbol</th>
              <th scope="col" className="num">
                Reference price
              </th>
            </tr>
          </thead>
          <tbody>
            {instruments.map((instrument) => (
              <tr key={instrument.instrumentId}>
                <th scope="row">{instrument.symbol}</th>
                <td className="num">{groupDigits(instrument.referencePrice)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editable ? (
        <form onSubmit={submit} className="setup-form" aria-label="Add an instrument">
          <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
            <div className="field-row">
              <label>
                Symbol
                <input value={symbol} onChange={(event) => setSymbol(event.target.value)} autoComplete="off" />
              </label>
              <label>
                Reference price
                <input value={price} onChange={(event) => setPrice(event.target.value)} inputMode="decimal" />
              </label>
              <button type="submit" className="primary">
                Add instrument
              </button>
            </div>
          </fieldset>
          {suggestions.length > 0 && (
            <p className="suggestions">
              <span className="muted">Starting scenario:</span>
              {suggestions.map((suggestion) => (
                <button
                  key={suggestion.symbol}
                  type="button"
                  className="chip"
                  onClick={() => {
                    setSymbol(suggestion.symbol);
                    setPrice(suggestion.referencePrice);
                  }}
                >
                  {suggestion.symbol} {suggestion.referencePrice}
                </button>
              ))}
            </p>
          )}
        </form>
      ) : (
        <p className="muted">The catalogue is frozen once the market opens.</p>
      )}
      <CommandStatus
        outcome={command.outcome}
        done={(created) => `Added ${created.symbol} at ${created.referencePrice}. It appears in the list once market data has it.`}
        onRetry={() => void command.retry()}
      />
    </section>
  );
}
