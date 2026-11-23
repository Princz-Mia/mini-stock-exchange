import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useId, useRef, useState } from 'react';

import { api } from '../api/client';
import type { BotsRequest, BotStrategy, Instrument } from '../api/types';
import { groupInteger } from '../format';
import { CommandStatus } from './CommandStatus';
import { EXAMPLE_CASH, holdingsFrom, ShareFields, type ShareInputs } from './Endowment';
import { refusedFields, refusedHoldings, useFocusOnRefusal } from './refusals';
import { DEFAULT_STRATEGY, populationText, STRATEGIES, strategyNoun } from './strategies';
import { useCommand } from './useCommand';

/**
 * The bot population by strategy and, during setup, a form to create more. The population has no limit; one command
 * makes at most a batch. Every bot in one command gets the same strategy and endowment, and its own token, which goes
 * to the simulator and is never shown here.
 */
export function BotSetup({
  instruments,
  editable,
  closed = false,
}: {
  instruments: readonly Instrument[];
  editable: boolean;
  /** The market has closed: its bots are listed, and trade no more. */
  closed?: boolean;
}) {
  const client = useQueryClient();
  const population = useQuery({ queryKey: ['bots'], queryFn: api.botPopulation });
  const [count, setCount] = useState('');
  const [strategy, setStrategy] = useState<BotStrategy>(DEFAULT_STRATEGY);
  const [cash, setCash] = useState('');
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
    void command.run({ count: Number(count.trim()), cash: cash.trim(), holdings, strategy });
  };

  const bots = population.data;

  return (
    <section className="panel" aria-labelledby="bots-title">
      <h2 id="bots-title">Bots</h2>
      {population.isError ? (
        <p role="alert" className="form-error">
          The bot population could not be loaded.
        </p>
      ) : bots === undefined ? (
        <p className="muted">Loading…</p>
      ) : (
        <div className="population">
          <p>
            {bots.population === 0
              ? 'No bots yet'
              : `${groupInteger(bots.population)} ${bots.population === 1 ? 'bot' : 'bots'}`}
          </p>
          {bots.population > 0 && <p className="muted">{populationText(bots.byStrategy)}</p>}
        </div>
      )}
      {editable ? (
        <form ref={form} onSubmit={submit} className="setup-form" aria-label="Create bots">
          <fieldset disabled={command.outcome.kind === 'sending' || command.unresolved}>
            <div className="field-row">
              <label>
                Number of bots
                <input
                  value={count}
                  onChange={(event) => setCount(event.target.value)}
                  inputMode="numeric"
                  placeholder="e.g. 10"
                  {...marked('count')}
                />
              </label>
              <label>
                Cash each
                <input
                  value={cash}
                  onChange={(event) => setCash(event.target.value)}
                  inputMode="decimal"
                  placeholder={`e.g. ${EXAMPLE_CASH}`}
                  {...marked('cash')}
                />
              </label>
            </div>
            <fieldset className="choices strategies">
              <legend>Strategy</legend>
              {STRATEGIES.map((option) => (
                <div key={option.value} className="strategy">
                  <label>
                    <input
                      type="radio"
                      name="bot-strategy"
                      value={option.value}
                      checked={strategy === option.value}
                      onChange={() => setStrategy(option.value)}
                      aria-describedby={`strategy-${option.value}`}
                    />
                    {option.label}
                  </label>
                  <p id={`strategy-${option.value}`} className="muted">
                    {option.description}
                  </p>
                </div>
              ))}
            </fieldset>
            <ShareFields
              instruments={instruments}
              shares={shares}
              onChange={setShares}
              invalid={invalidShares}
              describedBy={outcomeId}
            />
            <p className="muted hint">
              {bots !== undefined && `Up to ${groupInteger(bots.maxPerCommand)} at a time, as many batches as you like. `}
              Each bot in a batch gets the same strategy and endowment, and its own token for the simulator; a
              blank or 0 gives none of that instrument. Create a batch of 1 to give one bot its own.
            </p>
            <button type="submit" className="primary">
              Create bots
            </button>
          </fieldset>
        </form>
      ) : (
        <p className="muted">
          {closed
            ? "The market has closed, so no bot can be created. These bots trade no more: the close revoked every bot's token."
            : 'Bots are created before the market opens; the population is fixed after that.'}
        </p>
      )}
      <div id={outcomeId}>
        <CommandStatus
          outcome={command.outcome}
          done={(created) =>
            `Created ${created.firstBot} to ${created.lastBot}` +
            ` as ${strategyNoun(created.strategy, created.created)}` +
            `. ${groupInteger(created.population)} bots in all.`
          }
          onRetry={() => void command.retry()}
        />
      </div>
    </section>
  );
}
