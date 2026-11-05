import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useState } from 'react';

import { api } from '../api/client';
import type { AccountRequest, Instrument } from '../api/types';
import { groupDigits, groupInteger } from '../format';
import { CommandStatus } from './CommandStatus';
import { useCommand } from './useCommand';

/** The default endowment: offered as the form's starting values, never applied without the operator. */
export const DEFAULT_CASH = '100000.00';
export const DEFAULT_SHARES = '1000';

/** Funded accounts with what they started with and, during setup, a form to fund another trader. */
export function AccountSetup({ instruments, editable }: { instruments: readonly Instrument[]; editable: boolean }) {
  const client = useQueryClient();
  const accounts = useInfiniteQuery({
    queryKey: ['accounts'],
    queryFn: ({ pageParam }) => api.accounts(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const [username, setUsername] = useState('');
  const [cash, setCash] = useState(DEFAULT_CASH);
  const [shares, setShares] = useState<Record<string, string>>({});
  const send = useCallback(async (request: AccountRequest, key: string) => {
    const created = await api.provisionAccount(request, key);
    setUsername('');
    void client.invalidateQueries({ queryKey: ['accounts'] });
    return created;
  }, [client]);
  const command = useCommand(send);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const holdings = instruments
      .map((instrument) => ({ instrumentId: instrument.instrumentId, text: shares[instrument.instrumentId] ?? DEFAULT_SHARES }))
      .filter(({ text }) => text.trim() !== '' && text.trim() !== '0')
      .map(({ instrumentId, text }) => ({ instrumentId, quantity: Number(text.trim()) }));
    void command.run({ username: username.trim(), cash: cash.trim(), holdings });
  };

  const rows = accounts.data?.pages.flatMap((page) => page.accounts) ?? [];

  return (
    <section className="panel" aria-labelledby="accounts-title">
      <h2 id="accounts-title">Accounts</h2>
      {accounts.isError ? (
        <p role="alert" className="form-error">
          The accounts could not be loaded.
        </p>
      ) : rows.length === 0 ? (
        <p className="muted">{accounts.isPending ? 'Loading…' : 'No accounts yet.'}</p>
      ) : (
        <table aria-label="Accounts">
          <thead>
            <tr>
              <th scope="col">Trader</th>
              <th scope="col" className="num">
                Starting cash
              </th>
              <th scope="col">Starting shares</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((account) => (
              <tr key={account.accountId}>
                <th scope="row">{account.username}</th>
                <td className="num">{groupDigits(account.cash)}</td>
                <td>
                  {account.holdings.length === 0
                    ? '—'
                    : account.holdings.map((held) => `${held.symbol} ${groupInteger(held.quantity)}`).join(' · ')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {accounts.hasNextPage && (
        <button type="button" className="quiet" onClick={() => void accounts.fetchNextPage()}>
          Show more
        </button>
      )}
      {editable ? (
        <form onSubmit={submit} className="setup-form" aria-label="Fund a trader">
          <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
            <div className="field-row">
              <label>
                Trader's username
                <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="off" />
              </label>
              <label>
                Cash
                <input value={cash} onChange={(event) => setCash(event.target.value)} inputMode="decimal" />
              </label>
            </div>
            {instruments.length > 0 && (
              <div className="field-row">
                {instruments.map((instrument) => (
                  <label key={instrument.instrumentId}>
                    {instrument.symbol} shares
                    <input
                      value={shares[instrument.instrumentId] ?? DEFAULT_SHARES}
                      onChange={(event) =>
                        setShares((current) => ({ ...current, [instrument.instrumentId]: event.target.value }))
                      }
                      inputMode="numeric"
                    />
                  </label>
                ))}
              </div>
            )}
            <p className="muted hint">
              Starts from the default endowment: 100,000.00 and 1,000 shares of each instrument. Set shares to 0 to
              leave an instrument out. Only existing traders can be funded, once each.
            </p>
            <button type="submit" className="primary">
              Fund account
            </button>
          </fieldset>
        </form>
      ) : (
        <p className="muted">Endowments are frozen once the market opens; nothing is granted after that.</p>
      )}
      <CommandStatus
        outcome={command.outcome}
        done={(created) => `Funded ${created.username} with ${groupDigits(created.cash)}.`}
        onRetry={() => void command.retry()}
      />
    </section>
  );
}
