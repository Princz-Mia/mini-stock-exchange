import { useQuery } from '@tanstack/react-query';

import { api, pollEvery } from '../api/client';
import { AccountSetup } from './AccountSetup';
import { BotRuns } from './BotRuns';
import { BotSetup } from './BotSetup';
import { HealthPanel } from './HealthPanel';
import { InstrumentSetup } from './InstrumentSetup';
import { MarketControl } from './MarketControl';

/**
 * The operator's screen: the authoritative trading phase with its controls and, once open, how trading is keeping
 * up; bot runs, and the SETUP editor for
 * instruments, bots and endowments. Once the market opens, bot runs come first. Everything shown is what the exchange committed, refetched after each command and every five
 * seconds.
 */
export function OperatorPage() {
  const market = useQuery({ queryKey: ['market'], queryFn: api.market, refetchInterval: pollEvery(5_000) });
  const instruments = useQuery({
    queryKey: ['instruments'],
    queryFn: api.instruments,
    refetchInterval: pollEvery(5_000),
  });

  if (market.isPending) {
    return <p className="page-status">Loading the market…</p>;
  }
  // Only when there is nothing to show: a failed poll after a good read keeps the page, and every form's unconfirmed
  // command with its key and Retry, on screen with the last known state.
  if (market.data === undefined) {
    return (
      <p role="alert" className="page-status">
        The exchange is not reachable right now.
      </p>
    );
  }
  const listed = instruments.data?.instruments ?? [];
  const editable = market.data.state === 'NOT_SET_UP' || market.data.state === 'SETUP';

  return (
    <div className="operator">
      <div className="operator-side">
        {market.isError && (
          <p role="alert" className="form-error">
            The exchange is not answering, so the market state shown may be out of date.
          </p>
        )}
        <MarketControl market={market.data} instruments={listed.length} />
        {!editable && (
          <HealthPanel
            instruments={listed}
            feed={
              instruments.isError
                ? { kind: 'unreachable' }
                : instruments.data === undefined
                  ? { kind: 'checking' }
                  : { kind: 'read', feed: instruments.data.feed }
            }
          />
        )}
      </div>
      <div className="operator-setup">
        {instruments.isError && (
          <p role="alert" className="form-error">
            Market data is not reachable, so the instrument list may be incomplete.
          </p>
        )}
        {!editable && <BotRuns market={market.data.state} instruments={listed} />}
        <InstrumentSetup instruments={listed} editable={editable} />
        <BotSetup instruments={listed} editable={editable} />
        <AccountSetup instruments={listed} editable={editable} />
        {editable && <BotRuns market={market.data.state} instruments={listed} />}
      </div>
    </div>
  );
}
