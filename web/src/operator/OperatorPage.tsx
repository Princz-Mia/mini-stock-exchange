import { pollEvery } from '../api/client';
import { useInstruments, useMarketStatus, useRefreshOnMarketChange } from '../app/market';
import { utcDateTime } from '../format';
import { AccountSetup } from './AccountSetup';
import { BotRuns } from './BotRuns';
import { BotSetup } from './BotSetup';
import { HealthPanel } from './HealthPanel';
import { InstrumentSetup } from './InstrumentSetup';
import { MarketControl } from './MarketControl';

/**
 * What this page shows of one simulation and no other. All of it is read again when the market closes, whoever closed
 * it, and when the exchange names another simulation: a close ends the runs and revokes the bots' tokens, and each of
 * these lists would otherwise show the market as it was until its own next poll, or for good where it has none.
 */
const PER_SIMULATION = [['instruments'], ['bot-runs'], ['bots'], ['accounts'], ['traders'], ['admin-health']] as const;

/**
 * The operator's screen: the authoritative trading phase with its controls and, once open, how trading is keeping
 * up; bot runs, and the SETUP editor for
 * instruments, bots and endowments. Once the market opens, bot runs come first. A market that has closed is shown as
 * ended: what it left is listed read-only, and nothing is offered that a closed market would refuse. Everything shown
 * is what the exchange committed, refetched after each command and every five seconds.
 */
export function OperatorPage() {
  const market = useMarketStatus();
  useRefreshOnMarketChange(market.data, PER_SIMULATION);
  // This market's instruments only: the list is asked for by the simulation the status names.
  const instruments = useInstruments(market, { refetchInterval: pollEvery(5_000) });

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
  const listed = instruments.instruments;
  const editable = market.data.state === 'NOT_SET_UP' || market.data.state === 'SETUP';
  const closed = market.data.state === 'CLOSED';

  return (
    <div className="operator">
      <div className="operator-side">
        {market.isError && (
          <p role="alert" className="form-error">
            The exchange is not answering, so the market state shown may be out of date.
          </p>
        )}
        <MarketControl market={market.data} instruments={instruments.isPending ? undefined : listed.length} />
        {!editable && (
          <HealthPanel
            instruments={listed}
            feed={
              instruments.isError
                ? { kind: 'unreachable' }
                : instruments.feed === undefined
                  ? { kind: 'checking' }
                  : { kind: 'read', feed: instruments.feed }
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
        {closed && <Ended closedAt={market.data.closedAt} />}
        {!editable && <BotRuns market={market.data.state} instruments={listed} />}
        <InstrumentSetup instruments={listed} editable={editable} closed={closed} />
        <BotSetup instruments={listed} editable={editable} closed={closed} />
        <AccountSetup instruments={listed} editable={editable} closed={closed} />
        {editable && <BotRuns market={market.data.state} instruments={listed} />}
      </div>
    </div>
  );
}

/**
 * First on the page of a market that has closed: that it has ended and when, what the close did, and what is still
 * here to read. The panels under it say the same of themselves in a line each, in place of their forms.
 */
function Ended({ closedAt }: { closedAt: string | null }) {
  return (
    <section className="panel ended" aria-labelledby="ended-title">
      <h2 id="ended-title">This market has ended</h2>
      <p>
        It closed{closedAt === null ? '' : ` on ${utcDateTime(closedAt)}`}. Trading is over for good: every order that
        still rested was cancelled and its hold released, every bot run ended, and every bot&apos;s token stopped
        working.
      </p>
      <p className="muted">
        Nothing was deleted. The bot runs and their comparison, the instruments, the bots and the accounts stay below
        to read, and traders keep their portfolios and their history. Nothing here can be changed any more.
      </p>
    </section>
  );
}
