import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { Instrument, TraderAdded, TraderRequest } from '../api/types';
import { groupDigits } from '../format';
import { CommandStatus } from './CommandStatus';
import { EXAMPLE_CASH, holdingsFrom, ShareFields, type ShareInputs } from './Endowment';
import { generatePassword } from './password';
import { refusedFields, refusedHoldings, useFocusOnRefusal } from './refusals';
import { useCommand } from './useCommand';

/** "Added maria with 25,000.00." or, without an endowment, that the trader has no account yet. */
export function addedText(added: TraderAdded): string {
  return added.cash === null
    ? `Added ${added.username}, without an account yet: fund them below before the market opens.`
    : `Added ${added.username} with ${groupDigits(added.cash)}.`;
}

/**
 * Adds a trader login during setup: a username, a password the operator types or generates, and optionally the
 * starting cash and shares. The password lives only in this form's state: it is sent once, cleared when the trader
 * is added, and never stored in the browser.
 */
export function AddTraderForm({ instruments }: { instruments: readonly Instrument[] }) {
  const client = useQueryClient();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [shown, setShown] = useState(false);
  const [copied, setCopied] = useState<'copied' | 'failed' | null>(null);
  const [cash, setCash] = useState('');
  const [shares, setShares] = useState<ShareInputs>({});
  const send = useCallback(
    async (request: TraderRequest, key: string) => {
      const added = await api.addTrader(request, key);
      setUsername('');
      setPassword('');
      setShown(false);
      setCopied(null);
      void client.invalidateQueries({ queryKey: ['accounts'] });
      void client.invalidateQueries({ queryKey: ['traders'] });
      return added;
    },
    [client],
  );
  const command = useCommand(send);
  const form = useRef<HTMLFormElement>(null);
  const outcomeId = useId();
  const passwordHintId = useId();
  const [sent, setSent] = useState<readonly { instrumentId: string }[]>([]);
  const invalid = refusedFields(command.outcome);
  const invalidShares = refusedHoldings(invalid, sent);
  useFocusOnRefusal(command.outcome.kind, form);
  const marked = (field: string, hint?: string) => ({
    'aria-invalid': invalid.has(field) || undefined,
    'aria-describedby': [hint, invalid.has(field) ? outcomeId : undefined].filter(Boolean).join(' ') || undefined,
  });

  const generate = () => {
    setPassword(generatePassword());
    setShown(true);
    setCopied(null);
  };
  const copy = () => {
    navigator.clipboard.writeText(password).then(
      () => setCopied('copied'),
      () => setCopied('failed'),
    );
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const holdings = holdingsFrom(instruments, shares);
    const typedCash = cash.trim();
    setSent(holdings);
    // Shares alone are an account with no cash: the exchange takes 0.00 with a holding.
    const endowment =
      typedCash !== '' ? { cash: typedCash, holdings } : holdings.length > 0 ? { cash: '0.00', holdings } : {};
    void command.run({ username: username.trim(), password, ...endowment });
  };

  return (
    <>
      <form ref={form} onSubmit={submit} className="setup-form" aria-label="Add a trader">
        <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
          <div className="field-row">
            <label>
              Username
              <input
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                placeholder="e.g. maria"
                {...marked('username')}
              />
            </label>
          </div>
          <div className="password-row">
            <label>
              Password
              <input
                type={shown ? 'text' : 'password'}
                value={password}
                onChange={(event) => {
                  setPassword(event.target.value);
                  setCopied(null);
                }}
                autoComplete="new-password"
                autoCapitalize="none"
                spellCheck={false}
                {...marked('password', passwordHintId)}
              />
            </label>
            <div className="password-actions">
              <button type="button" className="quiet" aria-pressed={shown} onClick={() => setShown(!shown)}>
                Show password
              </button>
              <button type="button" className="quiet" onClick={generate}>
                Generate
              </button>
              <button type="button" className="quiet" onClick={copy} disabled={password === ''}>
                Copy
              </button>
            </div>
          </div>
          <p id={passwordHintId} className="muted hint">
            12 to 64 characters, not the username. Generate makes 20 random letters and digits. Hand it to the trader
            yourself: it can't be shown again or reset.
          </p>
          <p role="status" className="muted copy-status">
            {copied === 'copied'
              ? 'Password copied.'
              : copied === 'failed'
                ? "The password couldn't be copied; select it and copy it by hand."
                : ''}
          </p>
          <div className="field-row">
            <label>
              Cash (optional)
              <input
                value={cash}
                onChange={(event) => setCash(event.target.value)}
                inputMode="decimal"
                placeholder={`e.g. ${EXAMPLE_CASH}`}
                {...marked('cash')}
              />
            </label>
          </div>
          <ShareFields
            instruments={instruments}
            shares={shares}
            onChange={setShares}
            invalid={invalidShares}
            describedBy={outcomeId}
          />
          <p className="muted hint">
            With cash or shares, the trader gets an account now; with neither, only the login, which can be funded
            below. A login stays for later simulations.
          </p>
          <button type="submit" className="primary">
            Add trader
          </button>
        </fieldset>
      </form>
      <div id={outcomeId}>
        <CommandStatus outcome={command.outcome} done={addedText} onRetry={() => void command.retry()} />
      </div>
    </>
  );
}
