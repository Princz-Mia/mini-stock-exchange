import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, type RefObject, useCallback, useEffect, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { Instrument, InstrumentRequest } from '../api/types';
import { groupDigits } from '../format';
import { CommandStatus } from './CommandStatus';
import { type CommandOutcome, useCommand } from './useCommand';

/** The default starting scenario, offered as quick fills; the operator still adds each one. */
export const DEFAULT_INSTRUMENTS: readonly InstrumentRequest[] = [
  { symbol: 'NOVA', referencePrice: '100.00' },
  { symbol: 'ORBT', referencePrice: '50.00' },
  { symbol: 'HELI', referencePrice: '200.00' },
  { symbol: 'QUAD', referencePrice: '25.00' },
  { symbol: 'VELO', referencePrice: '80.00' },
];

/**
 * Puts focus back on a form's field once its command has a final answer. Sending disables the form, and a disabled
 * control loses focus to the page, so keyboard and screen reader users would lose their place. Focus the user has
 * moved elsewhere in the meantime stays where it is.
 */
function useFocusOnAnswer(kind: CommandOutcome<unknown>['kind'], field: RefObject<HTMLInputElement | null>) {
  useEffect(() => {
    const lost = document.activeElement === null || document.activeElement === document.body;
    if ((kind === 'done' || kind === 'refused') && lost) {
      field.current?.focus();
    }
  }, [kind, field]);
}

/**
 * One instrument and, while the market is being set up, a field to change its reference price. Each row sends its own
 * command under its own key, so an unconfirmed save on one row is retried without touching the others.
 */
function InstrumentRow({ instrument, editable }: { instrument: Instrument; editable: boolean }) {
  const client = useQueryClient();
  const [price, setPrice] = useState('');
  const send = useCallback(
    async (referencePrice: string, key: string) => {
      const edited = await api.editInstrument(instrument.instrumentId, { referencePrice }, key);
      setPrice('');
      void client.invalidateQueries({ queryKey: ['instruments'] });
      return edited;
    },
    [client, instrument.instrumentId],
  );
  const command = useCommand(send);
  const field = useRef<HTMLInputElement>(null);
  useFocusOnAnswer(command.outcome.kind, field);
  const outcomeId = useId();
  const refused = command.outcome.kind === 'refused';

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void command.run(price.trim());
  };

  return (
    <>
      <tr>
        <th scope="row">{instrument.symbol}</th>
        <td className="num">{groupDigits(instrument.referencePrice)}</td>
        {editable && (
          <td>
            <form onSubmit={submit} className="price-edit">
              <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
                <input
                  ref={field}
                  aria-label={`New price for ${instrument.symbol}`}
                  aria-invalid={refused || undefined}
                  aria-describedby={refused ? outcomeId : undefined}
                  value={price}
                  onChange={(event) => setPrice(event.target.value)}
                  inputMode="decimal"
                  autoComplete="off"
                />
                <button type="submit" aria-label={`Save new price for ${instrument.symbol}`}>
                  Save
                </button>
              </fieldset>
            </form>
          </td>
        )}
      </tr>
      {command.outcome.kind !== 'idle' && (
        <tr className="row-outcome">
          <td id={outcomeId} colSpan={editable ? 3 : 2}>
            <CommandStatus
              outcome={command.outcome}
              done={(edited) =>
                `${edited.symbol}'s reference price is now ${groupDigits(edited.referencePrice)}. ` +
                'The list shows it once market data has it.'
              }
              onRetry={() => void command.retry()}
            />
          </td>
        </tr>
      )}
    </>
  );
}

/** The catalogue and, while the market is being set up, a form to add to it and a field to reprice each entry. */
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
  const symbolField = useRef<HTMLInputElement>(null);
  useFocusOnAnswer(command.outcome.kind, symbolField);
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
              {editable && <th scope="col">New price</th>}
            </tr>
          </thead>
          <tbody>
            {instruments.map((instrument) => (
              <InstrumentRow key={instrument.instrumentId} instrument={instrument} editable={editable} />
            ))}
          </tbody>
        </table>
      )}
      {editable && instruments.length > 0 && (
        <p className="muted hint">
          Reference prices can be changed until the market opens. Starting shares already granted are valued at the
          new price.
        </p>
      )}
      {editable ? (
        <form onSubmit={submit} className="setup-form" aria-label="Add an instrument">
          <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
            <div className="field-row">
              <label>
                Symbol
                <input
                  ref={symbolField}
                  value={symbol}
                  onChange={(event) => setSymbol(event.target.value)}
                  autoComplete="off"
                />
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
        done={(created) =>
          `Added ${created.symbol} at ${groupDigits(created.referencePrice)}. It appears in the list once market data has it.`
        }
        onRetry={() => void command.retry()}
      />
    </section>
  );
}
