import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { resetSession } from '../api/client';
import { botJob } from '../test/botJob';
import { signedIn } from '../test/fakeServer';
import { OperatorPage } from './OperatorPage';

// The operator page reads the market's status once each time it is drawn, and nothing else inside it does: counting
// those reads counts the page's own renders, apart from those of the panels in it.
const page = vi.hoisted(() => ({ renders: 0 }));
vi.mock('../app/market', async (original) => {
  const actual = await original<typeof import('../app/market')>();
  return {
    ...actual,
    useMarketStatus: () => {
      page.renders += 1;
      return actual.useMarketStatus();
    },
  };
});

const SIMULATION = '10000000-0000-0000-0000-000000000001';

describe('the operator page while a bot job runs', () => {
  beforeEach(() => resetSession());

  it('draws again only the panels that show the job with each read of it, not the whole page', async () => {
    let created = 0;
    signedIn('operator', ['OPERATOR'])
      .on('GET /api/v1/market', {
        status: 200,
        json: {
          state: 'SETUP',
          simulationId: SIMULATION,
          name: null,
          openedAt: null,
          closesAt: null,
          closedAt: null,
          closedOnSchedule: false,
          commandSequence: '7',
        },
      })
      .on('GET /api/v1/instruments', { status: 200, json: { instruments: [], feed: { state: 'LIVE', lag: 0 } } })
      .on('GET /api/v1/admin/accounts', { status: 200, json: { accounts: [], nextCursor: null } })
      .on('GET /api/v1/admin/traders', { status: 200, json: { traders: [], nextCursor: null } })
      .on('GET /api/v1/admin/bots', {
        status: 200,
        json: { population: 0, maxPerCommand: 100, byStrategy: { marketMakers: 0, noise: 0, momentum: 0, untagged: 0 } },
      })
      .on('GET /api/v1/admin/bot-runs', { status: 200, json: { runs: [], nextCursor: null } })
      .on('GET /api/v1/admin/bot-jobs/current', () => {
        // A part made with each read, as a running job is read every second.
        created += 1_000;
        return { status: 200, json: botJob({ created, partsDone: created / 1_000 }) };
      });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/operator']}>
          <OperatorPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const bots = await screen.findByRole('region', { name: 'Bots' });
    const bar = await within(bots).findByRole('progressbar', { name: 'Creating bots' });
    // Settled: the status, the instruments and the first reads have all come back.
    await act(() => new Promise((resolve) => setTimeout(resolve, 300)));
    const settled = page.renders;
    const madeBefore = Number(bar.getAttribute('aria-valuenow'));

    await waitFor(() => expect(Number(bar.getAttribute('aria-valuenow'))).toBeGreaterThanOrEqual(madeBefore + 2_000), {
      timeout: 3_500,
    });
    // Two reads of the job or more, and the page itself not drawn again for any of them. Its own reads, of the status
    // and the instrument list, come every five seconds.
    expect(page.renders - settled).toBeLessThanOrEqual(1);
  });
});
