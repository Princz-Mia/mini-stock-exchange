import { type QueryClient, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';

import { api, isRefusal, pollEvery } from '../api/client';
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

/** Market Data's own current list, under the key it would have if the status could not be read. */
const CURRENT_LIST = ['instruments', MARKET_DATA_CURRENT] as const;

/** How long that list, once read, is taken as current when the status could not be read. */
const CURRENT_FRESH_MS = 2_000;

/**
 * Whose instruments to list:
 * - undefined while the status has not been read: nothing is shown yet, so no list of another simulation is shown
 *   for a moment and then replaced. Market Data's own current list is asked for meanwhile, and used only once the
 *   status names the simulation it is of;
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
export function useInstruments(
  market: StatusRead,
  options: { refetchInterval?: (query: { state: { data: Instruments | undefined; error: unknown } }) => number | false } = {},
): InstrumentList {
  const client = useQueryClient();
  const simulation = listedSimulation(market);
  // While the status is on its way, Market Data's own current list is asked for at the same time rather than after it,
  // and never shown: it is used below only when the status then names the very simulation it is of.
  useQuery({
    queryKey: CURRENT_LIST,
    queryFn: ({ signal }) => api.instruments(undefined, { signal }),
    enabled: simulation === undefined,
  });
  const list = useQuery({
    queryKey: ['instruments', simulation ?? null],
    queryFn: async ({ signal }) => {
      if (simulation === MARKET_DATA_CURRENT || simulation === undefined || simulation === null) {
        return api.instruments(undefined, { signal });
      }
      // The early read still on its way is waited for rather than asked again, when it is of this simulation.
      const early = client.getQueryCache().find<Instruments>({ queryKey: CURRENT_LIST, exact: true });
      if (early?.state.fetchStatus === 'fetching' && early.promise !== undefined) {
        const read = await early.promise.catch(() => undefined);
        if (read?.simulationId === simulation) {
          return read;
        }
      }
      return api.instruments(simulation, { signal });
    },
    enabled: simulation !== undefined && simulation !== null,
    // When the status then fails, Market Data's own list is the one the early read has just brought: it is not asked
    // for again straight away, only at the page's next poll.
    staleTime: simulation === MARKET_DATA_CURRENT ? CURRENT_FRESH_MS : 0,
    // A page that had to start from Market Data's own current simulation, the status being unreadable, goes on from
    // that list when the status then names the very same simulation: the list is asked again by id, but nothing on
    // screen is taken down to wait for it, such as a ticket holding an order whose outcome is not known. A list of
    // another simulation is never carried over.
    initialData: () => {
      const fallback = client.getQueryData<Instruments>(CURRENT_LIST);
      return typeof simulation === 'string' && fallback?.simulationId === simulation ? fallback : undefined;
    },
    initialDataUpdatedAt: () => client.getQueryState(CURRENT_LIST)?.dataUpdatedAt,
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
 * For each query client, the simulation each per-simulation query was last shown for, by its key. It outlives the page
 * that showed it, as the cached queries do, so a page opened after a change of simulation, from help say, still knows
 * that what is cached for it is of the simulation before: the status it opens on, which the top bar keeps current, is
 * already the new one's.
 */
const lastShown = new WeakMap<QueryClient, Map<string, string>>();

function recall(client: QueryClient, queryKeys: readonly (readonly string[])[]): string | undefined {
  const shown = lastShown.get(client);
  for (const queryKey of queryKeys) {
    const simulation = shown?.get(JSON.stringify(queryKey));
    if (simulation !== undefined) {
      return simulation;
    }
  }
  return undefined;
}

function remember(client: QueryClient, queryKeys: readonly (readonly string[])[], simulation: string) {
  let shown = lastShown.get(client);
  if (shown === undefined) {
    shown = new Map();
    lastShown.set(client, shown);
  }
  for (const queryKey of queryKeys) {
    shown.set(JSON.stringify(queryKey), simulation);
  }
}

interface Shown {
  simulation: string;
  closed: boolean;
  generation: number;
}

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
 * Another simulation is also one the page opens on when its queries were last shown, here or on another page, for the
 * one before. The first simulation of a market that had none is no other market: the page that created it goes on.
 * Nothing is read again on the first status otherwise, as the page's queries are being read for the first time then.
 */
export function useRefreshOnMarketChange(market: MarketStatus | undefined, queryKeys: readonly (readonly string[])[]): number {
  const client = useQueryClient();
  const simulation = market === undefined ? undefined : (market.simulationId ?? NO_SIMULATION);
  const closed = market?.state === 'CLOSED';
  const [shown, setShown] = useState<Shown | undefined>(() =>
    simulation === undefined ? undefined : { simulation: recall(client, queryKeys) ?? simulation, closed, generation: 0 },
  );
  useLayoutEffect(() => {
    if (simulation === undefined) {
      return;
    }
    // A page that had no status yet goes by what its queries were last shown for, if anything.
    const recalled = recall(client, queryKeys);
    const before = shown ?? (recalled === undefined ? undefined : { simulation: recalled, closed, generation: 0 });
    remember(client, queryKeys, simulation);
    if (shown !== undefined && shown.simulation === simulation && shown.closed === closed) {
      return;
    }
    const changed = before !== undefined && (before.simulation !== simulation || before.closed !== closed);
    const another = changed && before.simulation !== NO_SIMULATION && before.simulation !== simulation;
    if (changed) {
      for (const queryKey of queryKeys) {
        void (another ? client.resetQueries({ queryKey }) : client.invalidateQueries({ queryKey }));
      }
    }
    // A layout effect, so that the queries are emptied and the page keyed anew before the browser paints: no frame
    // shows the new market's page over the lists of the one before.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- the render it causes is the point, before paint
    setShown({ simulation, closed, generation: (before?.generation ?? 0) + (another ? 1 : 0) });
  }, [client, simulation, closed, shown, queryKeys]);
  return shown?.generation ?? 0;
}

/**
 * Takes the given parameters out of the address when the page is drawn for another simulation: they name what was the
 * simulation before's, such as its instruments or its runs, and would otherwise be asked for in the new one. False
 * until the address is clear of them, for a page that would ask for them as soon as it is drawn.
 */
export function useForgetInAddress(generation: number, names: readonly string[]): boolean {
  const [params, setParams] = useSearchParams();
  // The generation the address is clear for: state that follows the address, so updated during render.
  const [clearFor, setClearFor] = useState(generation);
  if (clearFor !== generation && !names.some((name) => params.has(name))) {
    setClearFor(generation);
  }
  const clearing = clearFor !== generation;
  useEffect(() => {
    if (clearing) {
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          for (const name of names) {
            next.delete(name);
          }
          return next;
        },
        { replace: true },
      );
    }
  }, [clearing, names, setParams]);
  return !clearing;
}

/**
 * Changes whenever an operator may have created or funded an account since: any command while the market is being set
 * up, which is when accounts are created, its opening, and another simulation. Undefined until the status is read.
 */
export function fundingMoment(market: MarketStatus | undefined): string | undefined {
  if (market === undefined) {
    return undefined;
  }
  const settingUp = market.state === 'NOT_SET_UP' || market.state === 'SETUP';
  return [market.simulationId ?? NO_SIMULATION, market.state, settingUp ? market.commandSequence : ''].join('|');
}

/**
 * A trader refused for having no account in the market stops being asked (`pollEvery`), so an account created for
 * them afterwards would only be seen on coming back to the tab. Those of the given queries the exchange refused are
 * read again at each moment an account may have been created, and at no other: not on every order of an open market.
 */
export function useAskAgainWhenFunded(market: MarketStatus | undefined, queryKeys: readonly (readonly string[])[]) {
  const client = useQueryClient();
  const moment = fundingMoment(market);
  const seen = useRef(moment);
  useEffect(() => {
    if (moment === undefined || moment === seen.current) {
      return;
    }
    const first = seen.current === undefined;
    seen.current = moment;
    if (first) {
      return;
    }
    for (const queryKey of queryKeys) {
      void client.invalidateQueries({ queryKey, predicate: (query) => isRefusal(query.state.error) });
    }
  }, [client, moment, queryKeys]);
}
