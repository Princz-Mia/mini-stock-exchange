import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useState } from 'react';

import { api } from '../api/client';
import type { BotsRequest, Instrument } from '../api/types';
import { groupInteger } from '../format';
import { CommandStatus } from './CommandStatus';
import { DEFAULT_CASH, holdingsFrom, ShareFields, type ShareInputs } from './Endowment';
import { useCommand } from './useCommand';

export const DEFAULT_BOTS = '10';

/**
 * The bot population and, during setup, a form to create more. Every bot in one command gets the same endowment and
 * its own token, which goes to the simulator and is never shown here.
 */
export function BotSetup({ instruments, editable }: { instruments: readonly Instrument[]; editable: boolean }) {
  const client = useQueryClient();
  const population = useQuery({ queryKey: ['bots'], queryFn: api.botPopulation });
  const [count, setCount] = useState(DEFAULT_BOTS);
  const [cash, setCash] = useState(DEFAULT_CASH);
  const [shares, setShares] = useState<ShareInputs>({});
  const send = useCallback(
    async (request: BotsRequest, key: string) => {
      const created = await api.provisionBots(request, key);
      void client.invalidateQueries({ queryKey: ['bots'] });
      void client.invalidateQueries({ queryKey: ['accounts'] });
      return created;
    },
    [client],
  );
  const command = useCommand(send);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void command.run({ count: Number(count.trim()), cash: cash.trim(), holdings: holdingsFrom(instruments, shares) });
  };

  const limits = population.data;
  const remaining = limits === undefined ? undefined : Math.max(limits.maxPopulation - limits.population, 0);
  const full = remaining === 0;

  return (
    <section className="panel" aria-labelledby="bots-title">
      <h2 id="bots-title">Bots</h2>
      {population.isError ? (
        <p role="alert" className="form-error">
          The bot population could not be loaded.
        </p>
      ) : limits === undefined ? (
        <p className="muted">Loading…</p>
      ) : (
        <div className="population">
          <p id="bot-population">
            {groupInteger(limits.population)} of {groupInteger(limits.maxPopulation)} bots
          </p>
          <meter min={0} max={limits.maxPopulation} value={limits.population} aria-labelledby="bot-population" />
        </div>
      )}
      {editable ? (
        <form onSubmit={submit} className="setup-form" aria-label="Create bots">
          <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
            <div className="field-row">
              <label>
                Number of bots
                <input value={count} onChange={(event) => setCount(event.target.value)} inputMode="numeric" />
              </label>
              <label>
                Cash each
                <input value={cash} onChange={(event) => setCash(event.target.value)} inputMode="decimal" />
              </label>
            </div>
            <ShareFields instruments={instruments} shares={shares} onChange={setShares} />
            <p className="muted hint">
              {limits !== undefined &&
                `Up to ${groupInteger(limits.maxPerCommand)} at a time, ${groupInteger(remaining ?? 0)} more in all. `}
              Each bot gets the same endowment and its own token for the simulator.
            </p>
            {full && (
              <p id="bots-full" className="muted">
                The population is at its limit; no more bots can be created.
              </p>
            )}
            <button type="submit" className="primary" disabled={full} aria-describedby={full ? 'bots-full' : undefined}>
              Create bots
            </button>
          </fieldset>
        </form>
      ) : (
        <p className="muted">Bots are created before the market opens; the population is fixed after that.</p>
      )}
      <CommandStatus
        outcome={command.outcome}
        done={(created) =>
          `Created ${created.firstBot} to ${created.lastBot}. ${groupInteger(created.population)} bots in all.`
        }
        onRetry={() => void command.retry()}
      />
    </section>
  );
}
