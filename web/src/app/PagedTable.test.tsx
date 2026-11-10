import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';

import { type Column, PagedTable, sortedRows } from './PagedTable';
import { type Paging, usePagedList } from './paging';

interface Row {
  id: number;
  name: string;
  amount: bigint;
}

/** 57 rows, oldest first; names and amounts out of id order, so sorting a page is visible. */
const ROWS: Row[] = Array.from({ length: 57 }, (_, index) => ({
  id: index + 1,
  name: `row ${String((index * 7) % 57).padStart(2, '0')}`,
  amount: BigInt((index * 13) % 57),
}));

const COLUMNS: readonly Column<Row>[] = [
  { key: 'id', header: 'Number', rowHeader: true, cell: (row) => String(row.id), sortValue: (row) => row.id },
  { key: 'name', header: 'Name', cell: (row) => row.name, sortValue: (row) => row.name },
  { key: 'amount', header: 'Amount', numeric: true, cell: (row) => String(row.amount), sortValue: (row) => row.amount },
  { key: 'note', header: 'Note', cell: () => '—' },
];

/** A server for the rows: the cursor is the index to continue from, in the order asked for. */
let calls: { cursor: string | undefined; size: number; order: string }[] = [];
let failing = false;
/** While set, fetches wait for it, so a test can look at the screen while a page is loading. */
let gate: Promise<void> | null = null;

async function fetchRows(cursor: string | undefined, { size, order }: Paging) {
  calls.push({ cursor, size, order });
  if (gate !== null) {
    await gate;
  }
  if (failing) {
    throw new Error('down');
  }
  const all = order === 'newest' ? [...ROWS].reverse() : ROWS;
  const start = cursor === undefined ? 0 : Number(cursor);
  const rows = all.slice(start, start + size);
  return { rows, nextCursor: start + size < all.length ? String(start + size) : null };
}

function Table({ empty = false }: { empty?: boolean }) {
  const list = usePagedList({
    table: 'rows',
    queryKey: ['rows'],
    fetch: empty ? async () => ({ rows: [], nextCursor: null }) : fetchRows,
    defaults: { size: 10, order: 'newest' },
  });
  return (
    <PagedTable label="Rows" list={list} columns={COLUMNS} rowKey={(row) => String(row.id)} empty="Nothing yet." noun="rows" />
  );
}

function Address() {
  return <span data-testid="address">{useLocation().search}</span>;
}

function renderTable(at = '/list', empty = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[at]}>
        <Table empty={empty} />
        <Address />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

const numbers = () =>
  within(screen.getByRole('table', { name: 'Rows' }))
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getByRole('rowheader').textContent);
const showing = () => screen.getByText(/^Showing|^No more/).textContent;
const address = () => screen.getByTestId('address').textContent;

beforeEach(() => {
  calls = [];
  failing = false;
  gate = null;
});

/** Holds every fetch until the returned function is called. */
function hold(): () => void {
  let release = () => undefined as void;
  gate = new Promise<void>((resolve) => {
    release = () => {
      gate = null;
      resolve();
    };
  });
  return () => release();
}

describe('PagedTable', () => {
  it('shows one page at a time, and goes forward and back with the cursors it was given', async () => {
    renderTable();
    await waitFor(() => expect(numbers()).toEqual(['57', '56', '55', '54', '53', '52', '51', '50', '49', '48']));
    expect(showing()).toBe('Showing 1–10');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(numbers()[0]).toBe('47'));
    expect(showing()).toBe('Showing 11–20');
    expect(calls.at(-1)).toEqual({ cursor: '10', size: 10, order: 'newest' });

    for (let page = 0; page < 4; page++) {
      await waitFor(() => expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled());
      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    }
    await waitFor(() => expect(numbers()).toEqual(['7', '6', '5', '4', '3', '2', '1']));
    expect(showing()).toBe('Showing 51–57');
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    await waitFor(() => expect(numbers()[0]).toBe('17'));
    expect(showing()).toBe('Showing 41–50');
  });

  it('refreshes only the page on screen, however many pages were passed', async () => {
    const client = renderTable();
    await waitFor(() => expect(numbers()).toHaveLength(10));
    for (let page = 0; page < 3; page++) {
      await waitFor(() => expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled());
      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    }
    await waitFor(() => expect(showing()).toBe('Showing 31–40'));
    calls = [];

    await act(() => client.invalidateQueries({ queryKey: ['rows'] }));

    expect(calls).toEqual([{ cursor: '30', size: 10, order: 'newest' }]);
  });

  it('starts again from the first page with a new page size, and keeps it in the address', async () => {
    renderTable();
    await waitFor(() => expect(numbers()).toHaveLength(10));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(showing()).toBe('Showing 11–20'));

    fireEvent.change(screen.getByLabelText('Rows per page'), { target: { value: '25' } });

    await waitFor(() => expect(numbers()).toHaveLength(25));
    expect(numbers()[0]).toBe('57');
    expect(showing()).toBe('Showing 1–25');
    expect(calls.at(-1)).toEqual({ cursor: undefined, size: 25, order: 'newest' });
    expect(address()).toBe('?rowsSize=25');
  });

  it('reads either way, starting again from the first page, and leaves defaults out of the address', async () => {
    renderTable();
    await waitFor(() => expect(numbers()).toHaveLength(10));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(showing()).toBe('Showing 11–20'));

    fireEvent.change(screen.getByLabelText('Order'), { target: { value: 'oldest' } });
    await waitFor(() => expect(numbers()[0]).toBe('1'));
    expect(showing()).toBe('Showing 1–10');
    expect(address()).toBe('?rowsOrder=oldest');

    fireEvent.change(screen.getByLabelText('Order'), { target: { value: 'newest' } });
    await waitFor(() => expect(numbers()[0]).toBe('57'));
    expect(address()).toBe('');
  });

  it('takes its page size and order from the address, and ignores what it does not know', async () => {
    renderTable('/list?rowsSize=50&rowsOrder=oldest');
    await waitFor(() => expect(numbers()).toHaveLength(50));
    expect(numbers()[0]).toBe('1');
    expect(screen.getByLabelText('Rows per page')).toHaveValue('50');
  });

  it('falls back to the defaults for values it does not offer', async () => {
    renderTable('/list?rowsSize=7&rowsOrder=sideways');
    await waitFor(() => expect(numbers()).toHaveLength(10));
    expect(numbers()[0]).toBe('57');
  });

  it('sorts the page on screen by a column, says it is this page only, and clears on the third click', async () => {
    renderTable();
    await waitFor(() => expect(numbers()).toHaveLength(10));
    const amount = screen.getByRole('button', { name: 'Amount' });

    fireEvent.click(amount);
    const ascending = numbers();
    expect(ascending).toHaveLength(10);
    const amounts = (ids: (string | null)[]) => ids.map((id) => ROWS[Number(id) - 1]!.amount);
    expect(amounts(ascending)).toEqual([...amounts(ascending)].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)));
    expect(new Set(ascending)).toEqual(new Set(['57', '56', '55', '54', '53', '52', '51', '50', '49', '48']));
    expect(screen.getByRole('columnheader', { name: /Amount/ })).toHaveAttribute('aria-sort', 'ascending');
    expect(screen.getByText('Column order applies to this page only.')).toBeInTheDocument();

    fireEvent.click(amount);
    expect(numbers()).toEqual([...ascending].reverse());
    expect(screen.getByRole('columnheader', { name: /Amount/ })).toHaveAttribute('aria-sort', 'descending');

    fireEvent.click(amount);
    expect(numbers()[0]).toBe('57');
    expect(screen.getByRole('columnheader', { name: /Amount/ })).not.toHaveAttribute('aria-sort');
    expect(screen.queryByText('Column order applies to this page only.')).not.toBeInTheDocument();
    expect(within(screen.getByRole('columnheader', { name: 'Note' })).queryByRole('button')).toBeNull();
  });

  it('says so when there is nothing at all', async () => {
    renderTable('/list', true);
    expect(await screen.findByText('Nothing yet.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('says so when the first page cannot be read, and tries again on request', async () => {
    failing = true;
    renderTable();
    expect(await screen.findByRole('alert')).toHaveTextContent('The rows could not be loaded.');

    failing = false;
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(numbers()).toHaveLength(10));
  });

  it('keeps the page it has when a refresh fails, and says so', async () => {
    const client = renderTable();
    await waitFor(() => expect(numbers()).toHaveLength(10));
    failing = true;

    await act(() => client.refetchQueries({ queryKey: ['rows'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be refreshed; showing the last page loaded');
    expect(numbers()).toHaveLength(10);
  });
});

describe('PagedTable while a page loads or fails', () => {
  it('keeps the page shown, with its own numbering, until the next one arrives', async () => {
    renderTable();
    await waitFor(() => expect(numbers()).toHaveLength(10));
    const release = hold();

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() => expect(screen.getByRole('table', { name: 'Rows' })).toHaveAttribute('aria-busy', 'true'));
    expect(numbers()[0]).toBe('57');
    expect(showing()).toBe('Showing 1–10');
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();

    act(() => release());
    await waitFor(() => expect(showing()).toBe('Showing 11–20'));
    expect(screen.getByRole('table', { name: 'Rows' })).toHaveAttribute('aria-busy', 'false');
  });

  it('does not show the old rows under a new page size or order while the new first page loads', async () => {
    renderTable();
    await waitFor(() => expect(numbers()).toHaveLength(10));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(showing()).toBe('Showing 11–20'));
    const release = hold();

    fireEvent.change(screen.getByLabelText('Order'), { target: { value: 'oldest' } });

    expect(await screen.findByText('Loading…')).toBeInTheDocument();
    act(() => release());
    await waitFor(() => expect(numbers()[0]).toBe('1'));
  });

  it('keeps the controls and the way back when the next page fails to load', async () => {
    renderTable();
    await waitFor(() => expect(numbers()).toHaveLength(10));
    failing = true;

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The rows could not be loaded.');
    expect(screen.getByLabelText('Rows per page')).toBeInTheDocument();
    failing = false;
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    await waitFor(() => expect(numbers()[0]).toBe('57'));
    expect(showing()).toBe('Showing 1–10');
  });
});

describe('sortedRows', () => {
  it('keeps rows in the order they came for equal values, either way', () => {
    const rows = [
      { id: 1, group: 'b' },
      { id: 2, group: 'a' },
      { id: 3, group: 'b' },
      { id: 4, group: 'a' },
    ];
    const columns: Column<(typeof rows)[number]>[] = [
      { key: 'group', header: 'Group', cell: (row) => row.group, sortValue: (row) => row.group },
    ];
    expect(sortedRows(rows, columns, { key: 'group', direction: 'ascending' }).map((row) => row.id)).toEqual([2, 4, 1, 3]);
    expect(sortedRows(rows, columns, { key: 'group', direction: 'descending' }).map((row) => row.id)).toEqual([
      1, 3, 2, 4,
    ]);
    expect(sortedRows(rows, columns, null)).toBe(rows);
  });
});
