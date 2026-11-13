import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, type RefObject, useCallback, useEffect, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { Instrument, InstrumentEdited, InstrumentEditRequest, InstrumentRequest, InstrumentUsage } from '../api/types';
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

/** The fields a refusal names, so each can be marked invalid and focused. */
function refusedFields(outcome: CommandOutcome<unknown>): ReadonlySet<string> {
  return new Set(outcome.kind === 'refused' ? (outcome.problem?.errors ?? []).map((error) => error.field) : []);
}

/**
 * One instrument, its name under its symbol and, while the market is being set up, Edit and Delete. Edit opens a form
 * under the row with the name and the reference price, filled in, saved together as the exchange replaces both: an
 * empty name removes it. Delete is confirmed first. Each row sends its own commands under their own keys, so an
 * unconfirmed save or delete on one row is retried without touching the others.
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
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [name, setName] = useState('');
  const [price, setPrice] = useState('');
  // The last answer saved, until the list catches up: the list comes from Market Data, a moment behind.
  const [saved, setSaved] = useState<InstrumentEdited | null>(null);
  // A refusal marks this form's fields only while the form that sent it is open: Save sets this and Cancel clears it. A
  // refused form closes only through Cancel, so opening never finds it set by an old refusal.
  const [refusalShown, setRefusalShown] = useState(false);
  const send = useCallback(
    async (edit: InstrumentEditRequest, key: string) => {
      const edited = await api.editInstrument(instrument.instrumentId, edit, key);
      setSaved(edited);
      setEditing(false);
      setConfirming(false);
      void client.invalidateQueries({ queryKey: ['instruments'] });
      return edited;
    },
    [client, instrument.instrumentId],
  );
  const command = useCommand(send);
  const editButton = useRef<HTMLButtonElement>(null);
  const nameField = useRef<HTMLInputElement>(null);
  const priceField = useRef<HTMLInputElement>(null);
  const saveOutcomeId = useId();
  const removalOutcomeId = useId();
  const invalid = refusalShown ? refusedFields(command.outcome) : new Set<string>();
  // A refusal goes with the form it answered: once that is cancelled, or opened afresh, its words go too.
  const saveOutcome: CommandOutcome<InstrumentEdited> =
    command.outcome.kind === 'refused' && !refusalShown ? { kind: 'idle' } : command.outcome;

  // Opening puts focus on the name. Cancel puts it back on Edit; a save does too, below, but only if focus was lost
  // meanwhile, as the user may have moved on to another row while it was sending.
  const cancelled = useRef(false);
  useEffect(() => {
    if (editing) {
      nameField.current?.focus();
    } else if (cancelled.current) {
      cancelled.current = false;
      editButton.current?.focus();
    }
  }, [editing]);
  useFocusOnAnswer(command.outcome.kind === 'done' ? 'done' : 'idle', editButton);
  // A refused save keeps the form open: focus goes to the first field the refusal names, or the name, unless the user
  // has moved it out of the form meanwhile.
  const editor = useRef<HTMLFormElement>(null);
  useEffect(() => {
    const active = document.activeElement;
    const lost = active === null || active === document.body || (editor.current?.contains(active) ?? false);
    if (command.outcome.kind === 'refused' && lost) {
      (refusedFields(command.outcome).has('referencePrice') && !refusedFields(command.outcome).has('displayName')
        ? priceField
        : nameField
      ).current?.focus();
    }
  }, [command.outcome]);

  const sendDelete = useCallback(
    async (instrumentId: string, key: string) => {
      const deleted = await api.deleteInstrument(instrumentId, key);
      void client.invalidateQueries({ queryKey: ['instruments'] });
      return deleted;
    },
    [client],
  );
  const removal = useCommand(sendDelete);
  const deleteButton = useRef<HTMLButtonElement>(null);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const kept = useRef(false);
  // Confirming swaps the buttons, so focus follows: to Confirm when asked, and back to Delete when kept. A confirmation
  // closed with the form leaves focus to the form's own rule.
  useEffect(() => {
    if (confirming) {
      confirmButton.current?.focus();
    } else if (kept.current) {
      kept.current = false;
      deleteButton.current?.focus();
    }
  }, [confirming]);
  useFocusOnAnswer(removal.outcome.kind, deleteButton);
  const deleted = removal.outcome.kind === 'done';
  useEffect(() => {
    if (deleted) {
      onDeleted(instrument.symbol);
    }
  }, [deleted, onDeleted, instrument.symbol]);
  // One command at a time per row: a delete waits for a save's final answer, and the other way round, so a deleted row
  // never offers to retry a save.
  const saving = command.outcome.kind === 'sending' || command.unresolved;
  const deleting = removal.outcome.kind === 'sending' || removal.unresolved;
  const deleteRefused = removal.outcome.kind === 'refused';
  const removalOutcome =
    removal.outcome.kind === 'refused' && removal.outcome.problem?.usage
      ? { ...removal.outcome, message: inUseMessage(instrument.symbol, removal.outcome.problem.usage) }
      : removal.outcome;
  const columns = editable ? 3 : 2;

  const open = () => {
    // Until Market Data has the last save, the list shows the values before it, and saving those would undo it.
    const behind = saved !== null && BigInt(instrument.sourceCommandSequence) < BigInt(saved.commandSequence);
    setName((behind ? saved.displayName : instrument.displayName) ?? '');
    setPrice(behind ? saved.referencePrice : instrument.referencePrice);
    setEditing(true);
  };
  const cancel = () => {
    cancelled.current = true;
    setRefusalShown(false);
    setConfirming(false);
    setEditing(false);
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = name.trim();
    setRefusalShown(true);
    void command.run({ referencePrice: price.trim(), displayName: trimmed === '' ? null : trimmed });
  };

  return (
    <>
      <tr>
        <th scope="row">
          {instrument.symbol}
          {/* The space keeps "NOVA Nova Dynamics" apart for screen readers; the name shows on its own line. */}
          {instrument.displayName ? <> <span className="instrument-name">{instrument.displayName}</span></> : null}
        </th>
        <td className="num">{groupDigits(instrument.referencePrice)}</td>
        {editable && (
          <td>
            {deleted ? (
              <span className="muted">Deleted</span>
            ) : confirming ? (
              // Keyed apart, so the focused Confirm is never reused as Edit when the buttons swap back.
              <div className="row-actions" key="confirm">
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
                  onClick={() => {
                    kept.current = true;
                    setConfirming(false);
                  }}
                >
                  Keep
                </button>
              </div>
            ) : (
              <div className="row-actions" key="actions">
                {!editing && (
                  <button
                    ref={editButton}
                    type="button"
                    className="quiet"
                    aria-label={`Edit ${instrument.symbol}`}
                    disabled={saving || deleting}
                    onClick={open}
                  >
                    Edit
                  </button>
                )}
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
              </div>
            )}
          </td>
        )}
      </tr>
      {editable && editing && !deleted && (
        <tr className="row-editor">
          <td colSpan={columns}>
            <form ref={editor} onSubmit={submit} aria-label={`Edit ${instrument.symbol}`}>
              <fieldset disabled={saving || deleting}>
                <div className="field-row">
                  <label>
                    Name
                    <input
                      ref={nameField}
                      value={name}
                      onChange={(event) => setName(event.target.value)}
                      aria-invalid={invalid.has('displayName') || undefined}
                      aria-describedby={invalid.has('displayName') ? saveOutcomeId : undefined}
                      autoComplete="off"
                    />
                  </label>
                  <label>
                    Reference price
                    <input
                      ref={priceField}
                      value={price}
                      onChange={(event) => setPrice(event.target.value)}
                      aria-invalid={invalid.has('referencePrice') || undefined}
                      aria-describedby={invalid.has('referencePrice') ? saveOutcomeId : undefined}
                      inputMode="decimal"
                      autoComplete="off"
                    />
                  </label>
                </div>
                <p className="muted hint">Leave the name empty for none. Both are saved together.</p>
                <div className="row-actions">
                  <button type="submit" className="primary" aria-label={`Save ${instrument.symbol}`}>
                    Save
                  </button>
                  <button
                    type="button"
                    className="quiet"
                    aria-label={`Cancel editing ${instrument.symbol}`}
                    onClick={cancel}
                  >
                    Cancel
                  </button>
                </div>
              </fieldset>
            </form>
          </td>
        </tr>
      )}
      {(saveOutcome.kind !== 'idle' || (removal.outcome.kind !== 'idle' && !deleted)) && (
        <tr className="row-outcome">
          <td colSpan={columns}>
            {/* Each control is described by its own command's words only. */}
            <div id={saveOutcomeId}>
              <CommandStatus
                outcome={saveOutcome}
                done={(edited) =>
                  `Saved ${edited.symbol}${edited.displayName ? ` as ${edited.displayName}` : ', with no name,'}` +
                  ` at ${groupDigits(edited.referencePrice)}. The list shows it once market data has it.`
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

/** The catalogue and, while the market is being set up, a form to add to it and a way to edit or delete each entry. */
export function InstrumentSetup({ instruments, editable }: { instruments: readonly Instrument[]; editable: boolean }) {
  const client = useQueryClient();
  const [symbol, setSymbol] = useState('');
  const [price, setPrice] = useState('');
  const [name, setName] = useState('');
  const send = useCallback(async (request: InstrumentRequest, key: string) => {
    const created = await api.addInstrument(request, key);
    setSymbol('');
    setPrice('');
    setName('');
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
    const trimmed = name.trim();
    // Without a name, the request is the one it was before names existed, so its key's answer is too.
    void command.run({
      symbol: symbol.trim().toUpperCase(),
      referencePrice: price.trim(),
      ...(trimmed === '' ? {} : { displayName: trimmed }),
    });
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
              {editable && (
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
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
          An instrument&apos;s name and reference price can be changed until the market opens; starting shares already
          granted are valued at the new price. An instrument can be deleted until an account is given its shares or an
          order names it.
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
              <label>
                Name (optional)
                <input value={name} onChange={(event) => setName(event.target.value)} autoComplete="off" />
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
          `Added ${created.symbol}${created.displayName ? ` (${created.displayName})` : ''} at ` +
          `${groupDigits(created.referencePrice)}. It appears in the list once market data has it.`
        }
        onRetry={() => void command.retry()}
      />
    </section>
  );
}
