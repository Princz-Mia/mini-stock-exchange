import { useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { AccountRequest, AccountSummary, Instrument, TraderSummary } from '../api/types';
import { type Column, PagedTable } from '../app/PagedTable';
import { usePagedList } from '../app/paging';
import { groupDigits, groupInteger } from '../format';
import { toCents } from '../terminal/money';
import { AddTraderForm } from './AddTraderForm';
import { CommandStatus } from './CommandStatus';
import { EXAMPLE_CASH, holdingsFrom, ShareFields, type ShareInputs } from './Endowment';
import { refusedFields, refusedHoldings, useFocusOnRefusal } from './refusals';
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
        action="Revoking the token"
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

/**
 * Trader logins still without an account in this market, a page at a time, each with Fund, which picks it in the
 * funding form below. Logins have no limit, so the list pages rather than offering every name in a menu.
 */
function UnfundedTraders({ onFund, busy }: { onFund: (username: string) => void; busy: boolean }) {
  const traders = usePagedList({
    table: 'traders',
    queryKey: ['traders'],
    fetch: async (cursor, { size, order }, read) => {
      const page = await api.traders(cursor, size, order, false, read);
      return { rows: page.traders, nextCursor: page.nextCursor };
    },
    defaults: { size: 10, order: 'oldest' },
  });
  const columns: readonly Column<TraderSummary>[] = [
    { key: 'username', header: 'Trader', rowHeader: true, cell: (trader) => trader.username },
    {
      key: 'fund',
      header: <span className="visually-hidden">Actions</span>,
      cell: (trader) => (
        <button type="button" className="quiet" onClick={() => onFund(trader.username)} disabled={busy}>
          Fund <span className="visually-hidden">{trader.username}</span>
        </button>
      ),
    },
  ];
  return (
    <PagedTable
      label="Traders without an account"
      list={traders}
      columns={columns}
      rowKey={(trader) => trader.username}
      empty="Every trader has an account."
      noun="traders"
    />
  );
}

/**
 * Funded accounts, traders and bots, with what they started with and, during setup, forms to add a trader and to
 * fund one that has no account yet.
 */
export function AccountSetup({
  instruments,
  editable,
  closed = false,
}: {
  instruments: readonly Instrument[];
  editable: boolean;
  /** The market has closed: the accounts are listed with what they started with, and nothing more is granted. */
  closed?: boolean;
}) {
  const client = useQueryClient();
  const accounts = usePagedList({
    table: 'accounts',
    queryKey: ['accounts'],
    fetch: async (cursor, { size, order }, read) => {
      const page = await api.accounts(cursor, size, order, read);
      return { rows: page.accounts, nextCursor: page.nextCursor };
    },
    defaults: { size: 25, order: 'oldest' },
  });
  const [username, setUsername] = useState('');
  const [cash, setCash] = useState('');
  const [shares, setShares] = useState<ShareInputs>({});
  const send = useCallback(async (request: AccountRequest, key: string) => {
    const created = await api.provisionAccount(request, key);
    setUsername('');
    void client.invalidateQueries({ queryKey: ['accounts'] });
    void client.invalidateQueries({ queryKey: ['traders'] });
    return created;
  }, [client]);
  const command = useCommand(send);
  const cashField = useRef<HTMLInputElement>(null);
  // Fund in the list picks the trader here, and moves on to their cash. While a funding is sending, or waits for a
  // retry under its key, the form shows the trader being funded, so Fund waits too.
  const fundingBusy = command.outcome.kind === 'sending' || command.unresolved;
  const fund = (trader: string) => {
    if (fundingBusy) {
      return;
    }
    setUsername(trader);
    cashField.current?.focus();
  };
  const form = useRef<HTMLFormElement>(null);
  const outcomeId = useId();
  // The holdings last sent, which a refusal's fields point into.
  const [sent, setSent] = useState<readonly { instrumentId: string }[]>([]);
  const invalid = refusedFields(command.outcome);
  const invalidShares = refusedHoldings(invalid, sent);
  useFocusOnRefusal(command.outcome.kind, form);
  const marked = (field: string) => ({
    'aria-invalid': invalid.has(field) || undefined,
    'aria-describedby': invalid.has(field) ? outcomeId : undefined,
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const holdings = holdingsFrom(instruments, shares);
    setSent(holdings);
    void command.run({ username: username.trim(), cash: cash.trim(), holdings });
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
      {editable && (
        <>
          <h3>Add a trader</h3>
          <AddTraderForm instruments={instruments} />
          <h3>Traders without an account</h3>
          <UnfundedTraders onFund={fund} busy={fundingBusy} />
        </>
      )}
      {editable ? (
        <form ref={form} onSubmit={submit} className="setup-form" aria-label="Fund a trader">
          <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
            <div className="field-row">
              <label>
                Trader's username
                <input
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="off"
                  {...marked('username')}
                />
              </label>
              <label>
                Cash
                <input
                  ref={cashField}
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
              Pick a trader without an account above, or type their username, then enter the cash and the shares
              of each instrument they start with; a blank or 0 gives none of that instrument. Each trader is funded
              once.
            </p>
            <button type="submit" className="primary">
              Fund account
            </button>
          </fieldset>
        </form>
      ) : (
        <p className="muted">
          {closed
            ? 'The market has closed, so no trader can be added or funded. The accounts stay listed with what they started with.'
            : 'Endowments are frozen once the market opens; nothing is granted after that.'}
        </p>
      )}
      <div id={outcomeId}>
        <CommandStatus
          outcome={command.outcome}
          action="Funding the account"
          done={(created) => `Funded ${created.username} with ${groupDigits(created.cash)}.`}
          onRetry={() => void command.retry()}
        />
      </div>
    </section>
  );
}
