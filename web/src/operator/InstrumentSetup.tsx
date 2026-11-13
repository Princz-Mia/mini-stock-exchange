import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, type RefObject, useCallback, useEffect, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { Instrument, InstrumentRequest, InstrumentUsage } from '../api/types';
import { groupDigits, groupInteger } from '../format';
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
function useFocusOnAnswer(kind: CommandOutcome<unknown>['kind'], field: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const lost = document.activeElement === null || document.activeElement === document.body;
    if ((kind === 'done' || kind === 'refused') && lost) {
      field.current?.focus();
    }
  }, [kind, field]);
}

/**
 * Why an instrument in use stays, with who holds it and how many orders name it. Orders sent before the market opens
 * are refused, but kept on record, so even those count.
 */
export function inUseMessage(symbol: string, usage: InstrumentUsage): string {
  const parts = [`${symbol} can't be deleted: it is in use.`];
  if (usage.holderCount > 0) {
    const more = usage.holderCount - usage.holders.length;
    parts.push(
      `Its shares were given to ${usage.holders.join(', ')}${more > 0 ? ` and ${groupInteger(more)} more` : ''}, ` +
        'as listed under Accounts.',
    );
  }
  if (usage.orderCount > 0) {
    const orders = usage.orderCount === 1 ? 'An order names it.' : `${groupInteger(usage.orderCount)} orders name it.`;
    parts.push(`${orders} Orders sent before the market opens are refused, but kept on record.`);
  }
  parts.push('Taking shares or orders back would need correcting entries in the ledger, so an instrument in use stays.');
  return parts.join(' ');
}

/**
 * One instrument and, while the market is being set up, a field to change its reference price and a way to delete it,
 * confirmed first. Each row sends its own commands under their own keys, so an unconfirmed save or delete on one row is
 * retried without touching the others.
 */
function InstrumentRow({
  instrument,
  editable,
  onDeleted,
}: {
  instrument: Instrument;
  editable: boolean;
  onDeleted: (symbol: string) => void;
}) {
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
  const priceOutcomeId = useId();
  const removalOutcomeId = useId();
  const refused = command.outcome.kind === 'refused';

  const sendDelete = useCallback(
    async (instrumentId: string, key: string) => {
      const deleted = await api.deleteInstrument(instrumentId, key);
      void client.invalidateQueries({ queryKey: ['instruments'] });
      return deleted;
    },
    [client],
  );
  const removal = useCommand(sendDelete);
  const [confirming, setConfirming] = useState(false);
  const deleteButton = useRef<HTMLButtonElement>(null);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const wasConfirming = useRef(false);
  // Confirming swaps the buttons, so focus follows: to Confirm when asked, and back to Delete when kept.
  useEffect(() => {
    if (confirming) {
      confirmButton.current?.focus();
    } else if (wasConfirming.current) {
      deleteButton.current?.focus();
    }
    wasConfirming.current = confirming;
  }, [confirming]);
  useFocusOnAnswer(removal.outcome.kind, deleteButton);
  const deleted = removal.outcome.kind === 'done';
  useEffect(() => {
    if (deleted) {
      onDeleted(instrument.symbol);
    }
  }, [deleted, onDeleted, instrument.symbol]);
  // One command at a time per row: a delete waits for a price save's final answer, and the other way round, so a
  // deleted row never offers to retry a save.
  const saving = command.outcome.kind === 'sending' || command.unresolved;
  const deleting = removal.outcome.kind === 'sending' || removal.unresolved;
  const deleteRefused = removal.outcome.kind === 'refused';
  const removalOutcome =
    removal.outcome.kind === 'refused' && removal.outcome.problem?.usage
      ? { ...removal.outcome, message: inUseMessage(instrument.symbol, removal.outcome.problem.usage) }
      : removal.outcome;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void command.run(price.trim());
  };

  return (
    <>
      <tr>
        <th scope="row">{instrument.symbol}</th>
        <td className="num">{groupDigits(instrument.referencePrice)}</td>
        {editable && deleted && (
          <td colSpan={2} className="muted">
            Deleted
          </td>
        )}
        {editable && !deleted && (
          <td>
            <form onSubmit={submit} className="price-edit">
              <fieldset disabled={saving || deleting}>
                <input
                  ref={field}
                  aria-label={`New price for ${instrument.symbol}`}
                  aria-invalid={refused || undefined}
                  aria-describedby={refused ? priceOutcomeId : undefined}
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
        {editable && !deleted && (
          <td>
            {confirming ? (
              <div className="row-actions">
                <button
                  ref={confirmButton}
                  type="button"
                  className="primary danger"
                  aria-label={`Confirm delete of ${instrument.symbol}`}
                  onClick={() => {
                    setConfirming(false);
                    void removal.run(instrument.instrumentId);
                  }}
                >
                  Confirm delete
                </button>
                <button
                  type="button"
                  className="quiet"
                  aria-label={`Keep ${instrument.symbol}`}
                  onClick={() => setConfirming(false)}
                >
                  Keep
                </button>
              </div>
            ) : (
              <button
                ref={deleteButton}
                type="button"
                className="quiet"
                aria-label={`Delete ${instrument.symbol}`}
                aria-describedby={deleteRefused ? removalOutcomeId : undefined}
                disabled={saving || deleting}
                onClick={() => setConfirming(true)}
              >
                Delete
              </button>
            )}
          </td>
        )}
      </tr>
      {(command.outcome.kind !== 'idle' || (removal.outcome.kind !== 'idle' && !deleted)) && (
        <tr className="row-outcome">
          <td colSpan={editable ? 4 : 2}>
            {/* Each control is described by its own command's words only. */}
            <div id={priceOutcomeId}>
              <CommandStatus
                outcome={command.outcome}
                done={(edited) =>
                  `${edited.symbol}'s reference price is now ${groupDigits(edited.referencePrice)}. ` +
                  'The list shows it once market data has it.'
                }
                onRetry={() => void command.retry()}
              />
            </div>
            {/* A done delete is said by the panel, as this row goes once market data drops it. */}
            <div id={removalOutcomeId}>
              {!deleted && (
                <CommandStatus outcome={removalOutcome} done={() => ''} onRetry={() => void removal.retry()} />
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

/** The catalogue and, while the market is being set up, a form to add to it and a way to reprice or delete each entry. */
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
  // Said here rather than in the row, which goes once market data drops it. The row loses its controls, and focus with
  // them, so focus goes to the add form, the next thing to do here, unless the user has moved it meanwhile.
  const [deletedSymbol, setDeletedSymbol] = useState<string | null>(null);
  const announceDeleted = useCallback((gone: string) => {
    setDeletedSymbol(gone);
    if (document.activeElement === null || document.activeElement === document.body) {
      symbolField.current?.focus();
    }
  }, []);
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
        <table aria-label="Instruments" className="setup-table">
          <thead>
            <tr>
              <th scope="col">Symbol</th>
              <th scope="col" className="num">
                Reference price
              </th>
              {editable && <th scope="col">New price</th>}
              {editable && (
                <th scope="col">
                  <span className="visually-hidden">Delete</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {instruments.map((instrument) => (
              <InstrumentRow
                key={instrument.instrumentId}
                instrument={instrument}
                editable={editable}
                onDeleted={announceDeleted}
              />
            ))}
          </tbody>
        </table>
      )}
      {deletedSymbol !== null && (
        <p role="status" className="outcome outcome-ok">
          {deletedSymbol} is deleted. The list drops it once market data has it.
        </p>
      )}
      {editable && instruments.length > 0 && (
        <p className="muted hint">
          Reference prices can be changed until the market opens. Starting shares already granted are valued at the
          new price. An instrument can be deleted until an account is given its shares or an order names it.
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
