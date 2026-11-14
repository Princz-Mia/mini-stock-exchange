import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useState } from 'react';

import { api } from '../api/client';
import type { AccountRequest, AccountSummary, Instrument } from '../api/types';
import { type Column, PagedTable } from '../app/PagedTable';
import { usePagedList } from '../app/paging';
import { groupDigits, groupInteger } from '../format';
import { toCents } from '../terminal/money';
import { CommandStatus } from './CommandStatus';
import { DEFAULT_CASH, holdingsFrom, ShareFields, type ShareInputs } from './Endowment';
import { strategyTag } from './strategies';
import { useCommand } from './useCommand';

/** A bot's token: whether it still works, and a way to revoke it, confirmed first since it can't be undone. */
function BotToken({ account }: { account: AccountSummary }) {
  const client = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const send = useCallback(
    async (accountId: string, key: string) => {
      const revoked = await api.revokeBotToken(accountId, key);
      void client.invalidateQueries({ queryKey: ['accounts'] });
      return revoked;
    },
    [client],
  );
  const command = useCommand(send);

  if (account.token === 'REVOKED') {
    return <span className="muted">Revoked</span>;
  }
  return (
    <div className="token-cell">
      <span>Active</span>
      {confirming ? (
        <>
          <button
            type="button"
            className="primary danger"
            aria-label={`Confirm revoking ${account.username}'s token`}
            onClick={() => {
              setConfirming(false);
              void command.run(account.accountId);
            }}
          >
            Confirm revoke
          </button>
          <button type="button" className="quiet" onClick={() => setConfirming(false)}>
            Keep
          </button>
        </>
      ) : (
        <button
          type="button"
          className="quiet"
          aria-label={`Revoke ${account.username}'s token`}
          disabled={command.outcome.kind === 'sending'}
          onClick={() => setConfirming(true)}
        >
          Revoke
        </button>
      )}
      <CommandStatus
        outcome={command.outcome}
        done={(result) => (result.revoked > 0 ? 'Revoked: its next request is refused.' : 'Already revoked.')}
        onRetry={() => void command.retry()}
      />
    </div>
  );
}

const ACCOUNT_COLUMNS: readonly Column<AccountSummary>[] = [
  {
    key: 'account',
    header: 'Account',
    rowHeader: true,
    cell: (account) => (
      <>
        {account.username}{' '}
        {account.kind === 'BOT' && (
          <span className="tag">{account.strategy === null ? 'bot' : `${strategyTag(account.strategy)} bot`}</span>
        )}
      </>
    ),
    sortValue: (account) => account.username,
  },
  {
    key: 'cash',
    header: 'Starting cash',
    numeric: true,
    cell: (account) => groupDigits(account.cash),
    sortValue: (account) => toCents(account.cash),
  },
  {
    key: 'shares',
    header: 'Starting shares',
    cell: (account) =>
      account.holdings.length === 0
        ? '—'
        : account.holdings.map((held) => `${held.symbol} ${groupInteger(held.quantity)}`).join(' · '),
  },
  { key: 'token', header: 'Token', cell: (account) => (account.kind === 'BOT' ? <BotToken account={account} /> : '—') },
];

/** Funded accounts, traders and bots, with what they started with and, during setup, a form to fund a trader. */
export function AccountSetup({ instruments, editable }: { instruments: readonly Instrument[]; editable: boolean }) {
  const client = useQueryClient();
  const accounts = usePagedList({
    table: 'accounts',
    queryKey: ['accounts'],
    fetch: async (cursor, { size, order }) => {
      const page = await api.accounts(cursor, size, order);
      return { rows: page.accounts, nextCursor: page.nextCursor };
    },
    defaults: { size: 25, order: 'oldest' },
  });
  const [username, setUsername] = useState('');
  const [cash, setCash] = useState(DEFAULT_CASH);
  const [shares, setShares] = useState<ShareInputs>({});
  const send = useCallback(async (request: AccountRequest, key: string) => {
    const created = await api.provisionAccount(request, key);
    setUsername('');
    void client.invalidateQueries({ queryKey: ['accounts'] });
    return created;
  }, [client]);
  const command = useCommand(send);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void command.run({ username: username.trim(), cash: cash.trim(), holdings: holdingsFrom(instruments, shares) });
  };

  return (
    <section className="panel" aria-labelledby="accounts-title">
      <h2 id="accounts-title">Accounts</h2>
      <PagedTable
        label="Accounts"
        list={accounts}
        columns={ACCOUNT_COLUMNS}
        rowKey={(account) => account.accountId}
        empty="No accounts yet."
        noun="accounts"
      />
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
            <ShareFields instruments={instruments} shares={shares} onChange={setShares} />
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
