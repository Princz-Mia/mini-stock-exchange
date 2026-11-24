import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLayoutEffect, useState } from 'react';

import { api, pollEvery } from '../api/client';
import type { Feed, Instrument, Instruments, MarketStatus } from '../api/types';

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
 * - Market Data's own current simulation when the status could not be read at all. The exchange is down then, but
 *   public data stays readable, and the list is asked again by id once the status is read.
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
  const client = useQueryClient();
  const simulation = listedSimulation(market);
  const list = useQuery({
    queryKey: ['instruments', simulation ?? null],
    queryFn: () => api.instruments(simulation === MARKET_DATA_CURRENT ? undefined : (simulation ?? undefined)),
    enabled: simulation !== undefined && simulation !== null,
    // A page that had to start from Market Data's own current simulation, the status being unreadable, goes on from
    // that list when the status then names the very same simulation: the list is asked again by id, but nothing on
    // screen is taken down to wait for it, such as a ticket holding an order whose outcome is not known. A list of
    // another simulation is never carried over.
    initialData: () => {
      const fallback = client.getQueryData<Instruments>(['instruments', MARKET_DATA_CURRENT]);
      return typeof simulation === 'string' && fallback?.simulationId === simulation ? fallback : undefined;
    },
    initialDataUpdatedAt: () => client.getQueryState(['instruments', MARKET_DATA_CURRENT])?.dataUpdatedAt,
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

/** Stands for "no simulation" where one is compared with another. */
const NO_SIMULATION = '';

/**
 * Follows the market on screen as it becomes another one: when it closes, or when the exchange names another
 * simulation. Whoever closed it or started the next, here or elsewhere, what the page shows of the old state would
 * otherwise stay until each query's own next poll, or for good where there is none.
 *
 * - A close: the given queries are read again, and what they showed stays on screen meanwhile. It is the same market.
 * - Another simulation: the given queries are emptied before they are read again, so nothing of the simulation before
 *   is shown as the new one's while its own is on the way. The number returned goes up by one, for a page to key what
 *   it draws by: everything typed, answered or paged through in the simulation before goes with it.
 *
 * The first simulation of a market that had none is no other market: the page that created it goes on. Nothing is
 * read again on the first status either, as the page's queries are being read for the first time then.
 */
export function useRefreshOnMarketChange(market: MarketStatus | undefined, queryKeys: readonly (readonly string[])[]): number {
  const client = useQueryClient();
  const simulation = market === undefined ? undefined : (market.simulationId ?? NO_SIMULATION);
  const closed = market?.state === 'CLOSED';
  const [shown, setShown] = useState(simulation === undefined ? undefined : { simulation, closed, generation: 0 });
  useLayoutEffect(() => {
    if (simulation === undefined || (shown !== undefined && shown.simulation === simulation && shown.closed === closed)) {
      return;
    }
    const another = shown !== undefined && shown.simulation !== NO_SIMULATION && shown.simulation !== simulation;
    if (shown !== undefined) {
      for (const queryKey of queryKeys) {
        void (another ? client.resetQueries({ queryKey }) : client.invalidateQueries({ queryKey }));
      }
    }
    // A layout effect, so that the queries are emptied and the page keyed anew before the browser paints: no frame
    // shows the new market's page over the lists of the one before.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- the render it causes is the point, before paint
    setShown({ simulation, closed, generation: (shown?.generation ?? 0) + (another ? 1 : 0) });
  }, [client, simulation, closed, shown, queryKeys]);
  return shown?.generation ?? 0;
}
