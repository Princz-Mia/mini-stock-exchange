import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';

import { api, pollEvery } from '../api/client';
import type { Feed, Instrument, MarketStatus } from '../api/types';

/** The exchange's own word on the market: its phase and which simulation it is, read again every five seconds. */
export function useMarketStatus() {
  return useQuery({ queryKey: ['market'], queryFn: api.market, refetchInterval: pollEvery(5_000) });
}

/** What a page needs of the market status to know whose instruments to list. */
interface StatusRead {
  data: MarketStatus | undefined;
  isPending: boolean;
}

/** Asked of Market Data when the exchange can't be asked which simulation is current. */
const MARKET_DATA_CURRENT = 'current';

/**
 * Whose instruments to list:
 * - undefined while the status has not been read: nothing is asked yet, so no list of another simulation is shown
 *   for a moment and then replaced;
 * - null when the exchange has no simulation at all: nothing is asked, as there is nothing to list;
 * - the simulation's id once the exchange has named it;
 * - Market Data's own current simulation when the status could not be read at all. The exchange is down then and
 *   nothing can be traded, but public data stays readable, and the list is asked again by id once the status is read.
 */
export function listedSimulation(market: StatusRead): string | null | undefined {
  if (market.data !== undefined) {
    return market.data.simulationId;
  }
  return market.isPending ? undefined : MARKET_DATA_CURRENT;
}

export interface InstrumentList {
  instruments: readonly Instrument[];
  /** How current Market Data says it is; undefined until a list has been read. */
  feed: Feed | undefined;
  /** Still finding out: the status or the list has not been read yet. */
  isPending: boolean;
  /** The last read of the list failed. An earlier list, if any, is still in `instruments`. */
  isError: boolean;
}

/**
 * The instruments of the simulation the exchange says is current. The simulation is part of the query's key, so a
 * change of simulation reads the list again and never shows the one before under the new market's name.
 */
export function useInstruments(market: StatusRead, options: { refetchInterval?: ReturnType<typeof pollEvery> } = {}): InstrumentList {
  const simulation = listedSimulation(market);
  const list = useQuery({
    queryKey: ['instruments', simulation ?? null],
    queryFn: () => api.instruments(simulation === MARKET_DATA_CURRENT ? undefined : (simulation ?? undefined)),
    enabled: simulation !== undefined && simulation !== null,
    ...options,
  });
  if (simulation === undefined) {
    return { instruments: [], feed: undefined, isPending: true, isError: false };
  }
  if (simulation === null) {
    return { instruments: [], feed: undefined, isPending: false, isError: false };
  }
  return {
    instruments: list.data?.instruments ?? [],
    feed: list.data?.feed,
    isPending: list.isPending,
    isError: list.isError,
  };
}

/**
 * Reads the given queries again when the market on screen becomes another one: when it closes, or when the exchange
 * names another simulation. Whoever closed it, here or elsewhere, what the page shows of the old state would otherwise
 * stay until each query's own next poll, or for good where there is none. Nothing is read again on the first status:
 * the page's queries are being read for the first time then.
 */
export function useRefreshOnMarketChange(market: MarketStatus | undefined, queryKeys: readonly (readonly string[])[]) {
  const client = useQueryClient();
  const which = market === undefined ? undefined : `${market.simulationId ?? ''}|${market.state === 'CLOSED'}`;
  const seen = useRef(which);
  useEffect(() => {
    if (which === undefined || which === seen.current) {
      return;
    }
    const first = seen.current === undefined;
    seen.current = which;
    if (!first) {
      for (const queryKey of queryKeys) {
        void client.invalidateQueries({ queryKey });
      }
    }
  }, [client, which, queryKeys]);
}
