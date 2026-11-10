import { type ReactNode, useId, useState } from 'react';

import { groupInteger } from '../format';
import { type ListOrder, PAGE_SIZES, type PageSize, type PagedList } from './paging';

export interface Column<T> {
  key: string;
  header: string;
  cell: (row: T) => ReactNode;
  /** Right-aligned, tabular figures. */
  numeric?: boolean;
  /** The cell names its row for screen readers. */
  rowHeader?: boolean;
  /** What the column sorts by within the page; without it the column does not sort. */
  sortValue?: (row: T) => string | number | bigint;
}

type Sort = { key: string; direction: 'ascending' | 'descending' } | null;

function compare(a: string | number | bigint, b: string | number | bigint): number {
  if (typeof a === 'string' && typeof b === 'string') {
    return a.localeCompare(b);
  }
  return a < b ? -1 : a > b ? 1 : 0;
}

/** The page's rows in the chosen column's order, or as they came. Ties keep the order they came in. */
export function sortedRows<T>(rows: readonly T[], columns: readonly Column<T>[], sort: Sort): readonly T[] {
  const column = sort === null ? undefined : columns.find((each) => each.key === sort.key);
  if (sort === null || column?.sortValue === undefined) {
    return rows;
  }
  const value = column.sortValue;
  const sign = sort.direction === 'ascending' ? 1 : -1;
  return rows
    .map((row, index) => ({ row, index }))
    .sort((a, b) => sign * compare(value(a.row), value(b.row)) || a.index - b.index)
    .map(({ row }) => row);
}

const ORDER_LABELS: Record<ListOrder, string> = { newest: 'Newest first', oldest: 'Oldest first' };

/**
 * One page of a long list, with how many to show at once, which end to start from, Previous and Next, and the rows
 * shown counted out. Sorting by a column applies to the page on screen, and says so; the whole list is in time order.
 */
export function PagedTable<T>({
  label,
  list,
  columns,
  rowKey,
  empty,
  noun,
}: {
  /** The table's accessible name. */
  label: string;
  list: PagedList<T>;
  columns: readonly Column<T>[];
  rowKey: (row: T) => string;
  /** What to say when the list has no rows at all. */
  empty: string;
  /** The rows, in the plural, for messages such as "The executions could not be loaded." */
  noun: string;
}) {
  const id = useId();
  const [sort, setSort] = useState<Sort>(null);
  const rows = sortedRows(list.rows, columns, sort);
  const first = list.offset + 1;
  const last = list.offset + list.rows.length;

  const toggle = (key: string) =>
    setSort((current) =>
      current?.key !== key
        ? { key, direction: 'ascending' }
        : current.direction === 'ascending'
          ? { key, direction: 'descending' }
          : null,
    );

  if (list.isPending) {
    return <p className="muted">Loading…</p>;
  }
  if (!list.isError && list.rows.length === 0 && list.offset === 0) {
    return <p className="muted">{empty}</p>;
  }

  // A page that failed to load still leaves the controls and Previous, so the user can go back or change the view.
  return (
    <div className="paged-table">
      <div className="table-toolbar">
        <label htmlFor={`${id}-order`}>Order</label>
        <select
          id={`${id}-order`}
          value={list.paging.order}
          onChange={(event) => list.setOrder(event.target.value as ListOrder)}
        >
          {(['newest', 'oldest'] as const).map((order) => (
            <option key={order} value={order}>
              {ORDER_LABELS[order]}
            </option>
          ))}
        </select>
        <label htmlFor={`${id}-size`}>Rows per page</label>
        <select
          id={`${id}-size`}
          value={list.paging.size}
          onChange={(event) => list.setSize(Number(event.target.value) as PageSize)}
        >
          {PAGE_SIZES.map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </select>
        {sort !== null && <span className="muted">Column order applies to this page only.</span>}
      </div>
      {list.isError &&
        (list.rows.length === 0 ? (
          <div className="table-problem">
            <p role="alert" className="form-error">
              The {noun} could not be loaded.
            </p>
            <button type="button" className="quiet" onClick={list.retry}>
              Try again
            </button>
          </div>
        ) : (
          <p role="alert" className="form-error">
            The {noun} could not be refreshed; showing the last page loaded.
          </p>
        ))}
      {list.rows.length > 0 && (
      <table aria-label={label} aria-busy={list.isLoadingPage}>
        <thead>
          <tr>
            {columns.map((column) => {
              const sorted = sort?.key === column.key ? sort.direction : undefined;
              return (
                <th
                  key={column.key}
                  scope="col"
                  className={column.numeric === true ? 'num' : undefined}
                  aria-sort={sorted}
                >
                  {column.sortValue === undefined ? (
                    column.header
                  ) : (
                    <button type="button" className="column-sort" onClick={() => toggle(column.key)}>
                      {column.header}
                      <span aria-hidden="true">{sorted === 'ascending' ? ' ▲' : sorted === 'descending' ? ' ▼' : ''}</span>
                    </button>
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)}>
              {columns.map((column) =>
                column.rowHeader === true ? (
                  <th key={column.key} scope="row" className={column.numeric === true ? 'num' : undefined}>
                    {column.cell(row)}
                  </th>
                ) : (
                  <td key={column.key} className={column.numeric === true ? 'num' : undefined}>
                    {column.cell(row)}
                  </td>
                ),
              )}
            </tr>
          ))}
        </tbody>
      </table>
      )}
      <div className="table-footer">
        <span className="muted" role="status">
          {list.rows.length > 0
            ? `Showing ${groupInteger(first)}–${groupInteger(last)}`
            : list.isError
              ? ''
              : 'No more rows.'}
        </span>
        <button type="button" className="quiet" onClick={list.previous} disabled={!list.hasPrevious}>
          Previous
        </button>
        <button type="button" className="quiet" onClick={list.next} disabled={!list.hasNext}>
          Next
        </button>
      </div>
    </div>
  );
}
