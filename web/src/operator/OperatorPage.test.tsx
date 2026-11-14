import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetSession } from '../api/client';
import type { BotRun, MarketStatus } from '../api/types';
import { TOURS } from '../help/tours';
import { botRun } from '../test/botRun';
import { signedIn } from '../test/fakeServer';
import { noTrades } from '../test/fakeStream';
import { inUseMessage } from './InstrumentSetup';
import { OperatorPage } from './OperatorPage';

const NOVA = {
  instrumentId: '00000000-0000-0000-0000-000000000001',
  symbol: 'NOVA',
  displayName: null as string | null,
  venueState: 'SETUP',
  referencePrice: '100.00',
  tick: '0.01',
  lotSize: 1,
  minQuantity: 1,
  maxQuantity: 10000,
  maxPrice: '1000000.00',
  lastPrice: null,
  bestBid: null,
  bestAsk: null,
  lastTradeSequence: '0',
  sourceCommandSequence: '1',
  generatedAt: '2026-11-05T09:00:00Z',
  session: noTrades('100.00'),
};

function market(state: MarketStatus['state']): MarketStatus {
  return state === 'NOT_SET_UP'
    ? { state, simulationId: null, openedAt: null, commandSequence: null }
    : {
        state,
        simulationId: '10000000-0000-0000-0000-000000000001',
        openedAt: state === 'SETUP' ? null : '2026-11-05T09:30:00Z',
        commandSequence: '7',
      };
}

/** A stateful stand-in: the market moves when a transition is accepted, so refetches show the new state. */
function exchange(initial: MarketStatus['state'], instruments = [NOVA]) {
  const state = { market: market(initial), bots: 0, runs: [] as BotRun[] };
  const server = signedIn('operator', ['OPERATOR'])
    .on('GET /api/v1/market', () => ({ status: 200, json: state.market }))
    .on('GET /api/v1/instruments', { status: 200, json: { instruments, feed: { state: 'LIVE', lag: 0 } } })
    .on('GET /api/v1/admin/accounts', { status: 200, json: { accounts: [], nextCursor: null } })
    .on('GET /api/v1/admin/bots', () => ({
      status: 200,
      json: {
        population: state.bots,
        maxPopulation: 1000,
        maxPerCommand: 100,
        byStrategy: { marketMakers: 0, noise: 0, momentum: 0, untagged: state.bots },
      },
    }))
    .on('GET /api/v1/admin/bot-runs', () => ({ status: 200, json: { runs: state.runs, nextCursor: null } }));
  return { server, state };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return renderWith(client);
}

function renderWith(client: QueryClient) {
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/operator']}>
        <OperatorPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const button = (name: string) => screen.findByRole('button', { name });

describe('the operator screen', () => {
  beforeEach(() => resetSession());

  it('offers only the transition the exchange would allow, and says why the others are not', async () => {
    exchange('SETUP');
    renderPage();

    expect(await button('Open market')).toBeEnabled();
    expect(await button('Halt trading')).toBeDisabled();
    expect(await button('Halt trading')).toHaveAccessibleDescription('Only an open market can be halted.');
    expect(await button('Resume trading')).toHaveAccessibleDescription('Only a halted market can be resumed.');
    expect(screen.getByText('Opening freezes the instruments and every account\'s endowment.')).toBeInTheDocument();
  });

  it('cannot open a market without instruments', async () => {
    exchange('NOT_SET_UP', []);
    renderPage();

    expect(await button('Open market')).toHaveAccessibleDescription(
      'Add an instrument first; that creates the market.',
    );
  });

  it('cannot open a market in setup until it has an instrument', async () => {
    exchange('SETUP', []);
    renderPage();

    expect(await button('Open market')).toBeDisabled();
    expect(await button('Open market')).toHaveAccessibleDescription('Add at least one instrument first.');
  });

  it('opens the market with a key and shows the committed state', async () => {
    const { server, state } = exchange('SETUP');
    server.on('POST /api/v1/admin/market/open', () => {
      state.market = market('OPEN');
      return { status: 200, json: { state: 'OPEN', commandSequence: '8' } };
    });
    renderPage();

    // An edit left open when the market opens goes with the rest of the setup controls.
    fireEvent.click(await button('Edit NOVA'));
    expect(screen.getByRole('form', { name: 'Edit NOVA' })).toBeInTheDocument();
    fireEvent.click(await button('Open market'));

    expect(await screen.findByText('The market is open.')).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/admin/market/open');
    expect(sent?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => expect(screen.getByText('Open', { selector: '.state-badge' })).toBeInTheDocument());
    expect(await button('Halt trading')).toBeEnabled();
    expect(screen.getByText('The catalogue is frozen once the market opens.')).toBeInTheDocument();
    // The rows that were mounted in SETUP drop their controls too.
    const table = screen.getByRole('table', { name: 'Instruments' });
    expect(within(table).queryByRole('textbox')).toBeNull();
    expect(within(table).queryByRole('button', { name: 'Edit NOVA' })).toBeNull();
    expect(within(table).queryByRole('button', { name: 'Delete NOVA' })).toBeNull();
    expect(within(table).queryByRole('columnheader', { name: 'Actions' })).toBeNull();
    expect(screen.queryByRole('form', { name: 'Edit NOVA' })).toBeNull();
  });

  it('retries a busy halt under the same key, so it cannot apply twice', async () => {
    const { server, state } = exchange('OPEN');
    let calls = 0;
    server.on('POST /api/v1/admin/market/halt', () => {
      calls += 1;
      if (calls === 1) {
        return { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } };
      }
      state.market = market('HALTED');
      return { status: 200, json: { state: 'HALTED', commandSequence: '9' } };
    });
    renderPage();

    fireEvent.click(await button('Halt trading'));
    expect(await screen.findByText(/Not confirmed/)).toBeInTheDocument();
    expect(await button('Halt trading')).toBeDisabled();
    fireEvent.click(await button('Retry'));

    expect(await screen.findByText('Trading is halted. Cancels still work.')).toBeInTheDocument();
    const keys = server.requests
      .filter((request) => request.url === '/api/v1/admin/market/halt')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('adds an instrument from a starting-scenario suggestion and keeps the input after a refusal', async () => {
    const { server } = exchange('SETUP');
    let calls = 0;
    server.on('POST /api/v1/admin/instruments', () => {
      calls += 1;
      return calls === 1
        ? { status: 409, problem: { status: 409, code: 'SYMBOL_TAKEN', detail: 'An instrument with symbol ORBT already exists' } }
        : {
            status: 201,
            json: { instrumentId: 'x', symbol: 'ORBT', displayName: 'Orbit Labs', referencePrice: '50.00', commandSequence: '9' },
          };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Add an instrument' });
    expect(within(form).queryByRole('button', { name: 'NOVA 100.00' })).toBeNull();
    fireEvent.click(within(form).getByRole('button', { name: 'ORBT 50.00' }));
    fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));

    expect(await screen.findByText('An instrument with symbol ORBT already exists')).toBeInTheDocument();
    expect(within(form).getByLabelText('Symbol')).toHaveValue('ORBT');
    // No name typed, so none is sent: the request is the one it was before names existed.
    const lastAdd = () =>
      server.requests.filter((request) => request.method === 'POST' && request.url === '/api/v1/admin/instruments').at(-1)?.body;
    expect(lastAdd()).toEqual({ symbol: 'ORBT', referencePrice: '50.00' });

    fireEvent.change(within(form).getByLabelText('Name (optional)'), { target: { value: ' Orbit Labs ' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));
    expect(await screen.findByText(/Added ORBT \(Orbit Labs\) at 50.00/)).toBeInTheDocument();
    expect(lastAdd()).toEqual({ symbol: 'ORBT', referencePrice: '50.00', displayName: 'Orbit Labs' });
    expect(within(form).getByLabelText('Name (optional)')).toHaveValue('');
    const keys = server.requests
      .filter((request) => request.url === '/api/v1/admin/instruments')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('edits the name and price together under a key, and keeps the form after a refusal', async () => {
    const named = { ...NOVA, displayName: 'Nova Dynamics' };
    const { server } = exchange('SETUP', [named]);
    const route = `PUT /api/v1/admin/instruments/${NOVA.instrumentId}`;
    let calls = 0;
    server.on(route, () => {
      calls += 1;
      return calls === 1
        ? {
            status: 400,
            problem: {
              status: 400,
              code: 'VALIDATION_FAILED',
              detail: 'The request is invalid',
              errors: [{ field: 'referencePrice', code: 'INVALID_PRICE', message: 'Reference price must be a price in cents' }],
            },
          }
        : {
            status: 200,
            json: { instrumentId: NOVA.instrumentId, symbol: 'NOVA', displayName: null, referencePrice: '1205.50', commandSequence: '9' },
          };
    });
    renderPage();

    // The name shows under the symbol; Edit opens a labelled form under the row, filled in, with focus on the name.
    const table = await screen.findByRole('table', { name: 'Instruments' });
    expect(within(table).getByText('Nova Dynamics')).toBeInTheDocument();
    fireEvent.click(await button('Edit NOVA'));
    const form = screen.getByRole('form', { name: 'Edit NOVA' });
    const name = within(form).getByLabelText('Name');
    const price = within(form).getByLabelText('Reference price');
    expect(name).toHaveValue('Nova Dynamics');
    expect(name).toHaveFocus();
    expect(price).toHaveValue('100.00');
    expect(screen.queryByRole('button', { name: 'Edit NOVA' })).toBeNull();

    // Both are sent each time, the ends trimmed; a refusal keeps the form and marks the field it names.
    fireEvent.change(name, { target: { value: ' Nova  Dyn ' } });
    fireEvent.change(price, { target: { value: ' 1205.5x ' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save NOVA' }));
    expect(await screen.findByText('Reference price must be a price in cents')).toBeInTheDocument();
    expect(price).toHaveValue(' 1205.5x ');
    expect(price).toHaveAttribute('aria-invalid', 'true');
    expect(price).toHaveAccessibleDescription('Reference price must be a price in cents');
    expect(price).toHaveFocus();
    expect(name).not.toHaveAttribute('aria-invalid');
    expect(server.requests.at(-1)?.body).toEqual({ referencePrice: '1205.5x', displayName: 'Nova  Dyn' });

    // An empty name removes it; a save closes the form and puts focus back on Edit.
    const listReads = () => server.requests.filter((request) => request.url === '/api/v1/instruments').length;
    const readsBefore = listReads();
    fireEvent.change(name, { target: { value: '   ' } });
    fireEvent.change(price, { target: { value: '1205.50' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save NOVA' }));
    expect(price).toBeDisabled();
    expect(
      await screen.findByText('Saved NOVA, with no name, at 1,205.50. The list shows it once market data has it.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Edit NOVA' })).toBeNull();
    expect(await button('Edit NOVA')).toHaveFocus();
    // The list is read again at once rather than at the next poll.
    await waitFor(() => expect(listReads()).toBeGreaterThan(readsBefore));
    const sent = server.requests.filter((request) => `${request.method} ${request.url}` === route);
    expect(sent.map((request) => request.body)).toEqual([
      { referencePrice: '1205.5x', displayName: 'Nova  Dyn' },
      { referencePrice: '1205.50', displayName: null },
    ]);
    expect(sent[0]?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent[1]?.headers['Idempotency-Key']).not.toBe(sent[0]?.headers['Idempotency-Key']);
  });

  it('cancels an edit without sending anything, and opens it again with the current values', async () => {
    const { server } = exchange('SETUP', [{ ...NOVA, displayName: 'Nova Dynamics' }]);
    renderPage();

    fireEvent.click(await button('Edit NOVA'));
    const form = screen.getByRole('form', { name: 'Edit NOVA' });
    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Typed, then dropped' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Cancel editing NOVA' }));

    expect(screen.queryByRole('form', { name: 'Edit NOVA' })).toBeNull();
    expect(await button('Edit NOVA')).toHaveFocus();
    fireEvent.click(await button('Edit NOVA'));
    expect(within(screen.getByRole('form', { name: 'Edit NOVA' })).getByLabelText('Name')).toHaveValue('Nova Dynamics');
    expect(server.requests.filter((request) => request.method === 'PUT')).toHaveLength(0);
  });

  it('fills a reopened form from the last save until the list catches up', async () => {
    const listed = { ...NOVA };
    const { server } = exchange('SETUP', [listed]);
    server.on(`PUT /api/v1/admin/instruments/${NOVA.instrumentId}`, {
      status: 200,
      json: { instrumentId: NOVA.instrumentId, symbol: 'NOVA', displayName: 'Nova Dynamics', referencePrice: '110.00', commandSequence: '9' },
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);

    fireEvent.click(await button('Edit NOVA'));
    let form = screen.getByRole('form', { name: 'Edit NOVA' });
    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Nova Dynamics' } });
    fireEvent.change(within(form).getByLabelText('Reference price'), { target: { value: '110.00' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save NOVA' }));
    expect(await screen.findByText(/Saved NOVA as Nova Dynamics at 110.00/)).toBeInTheDocument();

    // Market Data has not projected the save yet, so the list still shows the old values: the form takes the saved ones.
    fireEvent.click(await button('Edit NOVA'));
    form = screen.getByRole('form', { name: 'Edit NOVA' });
    expect(within(form).getByLabelText('Name')).toHaveValue('Nova Dynamics');
    expect(within(form).getByLabelText('Reference price')).toHaveValue('110.00');
    fireEvent.click(within(form).getByRole('button', { name: 'Cancel editing NOVA' }));

    // Once the list is newer than the save, it is the list that fills the form.
    Object.assign(listed, { displayName: 'Renamed Elsewhere', referencePrice: '120.00', sourceCommandSequence: '10' });
    await client.invalidateQueries({ queryKey: ['instruments'] });
    await screen.findByText('Renamed Elsewhere');
    fireEvent.click(await button('Edit NOVA'));
    form = screen.getByRole('form', { name: 'Edit NOVA' });
    expect(within(form).getByLabelText('Name')).toHaveValue('Renamed Elsewhere');
    expect(within(form).getByLabelText('Reference price')).toHaveValue('120.00');
  });

  it('leaves focus where the user moved it while a save was sending', async () => {
    const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' };
    const { server } = exchange('SETUP', [NOVA, ORBT]);
    server.on(`PUT /api/v1/admin/instruments/${NOVA.instrumentId}`, {
      status: 200,
      json: { instrumentId: NOVA.instrumentId, symbol: 'NOVA', displayName: null, referencePrice: '100.00', commandSequence: '9' },
    });
    renderPage();

    fireEvent.click(await button('Edit NOVA'));
    fireEvent.click(within(screen.getByRole('form', { name: 'Edit NOVA' })).getByRole('button', { name: 'Save NOVA' }));
    const elsewhere = screen.getByRole('button', { name: 'Edit ORBT' });
    elsewhere.focus();

    expect(await screen.findByText(/Saved NOVA/)).toBeInTheDocument();
    expect(elsewhere).toHaveFocus();
  });

  it('marks and focuses the field a refusal names, or the name when it names none', async () => {
    const { server } = exchange('SETUP');
    const badName = { field: 'displayName', code: 'INVALID_DISPLAY_NAME', message: 'displayName must not have two spaces in a row' };
    const badPrice = { field: 'referencePrice', code: 'INVALID_PRICE', message: 'Reference price must be a price in cents' };
    let call = 0;
    server.on(`PUT /api/v1/admin/instruments/${NOVA.instrumentId}`, () => {
      call += 1;
      if (call === 1) {
        return { status: 400, problem: { status: 400, code: 'VALIDATION_FAILED', detail: 'invalid', errors: [badName] } };
      }
      if (call === 2) {
        return { status: 400, problem: { status: 400, code: 'VALIDATION_FAILED', detail: 'invalid', errors: [badName, badPrice] } };
      }
      return {
        status: 409,
        problem: { status: 409, code: 'MARKET_NOT_IN_SETUP', detail: 'Instruments can only be changed before the market opens' },
      };
    });
    renderPage();

    fireEvent.click(await button('Edit NOVA'));
    const form = screen.getByRole('form', { name: 'Edit NOVA' });
    const name = within(form).getByLabelText('Name');
    const price = within(form).getByLabelText('Reference price');
    const save = within(form).getByRole('button', { name: 'Save NOVA' });

    fireEvent.click(save);
    expect(await screen.findByText(badName.message)).toBeInTheDocument();
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveAccessibleDescription(badName.message);
    expect(name).toHaveFocus();
    expect(price).not.toHaveAttribute('aria-invalid');

    price.focus();
    fireEvent.click(save);
    expect(await screen.findByText(`${badName.message}; ${badPrice.message}`)).toBeInTheDocument();
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(price).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveFocus();

    fireEvent.click(save);
    expect(await screen.findByText('Instruments can only be changed before the market opens')).toBeInTheDocument();
    expect(name).not.toHaveAttribute('aria-invalid');
    expect(price).not.toHaveAttribute('aria-invalid');
    expect(name).toHaveFocus();
  });

  it('drops a refusal with the form it answered', async () => {
    const { server } = exchange('SETUP');
    server.on(`PUT /api/v1/admin/instruments/${NOVA.instrumentId}`, {
      status: 400,
      problem: {
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'invalid',
        errors: [{ field: 'referencePrice', code: 'INVALID_PRICE', message: 'Reference price must be a price in cents' }],
      },
    });
    renderPage();

    fireEvent.click(await button('Edit NOVA'));
    let form = screen.getByRole('form', { name: 'Edit NOVA' });
    fireEvent.change(within(form).getByLabelText('Reference price'), { target: { value: '1x' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save NOVA' }));
    expect(await screen.findByText('Reference price must be a price in cents')).toBeInTheDocument();

    fireEvent.click(within(form).getByRole('button', { name: 'Cancel editing NOVA' }));
    expect(screen.queryByText('Reference price must be a price in cents')).toBeNull();
    fireEvent.click(await button('Edit NOVA'));
    form = screen.getByRole('form', { name: 'Edit NOVA' });
    expect(within(form).getByLabelText('Reference price')).not.toHaveAttribute('aria-invalid');
    expect(within(form).getByLabelText('Reference price')).toHaveValue('100.00');
  });

  it('closes a pending delete confirmation with the form, so focus lands on Edit', async () => {
    exchange('SETUP');
    renderPage();

    fireEvent.click(await button('Edit NOVA'));
    fireEvent.click(screen.getByRole('button', { name: 'Delete NOVA' }));
    expect(screen.getByRole('button', { name: 'Confirm delete of NOVA' })).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel editing NOVA' }));

    expect(screen.queryByRole('button', { name: 'Confirm delete of NOVA' })).toBeNull();
    expect(await button('Edit NOVA')).toHaveFocus();
  });

  it('retries an unconfirmed save under its own key, while the other rows go on under theirs', async () => {
    const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT', referencePrice: '50.00' };
    const { server } = exchange('SETUP', [NOVA, ORBT]);
    let novaCalls = 0;
    server
      .on(`PUT /api/v1/admin/instruments/${NOVA.instrumentId}`, () => {
        novaCalls += 1;
        return novaCalls === 1
          ? { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } }
          : {
              status: 200,
              json: { instrumentId: NOVA.instrumentId, symbol: 'NOVA', displayName: null, referencePrice: '110.00', commandSequence: '10' },
            };
      })
      .on(`PUT /api/v1/admin/instruments/${ORBT.instrumentId}`, {
        status: 200,
        json: { instrumentId: ORBT.instrumentId, symbol: 'ORBT', displayName: null, referencePrice: '55.00', commandSequence: '9' },
      });
    renderPage();

    const save = async (symbol: string, value: string) => {
      fireEvent.click(await button(`Edit ${symbol}`));
      const form = screen.getByRole('form', { name: `Edit ${symbol}` });
      fireEvent.change(within(form).getByLabelText('Reference price'), { target: { value } });
      fireEvent.click(within(form).getByRole('button', { name: `Save ${symbol}` }));
    };
    await save('NOVA', '110.00');
    // Each outcome sits in the row under its own instrument and its form.
    const outcomeOf = (symbol: string) => {
      const rows = screen.getAllByRole('row');
      const at = rows.findIndex((row) => within(row).queryByRole('rowheader', { name: symbol }) !== null);
      const next = rows.slice(at + 1).find((row) => row.classList.contains('row-outcome'));
      expect(next).toBeDefined();
      return next as HTMLElement;
    };
    await waitFor(() => expect(outcomeOf('NOVA')).toHaveTextContent(/Not confirmed/));
    const novaForm = screen.getByRole('form', { name: 'Edit NOVA' });
    expect(within(novaForm).getByLabelText('Reference price')).toBeDisabled();
    expect(within(novaForm).getByRole('button', { name: 'Cancel editing NOVA' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete NOVA' })).toBeDisabled();

    await save('ORBT', '55.00');
    await waitFor(() => expect(outcomeOf('ORBT')).toHaveTextContent(/Saved ORBT, with no name, at 55.00/));
    expect(outcomeOf('NOVA')).toHaveTextContent(/Not confirmed/);

    fireEvent.click(within(outcomeOf('NOVA')).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(outcomeOf('NOVA')).toHaveTextContent(/Saved NOVA, with no name, at 110.00/));
    expect(screen.queryByRole('form', { name: 'Edit NOVA' })).toBeNull();

    const keyOf = (id: string) =>
      server.requests
        .filter((request) => request.method === 'PUT' && request.url === `/api/v1/admin/instruments/${id}`)
        .map((request) => request.headers['Idempotency-Key']);
    const nova = keyOf(NOVA.instrumentId);
    expect(nova).toHaveLength(2);
    expect(nova[1]).toBe(nova[0]);
    expect(keyOf(ORBT.instrumentId)).toHaveLength(1);
    expect(keyOf(ORBT.instrumentId)[0]).not.toBe(nova[0]);
    expect(server.requests.filter((request) => request.method === 'PUT').map((request) => request.body)).toEqual([
      { referencePrice: '110.00', displayName: null },
      { referencePrice: '55.00', displayName: null },
      { referencePrice: '110.00', displayName: null },
    ]);
  });

  it('keeps an unconfirmed save and its key on screen when a later market read fails', async () => {
    const { server, state } = exchange('SETUP');
    let down = false;
    server
      .on('GET /api/v1/market', () =>
        down
          ? { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE', detail: 'down' } }
          : { status: 200, json: state.market },
      )
      .on(`PUT /api/v1/admin/instruments/${NOVA.instrumentId}`, () =>
        down
          ? { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } }
          : {
              status: 200,
              json: { instrumentId: NOVA.instrumentId, symbol: 'NOVA', displayName: null, referencePrice: '110.00', commandSequence: '9' },
            },
      );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);

    fireEvent.click(await button('Edit NOVA'));
    const form = screen.getByRole('form', { name: 'Edit NOVA' });
    fireEvent.change(within(form).getByLabelText('Reference price'), { target: { value: '110.00' } });
    down = true;
    fireEvent.click(within(form).getByRole('button', { name: 'Save NOVA' }));
    expect(await screen.findByText(/Not confirmed/)).toBeInTheDocument();
    await client.refetchQueries({ queryKey: ['market'] });

    expect(
      await screen.findByText('The exchange is not answering, so the market state shown may be out of date.'),
    ).toBeInTheDocument();
    expect(screen.queryByText('The exchange is not reachable right now.')).toBeNull();
    expect(within(screen.getByRole('form', { name: 'Edit NOVA' })).getByLabelText('Reference price')).toHaveValue('110.00');
    down = false;
    fireEvent.click(await button('Retry'));
    expect(await screen.findByText(/Saved NOVA, with no name, at 110.00/)).toBeInTheDocument();
    const keys = server.requests
      .filter((request) => request.method === 'PUT')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('shows names but offers no editing once the market has opened', async () => {
    exchange('OPEN', [{ ...NOVA, displayName: 'Nova Dynamics' }]);
    renderPage();

    const table = await screen.findByRole('table', { name: 'Instruments' });
    expect(within(table).getByRole('rowheader', { name: 'NOVA Nova Dynamics' })).toBeInTheDocument();
    expect(within(table).queryByRole('textbox')).toBeNull();
    expect(within(table).queryByRole('button', { name: 'Edit NOVA' })).toBeNull();
    expect(within(table).queryByRole('button', { name: 'Delete NOVA' })).toBeNull();
    expect(within(table).queryByRole('columnheader', { name: 'Actions' })).toBeNull();
    expect(screen.queryByText(/can be changed until the market opens/)).toBeNull();
  });

  it('deletes an instrument only once confirmed, under a key, and moves on to adding', async () => {
    const { server } = exchange('SETUP');
    server.on(`DELETE /api/v1/admin/instruments/${NOVA.instrumentId}`, {
      status: 200,
      json: { instrumentId: NOVA.instrumentId, symbol: 'NOVA', commandSequence: '9' },
    });
    renderPage();
    const deletes = () => server.requests.filter((request) => request.method === 'DELETE');

    // Asking first: Delete becomes Confirm and Keep, and Keep changes nothing.
    fireEvent.click(await button('Delete NOVA'));
    expect(screen.getByRole('button', { name: 'Confirm delete of NOVA' })).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: 'Keep NOVA' }));
    expect(await button('Delete NOVA')).toHaveFocus();
    expect(deletes()).toHaveLength(0);

    const listReads = server.requests.filter((request) => request.url === '/api/v1/instruments').length;
    fireEvent.click(await button('Delete NOVA'));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete of NOVA' }));

    // Said by the panel, which stays when the row goes once market data drops it.
    const panel = screen.getByRole('region', { name: 'Instruments' });
    expect(await within(panel).findByText('NOVA is deleted. The list drops it once market data has it.')).toHaveAttribute(
      'role',
      'status',
    );
    expect(deletes()).toHaveLength(1);
    expect(deletes()[0]?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(deletes()[0]?.body).toBeUndefined();
    const table = screen.getByRole('table', { name: 'Instruments' });
    expect(within(table).getByText('Deleted')).toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: 'Delete NOVA' })).toBeNull();
    expect(within(table).queryByRole('button', { name: 'Edit NOVA' })).toBeNull();
    // The row's controls are gone, so focus goes to the add form.
    expect(screen.getByRole('textbox', { name: 'Symbol' })).toHaveFocus();
    await waitFor(() =>
      expect(server.requests.filter((request) => request.url === '/api/v1/instruments').length).toBeGreaterThan(listReads),
    );
  });

  it('explains an instrument in use with its holders, keeps it, and tries again under a new key', async () => {
    const { server } = exchange('SETUP');
    let answers = 0;
    server.on(`DELETE /api/v1/admin/instruments/${NOVA.instrumentId}`, () => {
      answers += 1;
      return {
        status: 409,
        problem: {
          status: 409,
          code: 'INSTRUMENT_IN_USE',
          detail: 'NOVA is in use, so it cannot be deleted',
          commandSequence: '9',
          usage: { holders: ['bot-0001', 'trader'], holderCount: 1_025, orderCount: answers },
        },
      };
    });
    renderPage();

    fireEvent.click(await button('Delete NOVA'));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete of NOVA' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      "NOVA can't be deleted: it is in use. Its shares were given to bot-0001, trader and 1,023 more, as listed " +
        'under Accounts. An order names it. Orders sent before the market opens are refused, but kept on record. ' +
        'Taking shares or orders back would need correcting entries in the ledger, so an instrument in use stays.',
    );
    const again = await button('Delete NOVA');
    expect(again).toBeEnabled();
    expect(again).toHaveFocus();
    expect(again).toHaveAccessibleDescription(alert.textContent ?? '');
    expect(screen.getByRole('button', { name: 'Edit NOVA' })).toBeEnabled();

    fireEvent.click(again);
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete of NOVA' }));
    expect(await screen.findByText(/2 orders name it\./)).toBeInTheDocument();
    const keys = server.requests
      .filter((request) => request.method === 'DELETE')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('retries an unconfirmed delete under its own key, with the row locked until then', async () => {
    const { server } = exchange('SETUP');
    let busy = true;
    server.on(`DELETE /api/v1/admin/instruments/${NOVA.instrumentId}`, () =>
      busy
        ? { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } }
        : { status: 200, json: { instrumentId: NOVA.instrumentId, symbol: 'NOVA', commandSequence: '9' } },
    );
    renderPage();

    fireEvent.click(await button('Delete NOVA'));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete of NOVA' }));
    expect(await screen.findByText(/Not confirmed/)).toBeInTheDocument();
    expect(await button('Delete NOVA')).toBeDisabled();
    // The row's edit waits for the delete's final answer too.
    expect(screen.getByRole('button', { name: 'Edit NOVA' })).toBeDisabled();

    busy = false;
    fireEvent.click(await button('Retry'));
    expect(await screen.findByText(/NOVA is deleted\./)).toBeInTheDocument();
    const keys = server.requests
      .filter((request) => request.method === 'DELETE')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('leaves focus where the user moved it while a delete was sending', async () => {
    const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' };
    const { server } = exchange('SETUP', [NOVA, ORBT]);
    server.on(`DELETE /api/v1/admin/instruments/${NOVA.instrumentId}`, {
      status: 200,
      json: { instrumentId: NOVA.instrumentId, symbol: 'NOVA', commandSequence: '9' },
    });
    renderPage();

    fireEvent.click(await button('Delete NOVA'));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete of NOVA' }));
    const elsewhere = screen.getByRole('button', { name: 'Edit ORBT' });
    elsewhere.focus();

    expect(await screen.findByText(/NOVA is deleted\./)).toBeInTheDocument();
    expect(elsewhere).toHaveFocus();
  });

  it('runs one command at a time in a row, each described by its own words', async () => {
    const { server } = exchange('SETUP');
    let priceBusy = true;
    server
      .on(`PUT /api/v1/admin/instruments/${NOVA.instrumentId}`, () =>
        priceBusy
          ? { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } }
          : {
              status: 400,
              problem: {
                status: 400,
                code: 'VALIDATION_FAILED',
                detail: 'The request is invalid',
                errors: [{ field: 'referencePrice', code: 'INVALID_PRICE', message: 'Reference price must be a price in cents' }],
              },
            },
      )
      .on(`DELETE /api/v1/admin/instruments/${NOVA.instrumentId}`, {
        status: 409,
        problem: {
          status: 409,
          code: 'INSTRUMENT_IN_USE',
          detail: 'NOVA is in use, so it cannot be deleted',
          usage: { holders: ['trader'], holderCount: 1, orderCount: 0 },
        },
      });
    renderPage();

    // An unconfirmed save holds the delete back, so a deleted row never offers to retry a save.
    fireEvent.click(await button('Edit NOVA'));
    const form = screen.getByRole('form', { name: 'Edit NOVA' });
    const price = within(form).getByLabelText('Reference price');
    fireEvent.change(price, { target: { value: '1x' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save NOVA' }));
    expect(await screen.findByText(/Not confirmed/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete NOVA' })).toBeDisabled();

    priceBusy = false;
    fireEvent.click(await button('Retry'));
    expect(await screen.findByText('Reference price must be a price in cents')).toBeInTheDocument();
    fireEvent.click(await button('Delete NOVA'));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete of NOVA' }));
    expect(await screen.findByText(/NOVA can't be deleted/)).toBeInTheDocument();

    // Both refusals show, and each control is described by its own.
    expect(price).toHaveAccessibleDescription('Reference price must be a price in cents');
    expect(await button('Delete NOVA')).toHaveAccessibleDescription(/^NOVA can't be deleted: it is in use\./);
    expect(await button('Delete NOVA')).not.toHaveAccessibleDescription(/Reference price/);
  });

  it('says plainly what keeps an instrument, whatever the mix of holders and orders', () => {
    expect(inUseMessage('ORBT', { holders: [], holderCount: 0, orderCount: 3 })).toBe(
      "ORBT can't be deleted: it is in use. 3 orders name it. Orders sent before the market opens are refused, but " +
        'kept on record. Taking shares or orders back would need correcting entries in the ledger, so an instrument ' +
        'in use stays.',
    );
    expect(inUseMessage('ORBT', { holders: [], holderCount: 0, orderCount: 1_500 })).toContain('1,500 orders name it.');
    expect(inUseMessage('ORBT', { holders: ['trader'], holderCount: 1, orderCount: 0 })).toBe(
      "ORBT can't be deleted: it is in use. Its shares were given to trader, as listed under Accounts. Taking " +
        'shares or orders back would need correcting entries in the ledger, so an instrument in use stays.',
    );
  });

  it('funds a trader with the default endowment for every instrument', async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/accounts', {
      status: 201,
      json: { accountId: 'a', username: 'trader', cash: '100000.00', holdings: [], commandSequence: '9' },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Fund a trader' });
    expect(await within(form).findByLabelText('NOVA shares')).toHaveValue('1000');
    fireEvent.change(within(form).getByLabelText("Trader's username"), { target: { value: 'trader' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Fund account' }));

    expect(await screen.findByText('Funded trader with 100,000.00.')).toBeInTheDocument();
    expect(server.requests.find((request) => request.url === '/api/v1/admin/accounts' && request.method === 'POST')?.body)
      .toEqual({
        username: 'trader',
        cash: '100000.00',
        holdings: [{ instrumentId: NOVA.instrumentId, quantity: 1000 }],
      });
  });

  it('lists funded accounts with what they started with', async () => {
    const { server } = exchange('OPEN');
    server.on('GET /api/v1/admin/accounts', {
      status: 200,
      json: {
        accounts: [
          {
            accountId: 'a',
            username: 'trader',
            kind: 'TRADER',
            cash: '100000.00',
            holdings: [{ instrumentId: NOVA.instrumentId, symbol: 'NOVA', quantity: 1000 }],
            token: null,
            strategy: null,
          },
          {
            accountId: 'b',
            username: 'bot-0001',
            kind: 'BOT',
            cash: '5000.00',
            holdings: [],
            token: 'ACTIVE',
            strategy: 'NOISE',
          },
        ],
        nextCursor: null,
      },
    });
    renderPage();

    const row = await screen.findByRole('row', { name: /trader/ });
    // Oldest first unless asked otherwise, a page at a time.
    const asked = server.requests.find((request) => request.url.startsWith('/api/v1/admin/accounts'))!;
    expect(asked.url).toContain('order=oldest');
    expect(asked.url).toContain('limit=25');
    expect(row).toHaveTextContent('100,000.00');
    expect(row).toHaveTextContent('NOVA 1,000');
    expect(within(row).queryByText('bot')).toBeNull();
    const bot = screen.getByRole('row', { name: /bot-0001/ });
    expect(within(bot).getByText('bot')).toHaveClass('tag');
    expect(screen.getByText(/Endowments are frozen once the market opens/)).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Fund a trader' })).toBeNull();
  });

  it("revokes a bot's token only after a confirmation, then shows it revoked", async () => {
    const { server } = exchange('OPEN');
    let token: 'ACTIVE' | 'REVOKED' = 'ACTIVE';
    server.on('GET /api/v1/admin/accounts', () => ({
      status: 200,
      json: {
        accounts: [
          { accountId: 'b', username: 'bot-0001', kind: 'BOT', cash: '5000.00', holdings: [], token, strategy: 'NOISE' },
        ],
        nextCursor: null,
      },
    }));
    server.on('POST /api/v1/admin/bots/b/revoke-token', () => {
      token = 'REVOKED';
      return { status: 200, json: { accountId: 'b', revoked: 1, commandSequence: '12' } };
    });
    const revocations = () => server.requests.filter((request) => request.url.endsWith('/revoke-token'));
    renderPage();

    const bot = await screen.findByRole('row', { name: /bot-0001/ });
    expect(within(bot).getByText('Active')).toBeInTheDocument();
    fireEvent.click(within(bot).getByRole('button', { name: "Revoke bot-0001's token" }));
    fireEvent.click(within(bot).getByRole('button', { name: 'Keep' }));
    expect(revocations()).toHaveLength(0);
    fireEvent.click(within(bot).getByRole('button', { name: "Revoke bot-0001's token" }));
    fireEvent.click(within(bot).getByRole('button', { name: "Confirm revoking bot-0001's token" }));

    expect(await within(bot).findByText('Revoked')).toBeInTheDocument();
    expect(revocations()).toHaveLength(1);
    expect(revocations()[0]?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(within(bot).queryByRole('button', { name: /Revoke/ })).toBeNull();
  });

  it('creates bots with the default endowment and shows the new population', async () => {
    const { server, state } = exchange('SETUP');
    server.on('POST /api/v1/admin/bots', () => {
      state.bots = 10;
      return {
        status: 201,
        json: {
          created: 10,
          firstBot: 'bot-0001',
          lastBot: 'bot-0010',
          population: 10,
          strategy: null,
          commandSequence: '9',
        },
      };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    expect(await screen.findByText('0 of 1,000 bots')).toBeInTheDocument();
    expect(within(form).getByLabelText('Number of bots')).toHaveValue('10');
    expect(await within(form).findByLabelText('NOVA shares')).toHaveValue('1000');
    expect(within(form).getByText(/Up to 100 at a time, 1,000 more in all/)).toBeInTheDocument();
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(await screen.findByText('Created bot-0001 to bot-0010. 10 bots in all.')).toBeInTheDocument();
    expect(await screen.findByText('10 of 1,000 bots')).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/admin/bots' && request.method === 'POST');
    expect(sent?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent?.body).toEqual({
      count: 10,
      cash: '100000.00',
      holdings: [{ instrumentId: NOVA.instrumentId, quantity: 1000 }],
    });
  });

  it("shows a refused bot request in the exchange's words and keeps the input", async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/bots', {
      status: 409,
      problem: { status: 409, code: 'BOT_POPULATION_LIMIT', detail: 'At most 1000 bots; 990 exist' },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '20' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(await screen.findByText('At most 1000 bots; 990 exist')).toBeInTheDocument();
    expect(within(form).getByLabelText('Number of bots')).toHaveValue('20');
  });

  it('cannot create bots once the population is full, and says why', async () => {
    const { state } = exchange('SETUP');
    state.bots = 1000;
    renderPage();

    const create = await button('Create bots');
    await waitFor(() => expect(create).toBeDisabled());
    expect(create).toHaveAccessibleDescription('The population is at its limit; no more bots can be created.');
  });

  it('fixes the bot population once the market opens', async () => {
    const { state } = exchange('OPEN');
    state.bots = 12;
    renderPage();

    expect(await screen.findByText('12 of 1,000 bots')).toBeInTheDocument();
    expect(screen.getByText(/the population is fixed after that/)).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Create bots' })).toBeNull();
  });
});

describe('bot runs on the operator screen', () => {
  beforeEach(() => resetSession());

  const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' };
  const runs = () => screen.findByRole('region', { name: 'Bot runs' });

  it('starts a run with the suggested split, sending only what the exchange needs', async () => {
    const { server, state } = exchange('OPEN', [NOVA, ORBT]);
    state.bots = 10;
    server.on('POST /api/v1/admin/bot-runs', () => {
      state.runs = [botRun({ status: 'STARTING', worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null } })];
      return { status: 201, json: { runId: botRun().runId, bots: 10, commandSequence: '9' } };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    await waitFor(() => expect(within(form).getByLabelText('Market makers')).toHaveValue('2'));
    expect(within(form).getByLabelText('Noise')).toHaveValue('6');
    expect(within(form).getByLabelText('Momentum')).toHaveValue('2');
    expect(within(form).getByRole('checkbox', { name: 'ORBT' })).toBeChecked();
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));

    expect(await screen.findByRole('status', { name: 'Run status: Starting' })).toBeInTheDocument();
    expect(screen.getByText('Waiting for a simulator worker to take the run.')).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/admin/bot-runs' && request.method === 'POST');
    expect(sent?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent?.body).toEqual({
      marketMakers: 2,
      noise: 6,
      momentum: 2,
      commandsPerSecond: '4.00',
      maxOrderQuantity: 10,
      maxPosition: 100,
    });
  });

  it('starts on the chosen instruments only, and never sends an empty choice', async () => {
    const { server, state } = exchange('OPEN', [NOVA, ORBT]);
    state.bots = 3;
    server.on('POST /api/v1/admin/bot-runs', {
      status: 422,
      problem: { status: 422, code: 'NOT_ENOUGH_BOTS', detail: 'The run needs 5 bots; 3 exist' },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    fireEvent.click(within(form).getByRole('checkbox', { name: 'ORBT' }));
    fireEvent.click(within(form).getByRole('checkbox', { name: 'NOVA' }));
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    expect(await within(form).findByText('Choose at least one instrument.')).toBeInTheDocument();
    expect(server.requests.some((request) => request.method === 'POST')).toBe(false);

    fireEvent.click(within(form).getByRole('checkbox', { name: 'NOVA' }));
    fireEvent.change(within(form).getByLabelText('Noise'), { target: { value: '5' } });
    fireEvent.change(within(form).getByLabelText('Seed'), { target: { value: '42' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));

    expect(await within(form).findByText('The run needs 5 bots; 3 exist')).toBeInTheDocument();
    expect(within(form).queryByText('Choose at least one instrument.')).toBeNull();
    const sent = server.requests.find((request) => request.method === 'POST' && request.url.endsWith('/bot-runs'));
    expect(sent?.body).toMatchObject({ noise: 5, seed: 42, instrumentIds: [NOVA.instrumentId] });
    expect(within(form).getByLabelText('Noise')).toHaveValue('5');
  });

  it('cannot start before the market opens, and says why', async () => {
    exchange('SETUP');
    renderPage();
    expect(await button('Start run')).toBeDisabled();
    expect(await button('Start run')).toHaveAccessibleDescription('Bots trade only once the market has opened.');
  });

  it('cannot start without bots once open, and says why', async () => {
    exchange('HALTED');
    renderPage();
    const start = await button('Start run');
    await waitFor(() => expect(start).toHaveAccessibleDescription('There are no bots; they are created during setup.'));
    expect(start).toBeDisabled();
  });

  it('shows the running run with its worker and activity instead of the form', async () => {
    const { state } = exchange('OPEN');
    state.bots = 3;
    state.runs = [botRun()];
    renderPage();

    const panel = await runs();
    expect(await within(panel).findByRole('status', { name: 'Run status: Running' })).toHaveClass('state-open');
    expect(within(panel).queryByRole('form', { name: 'Start a bot run' })).toBeNull();
    expect(within(panel).getByText('sim-1 holds the run.')).toBeInTheDocument();
    const counters = within(panel).getByRole('group', { name: 'Current run' });
    expect(within(panel).getAllByText('1 market maker, 2 noise')[0]).toBeInTheDocument();
    expect(counters).toHaveTextContent('Placed150');
    expect(counters).toHaveTextContent('In flight1');
    expect(counters).toHaveTextContent('Per second2.50 of 4.00');
    // The history lists every run, the running one too, and says where it stands.
    const history = await within(panel).findByRole('table', { name: 'Recent runs' });
    expect(within(history).getAllByRole('row')).toHaveLength(2);
    expect(within(history).getAllByRole('row')[1]).toHaveTextContent('Running');
    expect(within(panel).getByRole('button', { name: 'Stop run' })).toBeEnabled();
  });

  it('stops a run under one key, even after a busy answer, then waits for the worker', async () => {
    const { server, state } = exchange('OPEN');
    state.runs = [botRun()];
    let calls = 0;
    server.on(`POST /api/v1/admin/bot-runs/${botRun().runId}/stop`, () => {
      calls += 1;
      if (calls === 1) {
        return { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } };
      }
      state.runs = [botRun({ status: 'STOPPING', desiredState: 'STOPPED', stopRequestedAt: '2026-11-06T10:01:00Z' })];
      return { status: 200, json: { runId: botRun().runId, commandSequence: '12' } };
    });
    renderPage();

    fireEvent.click(await button('Stop run'));
    expect(await screen.findByText(/Not confirmed/)).toBeInTheDocument();
    expect(await button('Stop run')).toBeDisabled();
    fireEvent.click(await button('Retry'));

    expect(await screen.findByRole('status', { name: 'Run status: Stopping' })).toBeInTheDocument();
    expect(await button('Stop run')).toBeDisabled();
    expect(await button('Stop run')).toHaveAccessibleDescription(
      'A stop was asked for at 10:01:00 UTC; the run shows Stopped once the worker confirms.',
    );
    const keys = server.requests
      .filter((request) => request.url.endsWith('/stop'))
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('warns about a lost worker and about orders a stop could not cancel', async () => {
    const { state } = exchange('OPEN');
    state.runs = [botRun({ status: 'STALE' })];
    const view = renderPage();
    expect(await screen.findByRole('status', { name: 'Run status: Worker lost' })).toHaveClass('state-halted');
    expect(screen.getByText(/No heartbeat from sim-1 since 10:01:20 UTC/)).toHaveClass('form-error');
    view.unmount();

    state.runs = [
      botRun({
        status: 'STOPPING',
        desiredState: 'STOPPED',
        stopRequestedAt: '2026-11-06T10:01:00Z',
        activity: { ...botRun().activity, stopFailures: 2 },
      }),
    ];
    renderPage();
    expect(await screen.findByText('2 orders could not be canceled while stopping.')).toBeInTheDocument();
  });

  it('lists recent runs, and offers the form again once the last one stopped', async () => {
    const { state } = exchange('OPEN');
    state.bots = 3;
    state.runs = [
      botRun({
        runId: 'b',
        status: 'STOPPED',
        desiredState: 'STOPPED',
        stoppedAt: '2026-11-06T10:05:00Z',
        activity: { ...botRun().activity, stopFailures: 2 },
      }),
      botRun({
        runId: 'a',
        status: 'STOPPED',
        desiredState: 'STOPPED',
        startedAt: '2026-11-06T09:00:00Z',
        stoppedAt: null,
        activity: { ...botRun().activity, stopFailures: 1 },
      }),
    ];
    renderPage();

    expect(await screen.findByRole('form', { name: 'Start a bot run' })).toBeInTheDocument();
    const recent = screen.getByRole('table', { name: 'Recent runs' });
    expect(within(recent).getAllByRole('row')).toHaveLength(3);
    expect(within(recent).getByRole('row', { name: /10:00:00 UTC/ })).toHaveTextContent('10:05:00 UTC · 2 not canceled');
    expect(within(recent).getByRole('row', { name: /09:00:00 UTC/ })).toHaveTextContent(
      'Not confirmed · 1 not canceled',
    );
  });

  it('pages the run history and the accounts with the cursor, size and order they were given', async () => {
    const { server, state } = exchange('OPEN');
    state.bots = 3;
    const older = botRun({ runId: 'older', status: 'STOPPED', desiredState: 'STOPPED', startedAt: '2026-11-06T08:00:00Z' });
    const newer = botRun({ runId: 'newer', status: 'STOPPED', desiredState: 'STOPPED', startedAt: '2026-11-06T09:00:00Z' });
    state.runs = [newer];
    server
      .on('GET /api/v1/admin/bot-runs', (request) => ({
        status: 200,
        json: request.url.includes('cursor=runs-2')
          ? { runs: [older], nextCursor: null }
          : { runs: state.runs, nextCursor: 'runs-2' },
      }))
      .on('GET /api/v1/admin/accounts', (request) => ({
        status: 200,
        json: request.url.includes('cursor=accounts-2')
          ? { accounts: [{ accountId: 'b', username: 'second', kind: 'TRADER', cash: '1.00', holdings: [], token: null, strategy: null }], nextCursor: null }
          : { accounts: [{ accountId: 'a', username: 'first', kind: 'TRADER', cash: '1.00', holdings: [], token: null, strategy: null }], nextCursor: 'accounts-2' },
      }));
    renderPage();

    const runsPanel = await runs();
    const history = await within(runsPanel).findByRole('table', { name: 'Recent runs' });
    expect(within(history).getByRole('row', { name: /09:00:00 UTC/ })).toBeInTheDocument();
    fireEvent.click(within(runsPanel).getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(within(history).getByRole('row', { name: /08:00:00 UTC/ })).toBeInTheDocument());
    const asked = server.requests.find((request) => request.url.includes('cursor=runs-2'))!.url;
    expect(asked).toContain('limit=10');
    expect(asked).toContain('order=newest');

    const accounts = screen.getByRole('region', { name: 'Accounts' });
    await within(accounts).findByRole('row', { name: /first/ });
    fireEvent.click(within(accounts).getByRole('button', { name: 'Next' }));
    await within(accounts).findByRole('row', { name: /second/ });
    const accountsAsked = server.requests.find((request) => request.url.includes('cursor=accounts-2'))!.url;
    expect(accountsAsked).toContain('limit=25');
    expect(accountsAsked).toContain('order=oldest');
  });

  it('shows how trading keeps up once the market is open, and not during setup', async () => {
    const { server } = exchange('OPEN');
    server.on('GET /api/v1/admin/health', {
      status: 200,
      json: {
        writer: { available: true, queueDepth: 0, queueCapacity: 256 },
        lastMinute: { committed: 0, failed: 0, withinBudget: 0, refused: 0 },
        outbox: { admitting: true, pendingEvents: 0, oldestPendingMillis: 0 },
      },
    });
    server.on('GET /api/v1/instruments', {
      status: 200,
      json: { instruments: [NOVA], feed: { state: 'STALE', lag: 12 } },
    });
    const open = renderPage();
    const health = await screen.findByRole('region', { name: 'Health' });
    expect(await within(health).findByText('Taking commands')).toBeInTheDocument();
    // The page hands over the market data feed and the instruments to check the analytics of.
    expect(await within(health).findByText('Stale · 12 behind')).toBeInTheDocument();
    await waitFor(() =>
      expect(server.requests.some((request) => request.url.startsWith(`/api/v1/instruments/${NOVA.instrumentId}/candles`))).toBe(
        true,
      ),
    );
    open.unmount();

    exchange('SETUP');
    renderPage();
    await screen.findByRole('heading', { name: 'Market' });
    expect(screen.queryByRole('region', { name: 'Health' })).toBeNull();
  });

  it("has a part for every step of the operator's tour once the market is open", async () => {
    const { server } = exchange('OPEN');
    server.on('GET /api/v1/admin/health', {
      status: 200,
      json: {
        writer: { available: true, queueDepth: 0, queueCapacity: 256 },
        lastMinute: { committed: 0, failed: 0, withinBudget: 0, refused: 0 },
        outbox: { admitting: true, pendingEvents: 0, oldestPendingMillis: 0 },
      },
    });
    renderPage();
    await screen.findByRole('region', { name: 'Health' });
    for (const step of TOURS.OPERATOR.steps) {
      expect(document.querySelector(step.anchor), step.title).not.toBeNull();
    }
    expect(screen.getByRole('button', { name: 'What is halted?' })).toBeInTheDocument();
  });

  it('puts bot runs first once the market is open, and after the setup editor before', async () => {
    exchange('OPEN');
    const open = renderPage();
    await runs();
    const openOrder = screen.getAllByRole('region').map((region) => region.getAttribute('aria-labelledby'));
    expect(openOrder.indexOf('runs-title')).toBeLessThan(openOrder.indexOf('instruments-title'));
    open.unmount();

    exchange('SETUP');
    renderPage();
    await runs();
    const setupOrder = screen.getAllByRole('region').map((region) => region.getAttribute('aria-labelledby'));
    expect(setupOrder.indexOf('runs-title')).toBeGreaterThan(setupOrder.indexOf('accounts-title'));
  });
});
