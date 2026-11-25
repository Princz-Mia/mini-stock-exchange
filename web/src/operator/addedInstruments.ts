import { type QueryClient, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect } from 'react';

import type { InstrumentCreated } from '../api/types';

/**
 * How often the setup list is read while an instrument the exchange has added is not in it yet. The list comes from
 * Market Data, which has it a few tens of milliseconds after the exchange answers, so a read straight after the answer
 * often misses it, and the usual five seconds would then pass before the next.
 */
export const WAITING_POLL_MS = 400;

/** How long, from the exchange's answer, the list is read that often before it goes back to its usual pace. */
export const WAITING_FAST_FOR_MS = 20_000;

/**
 * An instrument the exchange answered as added, as its answer gave it, until Market Data lists it. It is shown as
 * added and waiting, labelled so: the exchange's own answer, never a guess at what Market Data will list.
 */
export interface AddedInstrument {
  instrumentId: string;
  symbol: string;
  displayName: string | null;
  referencePrice: string;
  /**
   * The simulation it was added to: the one on screen when it was sent, or null when there was none, as the add then
   * created the market. That is the next simulation the exchange names, and it is written in then.
   */
  scope: string | null;
  /** When the answer came, on the browser's clock. */
  answeredAt: number;
}

const ADDED = ['instruments-added'] as const;

const NONE: readonly AddedInstrument[] = [];

/** Every added instrument still waiting, kept beside the cached queries. */
export function addedInstruments(client: QueryClient): readonly AddedInstrument[] {
  return client.getQueryData<readonly AddedInstrument[]>(ADDED) ?? NONE;
}

/** Those of the simulation on screen that the list does not have yet. */
export function waitingFor(
  added: readonly AddedInstrument[],
  simulation: string | null,
  listed: readonly { instrumentId: string }[],
): AddedInstrument[] {
  const ids = new Set(listed.map((instrument) => instrument.instrumentId));
  return added.filter((each) => (each.scope === null || each.scope === simulation) && !ids.has(each.instrumentId));
}

/** Whether Market Data has had longer than usual to list it: the list is then read at its usual pace again. */
export function takingLonger(added: AddedInstrument, now: number): boolean {
  return now - added.answeredAt >= WAITING_FAST_FOR_MS;
}

/** Whether the list is to be read quickly: some instrument has been waiting for it for less than the time allowed. */
export function readQuickly(waiting: readonly AddedInstrument[], now: number): boolean {
  return waiting.some((each) => !takingLonger(each, now));
}

/**
 * The instruments the exchange has added in this simulation and Market Data does not list yet, and a way to record
 * one from its answer. They are kept while the operator stays signed in, so leaving the page and coming back still
 * shows them; one is let go of as soon as the list has it.
 */
export function useAddedInstruments(simulation: string | null, listed: readonly { instrumentId: string }[]) {
  const client = useQueryClient();
  const { data: added } = useQuery<readonly AddedInstrument[]>({
    queryKey: ADDED,
    // Never fetched: written from the exchange's answers only.
    queryFn: () => addedInstruments(client),
    enabled: false,
    initialData: NONE,
    staleTime: Infinity,
    gcTime: Infinity,
  });

  // An add sent when there was no market created the one the exchange now names.
  const unscoped = simulation !== null && added.some((each) => each.scope === null);
  useEffect(() => {
    if (unscoped) {
      client.setQueryData<readonly AddedInstrument[]>(ADDED, (before = NONE) =>
        before.map((each) => (each.scope === null ? { ...each, scope: simulation } : each)),
      );
    }
  }, [client, unscoped, simulation]);

  // Listed now: Market Data has it, and the list shows it from here on.
  const ids = listed.map((instrument) => instrument.instrumentId);
  const arrived = added.some((each) => ids.includes(each.instrumentId));
  const listedKey = ids.join(',');
  useEffect(() => {
    if (arrived) {
      const now = new Set(listedKey.split(','));
      client.setQueryData<readonly AddedInstrument[]>(ADDED, (before = NONE) =>
        before.filter((each) => !now.has(each.instrumentId)),
      );
    }
  }, [client, arrived, listedKey]);

  const add = useCallback(
    (created: InstrumentCreated, scope: string | null) =>
      client.setQueryData<readonly AddedInstrument[]>(ADDED, (before = NONE) => [
        ...before.filter((each) => each.instrumentId !== created.instrumentId),
        {
          instrumentId: created.instrumentId,
          symbol: created.symbol,
          displayName: created.displayName,
          referencePrice: created.referencePrice,
          scope,
          answeredAt: Date.now(),
        },
      ]),
    [client],
  );

  return { waiting: waitingFor(added, simulation, listed), add };
}
