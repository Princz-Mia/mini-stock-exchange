import { createColumnHelper, FlexRender, tableFeatures, useTable } from '@tanstack/react-table';

interface RouteOwner {
  path: string;
  owner: string;
}

// Which service owns each route at the reverse proxy.
const ROUTES: RouteOwner[] = [
  { path: '/api/v1/auth, /me, /market, /orders, /portfolio, /commands, /admin', owner: 'Execution' },
  { path: '/api/v1/account-events', owner: 'Execution' },
  { path: '/api/v1/instruments, /market-events', owner: 'Market Data' },
];

const features = tableFeatures({});
const column = createColumnHelper<typeof features, RouteOwner>();
const columns = column.columns([
  column.accessor('path', { header: 'Path' }),
  column.accessor('owner', { header: 'Owner' }),
]);

export function RouteTable() {
  const table = useTable({ features, columns, data: ROUTES });

  return (
    <table aria-label="API route owners">
      <thead>
        {table.getHeaderGroups().map((group) => (
          <tr key={group.id}>
            {group.headers.map((header) => (
              <th key={header.id}>
                <FlexRender header={header} />
              </th>
            ))}
          </tr>
        ))}
      </thead>
      <tbody>
        {table.getRowModel().rows.map((row) => (
          <tr key={row.id}>
            {row.getAllCells().map((cell) => (
              <td key={cell.id}>
                <FlexRender cell={cell} />
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
