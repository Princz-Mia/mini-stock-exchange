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

/** One way to narrow a list: the API's query parameter, its label, and the values to choose from. */
export interface Filter {
  name: string;
  label: string;
  options: readonly { value: string; label: string }[];
  /**
   * The options are every value the server takes, such as the two sides. A value from the address that is not one of
   * them is dropped, as an unknown page size is. Options loaded later, such as symbols, are not complete.
   */
  complete?: boolean;
}

/** The filters in use, by parameter name; a filter not in use is absent. */
export type FilterValues = Readonly<Record<string, string>>;

export interface Paging {
  size: PageSize;
  order: ListOrder;
  filters: FilterValues;
}

/** Where a filter's value lives in the address: `executions` and `side` give `executionsSide`. */
function filterParam(table: string, name: string): string {
  return `${table}${name.charAt(0).toUpperCase()}${name.slice(1)}`;
}

/**
 * A table's page size, order and filters live in the address, under the table's name, such as `?executionsSize=25`,
 * `?executionsOrder=oldest` and `?executionsSide=BUY`, so a reload keeps them. Defaults are left out, and anything
 * unknown is ignored. A filter's value is passed on as it is unless its options are complete; the server checks it.
 */
export function readPaging(
  params: URLSearchParams,
  table: string,
  defaults: Omit<Paging, 'filters'>,
  filters: readonly Filter[] = [],
): Paging {
  const size = Number(params.get(`${table}Size`));
  const order = params.get(`${table}Order`);
  const values: Record<string, string> = {};
  for (const filter of filters) {
    const value = params.get(filterParam(table, filter.name));
    const known = filter.complete !== true || filter.options.some((option) => option.value === value);
    if (value !== null && value !== '' && known) {
      values[filter.name] = value;
    }
  }
  return {
    size: (PAGE_SIZES as readonly number[]).includes(size) ? (size as PageSize) : defaults.size,
    order: order === 'newest' || order === 'oldest' ? order : defaults.order,
    filters: values,
  };
}

/** The filters in a fixed order, as text, so equal filters give equal keys. */
function filterKey(filters: FilterValues): string {
  return Object.keys(filters)
    .sort()
    .map((name) => `${name}=${filters[name]}`)
    .join('&');
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
  /** The filters this list offers. */
  filters: readonly Filter[];
  /** Sets one filter, or with null takes it off. */
  setFilter: (name: string, value: string | null) => void;
  clearFilters: () => void;
  /** The page asked for has not loaded yet, and there is nothing to show meanwhile. */
  isPending: boolean;
  /** Some page of this table has loaded before, so its controls are worth keeping on screen while another loads. */
  hasLoaded: boolean;
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
  defaults: Omit<Paging, 'filters'>;
  filters?: readonly Filter[];
  refetchInterval?: (query: { state: { error: unknown } }) => number | false;
}

const NO_FILTERS: readonly Filter[] = [];

export function usePagedList<T>({
  table,
  queryKey,
  fetch,
  defaults,
  filters = NO_FILTERS,
  refetchInterval,
}: Options<T>): PagedList<T> {
  const [params, setParams] = useSearchParams();
  const paging = readPaging(params, table, defaults, filters);
  const filtered = filterKey(paging.filters);
  const settings = `${paging.size}|${paging.order}|${filtered}`;

  // The way back: each page passed after the first, with its cursor. A new page size, order or filter starts again
  // from the first page; this is state that follows its inputs, so it is updated during render rather than in an
  // effect. Cursors belong to the list they came from, and the server refuses them under any other.
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
    queryKey: [...queryKey, paging.size, paging.order, filtered, cursor ?? null],
    // Each page remembers where it starts, so while the next one loads the rows shown keep their own numbering.
    queryFn: async () => ({ ...(await fetch(cursor, paging)), offset: start }),
    // While another page of the same list loads, the page shown stays. A new page size, order or filter is a different
    // list, so its old rows are not shown under the new settings.
    placeholderData: (previous, previousQuery) => {
      const key = previousQuery?.queryKey ?? [];
      const same =
        key[queryKey.length] === paging.size &&
        key[queryKey.length + 1] === paging.order &&
        key[queryKey.length + 2] === filtered;
      return same ? previous : undefined;
    },
    refetchOnWindowFocus: true,
    ...(refetchInterval === undefined ? {} : { refetchInterval }),
  });

  /** Writes names to the address; a value equal to its fallback, or null, is left out. */
  const update = (changes: readonly [name: string, value: string | null, fallback: string | null][]) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        for (const [name, value, fallback] of changes) {
          if (value === null || value === fallback) {
            next.delete(name);
          } else {
            next.set(name, value);
          }
        }
        return next;
      },
      { replace: true },
    );

  const page = query.data;
  const loadingPage = query.isPlaceholderData;
  // State that follows its input: set during render the first time any page arrives.
  const [hasLoaded, setHasLoaded] = useState(false);
  if (!hasLoaded && page !== undefined) {
    setHasLoaded(true);
  }
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
    setSize: (size) => update([[`${table}Size`, String(size), String(defaults.size)]]),
    setOrder: (order) => update([[`${table}Order`, order, defaults.order]]),
    filters,
    setFilter: (name, value) => update([[filterParam(table, name), value === '' ? null : value, null]]),
    clearFilters: () => update(filters.map((filter) => [filterParam(table, filter.name), null, null] as const)),
    isPending: query.isPending,
    hasLoaded,
    isError: query.isError,
    isLoadingPage: loadingPage,
    retry: () => void query.refetch(),
  };
}
