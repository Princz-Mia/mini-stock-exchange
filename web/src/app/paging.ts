import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useSearchParams } from 'react-router';

import type { ListOrder } from '../api/client';

/**
 * Long lists, one page at a time. The server pages by keyset cursor, which only goes forward, so the browser keeps the
 * cursors of the pages it has passed: Previous goes back to one of those, Next takes the server's cursor. Only the page
 * on screen is fetched and refreshed, however far the user has gone.
 */

export const PAGE_SIZES = [10, 25, 50, 100] as const;
export type PageSize = (typeof PAGE_SIZES)[number];

export type { ListOrder };

export interface Page<T> {
  rows: readonly T[];
  nextCursor: string | null;
}

export interface Paging {
  size: PageSize;
  order: ListOrder;
}

/**
 * A table's page size and order live in the address, under the table's name, such as `?executionsSize=25` and
 * `?executionsOrder=oldest`, so a reload keeps them. Defaults are left out, and anything unknown is ignored.
 */
export function readPaging(params: URLSearchParams, table: string, defaults: Paging): Paging {
  const size = Number(params.get(`${table}Size`));
  const order = params.get(`${table}Order`);
  return {
    size: (PAGE_SIZES as readonly number[]).includes(size) ? (size as PageSize) : defaults.size,
    order: order === 'newest' || order === 'oldest' ? order : defaults.order,
  };
}

export interface PagedList<T> {
  rows: readonly T[];
  paging: Paging;
  /** Zero-based position of the first row shown, in the whole list. */
  offset: number;
  hasPrevious: boolean;
  hasNext: boolean;
  previous: () => void;
  next: () => void;
  setSize: (size: PageSize) => void;
  setOrder: (order: ListOrder) => void;
  /** No page has loaded yet. */
  isPending: boolean;
  /** The last fetch failed; any rows shown are from before it. */
  isError: boolean;
  /** The rows shown belong to the page before; the one asked for is still loading. */
  isLoadingPage: boolean;
  retry: () => void;
}

/** A page after the first: the cursor that fetches it, and how many rows come before it. */
interface Passed {
  cursor: string;
  offset: number;
}

interface Options<T> {
  /** The name for this table in the address. */
  table: string;
  /** The query key prefix, so invalidating it refreshes the page on screen. */
  queryKey: readonly unknown[];
  fetch: (cursor: string | undefined, paging: Paging) => Promise<Page<T>>;
  defaults: Paging;
  refetchInterval?: (query: { state: { error: unknown } }) => number | false;
}

export function usePagedList<T>({ table, queryKey, fetch, defaults, refetchInterval }: Options<T>): PagedList<T> {
  const [params, setParams] = useSearchParams();
  const paging = readPaging(params, table, defaults);
  const settings = `${paging.size}|${paging.order}`;

  // The way back: each page passed after the first, with its cursor. A new page size or order starts again from the
  // first page; this is state that follows its inputs, so it is updated during render rather than in an effect.
  const [stack, setStack] = useState<{ settings: string; pages: readonly Passed[] }>({ settings, pages: [] });
  let pages = stack.pages;
  if (stack.settings !== settings) {
    pages = [];
    setStack({ settings, pages });
  }
  const here = pages.at(-1);
  const cursor = here?.cursor;
  const start = here?.offset ?? 0;

  const query = useQuery({
    queryKey: [...queryKey, paging.size, paging.order, cursor ?? null],
    // Each page remembers where it starts, so while the next one loads the rows shown keep their own numbering.
    queryFn: async () => ({ ...(await fetch(cursor, paging)), offset: start }),
    // While another page of the same list loads, the page shown stays. A new page size or order is a different list,
    // so its old rows are not shown under the new settings.
    placeholderData: (previous, previousQuery) => {
      const key = previousQuery?.queryKey ?? [];
      return key[queryKey.length] === paging.size && key[queryKey.length + 1] === paging.order ? previous : undefined;
    },
    refetchOnWindowFocus: true,
    ...(refetchInterval === undefined ? {} : { refetchInterval }),
  });

  const update = (name: string, value: string, fallback: string) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (value === fallback) {
          next.delete(name);
        } else {
          next.set(name, value);
        }
        return next;
      },
      { replace: true },
    );

  const page = query.data;
  const loadingPage = query.isPlaceholderData;
  return {
    rows: page?.rows ?? [],
    paging,
    offset: page?.offset ?? start,
    hasPrevious: pages.length > 0,
    hasNext: !loadingPage && page?.nextCursor != null,
    previous: () => setStack({ settings, pages: pages.slice(0, -1) }),
    next: () => {
      if (page?.nextCursor != null && !loadingPage) {
        setStack({ settings, pages: [...pages, { cursor: page.nextCursor, offset: start + page.rows.length }] });
      }
    },
    setSize: (size) => update(`${table}Size`, String(size), String(defaults.size)),
    setOrder: (order) => update(`${table}Order`, order, defaults.order),
    isPending: query.isPending,
    isError: query.isError,
    isLoadingPage: loadingPage,
    retry: () => void query.refetch(),
  };
}
