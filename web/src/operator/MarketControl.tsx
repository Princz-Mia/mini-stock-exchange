import { useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';

import { api } from '../api/client';
import type { MarketStatus } from '../api/types';
import { utcTime } from '../format';
import { CommandStatus } from './CommandStatus';
import { useCommand } from './useCommand';

type Action = 'open' | 'halt' | 'resume';

const STATE_LABELS: Record<MarketStatus['state'], string> = {
  NOT_SET_UP: 'Not set up',
  SETUP: 'Setup',
  OPEN: 'Open',
  HALTED: 'Halted',
};

const DONE: Record<Action, string> = {
  open: 'The market is open.',
  halt: 'Trading is halted. Cancels still work.',
  resume: 'Trading has resumed.',
};

/** Why an action is unavailable right now, or null when it is allowed. */
function blocked(action: Action, state: MarketStatus['state'], instruments: number): string | null {
  switch (action) {
    case 'open':
      if (state === 'NOT_SET_UP') return 'Add an instrument first; that creates the market.';
      if (state !== 'SETUP') return 'The market has already opened. It opens only once.';
      return instruments === 0 ? 'Add at least one instrument first.' : null;
    case 'halt':
      return state === 'OPEN' ? null : 'Only an open market can be halted.';
    case 'resume':
      return state === 'HALTED' ? null : 'Only a halted market can be resumed.';
  }
}

/** The trading phase and the three transitions, each enabled only when the exchange would allow it. */
export function MarketControl({ market, instruments }: { market: MarketStatus; instruments: number }) {
  const client = useQueryClient();
  const send = useCallback(async (action: Action, key: string) => {
    try {
      return { action, transition: await api.controlMarket(action, key) };
    } finally {
      void client.invalidateQueries({ queryKey: ['market'] });
    }
  }, [client]);
  const command = useCommand(send);
  const busy = command.outcome.kind === 'sending';

  const actions: { action: Action; label: string }[] = [
    { action: 'open', label: 'Open market' },
    { action: 'halt', label: 'Halt trading' },
    { action: 'resume', label: 'Resume trading' },
  ];

  return (
    <section className="panel market-control" aria-labelledby="market-title">
      <div className="panel-header">
        <h2 id="market-title">Market</h2>
        <span className={`state-badge state-${market.state.toLowerCase()}`} role="status">
          {STATE_LABELS[market.state]}
        </span>
      </div>
      <dl className="balances">
        <dt>Opened</dt>
        <dd>{market.openedAt === null ? 'Not yet' : utcTime(market.openedAt)}</dd>
        <dt>Last command</dt>
        <dd className="num">{market.commandSequence ?? '—'}</dd>
      </dl>
      <ul className="control-list">
        {actions.map(({ action, label }) => {
          const reason = blocked(action, market.state, instruments);
          const hint = `${action}-hint`;
          return (
            <li key={action}>
              <button
                type="button"
                className={action === 'halt' ? 'primary danger' : 'primary'}
                disabled={reason !== null || busy || command.unresolved}
                aria-describedby={reason === null ? undefined : hint}
                onClick={() => void command.run(action)}
              >
                {label}
              </button>
              {reason !== null && (
                <span id={hint} className="muted">
                  {reason}
                </span>
              )}
            </li>
          );
        })}
      </ul>
      {market.state === 'SETUP' && (
        <p className="muted hint">Opening freezes the instruments and every account's endowment.</p>
      )}
      <CommandStatus outcome={command.outcome} done={(result) => DONE[result.action]} onRetry={() => void command.retry()} />
    </section>
  );
}
