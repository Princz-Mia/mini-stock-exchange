import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { resetSession } from '../api/client';
import type { BotRun, MarketStatus } from '../api/types';
import { TOURS } from '../help/tours';
import { botRun, finishedRun, manifest } from '../test/botRun';
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
  // `bots` are untagged; `tagged` counts bots per strategy.
  const state = {
    market: market(initial),
    bots: 0,
    tagged: { marketMakers: 0, noise: 0, momentum: 0 },
    runs: [] as BotRun[],
    // Trader logins without an account yet.
    unfunded: [] as string[],
  };
  const server = signedIn('operator', ['OPERATOR'])
    .on('GET /api/v1/market', () => ({ status: 200, json: state.market }))
    .on('GET /api/v1/instruments', { status: 200, json: { instruments, feed: { state: 'LIVE', lag: 0 } } })
    .on('GET /api/v1/admin/accounts', { status: 200, json: { accounts: [], nextCursor: null } })
    .on('GET /api/v1/admin/traders', () => ({
      status: 200,
      json: { traders: state.unfunded.map((username) => ({ username, funded: false })), nextCursor: null },
    }))
    .on('GET /api/v1/admin/bots', () => ({
      status: 200,
      json: {
        population: state.bots + state.tagged.marketMakers + state.tagged.noise + state.tagged.momentum,
        maxPerCommand: 100,
        byStrategy: { ...state.tagged, untagged: state.bots },
      },
    }))
    .on('GET /api/v1/admin/bot-runs', () => ({ status: 200, json: { runs: state.runs, nextCursor: null } }));
  return { server, state };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return renderWith(client);
}

function renderAt(at: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return renderWith(client, at);
}

function renderWith(client: QueryClient, at = '/operator') {
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[at]}>
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
    // Focus moves to the refused field in an effect after the message renders, so wait for it.
    await waitFor(() => expect(price).toHaveFocus());
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
    await waitFor(() => expect(name).toHaveFocus());
    expect(price).not.toHaveAttribute('aria-invalid');

    price.focus();
    fireEvent.click(save);
    expect(await screen.findByText(`${badName.message}; ${badPrice.message}`)).toBeInTheDocument();
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(price).toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => expect(name).toHaveFocus());

    fireEvent.click(save);
    expect(await screen.findByText('Instruments can only be changed before the market opens')).toBeInTheDocument();
    expect(name).not.toHaveAttribute('aria-invalid');
    expect(price).not.toHaveAttribute('aria-invalid');
    await waitFor(() => expect(name).toHaveFocus());
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

  it('funds a trader with exactly what was typed into fields that start empty', async () => {
    const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' };
    const HELI = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000003', symbol: 'HELI' };
    const { server } = exchange('SETUP', [NOVA, ORBT, HELI]);
    server.on('POST /api/v1/admin/accounts', {
      status: 201,
      json: { accountId: 'a', username: 'trader', cash: '25000.00', holdings: [], commandSequence: '9' },
    });
    renderPage();

    // Nothing is filled in for the operator; the usual values show only as examples.
    const form = await screen.findByRole('form', { name: 'Fund a trader' });
    const cash = within(form).getByLabelText('Cash');
    const nova = await within(form).findByLabelText('NOVA shares');
    expect(cash).toHaveValue('');
    expect(cash).toHaveAttribute('placeholder', 'e.g. 100000.00');
    expect(nova).toHaveValue('');
    expect(nova).toHaveAttribute('placeholder', 'e.g. 1000');
    expect(within(form).getByLabelText('ORBT shares')).toHaveValue('');
    fireEvent.change(within(form).getByLabelText("Trader's username"), { target: { value: 'trader' } });
    fireEvent.change(cash, { target: { value: '25000.00' } });
    fireEvent.change(nova, { target: { value: '300' } });
    fireEvent.change(within(form).getByLabelText('HELI shares'), { target: { value: '0' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Fund account' }));

    expect(await screen.findByText('Funded trader with 25,000.00.')).toBeInTheDocument();
    // ORBT was left blank and HELI set to 0, so the trader gets none of either.
    expect(server.requests.find((request) => request.url === '/api/v1/admin/accounts' && request.method === 'POST')?.body)
      .toEqual({
        username: 'trader',
        cash: '25000.00',
        holdings: [{ instrumentId: NOVA.instrumentId, quantity: 300 }],
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
            strategy: 'MARKET_MAKER',
          },
          { accountId: 'c', username: 'bot-0002', kind: 'BOT', cash: '5000.00', holdings: [], token: 'ACTIVE', strategy: null },
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
    expect(within(row).queryByText(/bot/)).toBeNull();
    // A bot's tag says its strategy; one made without a strategy is just a bot.
    const bot = screen.getByRole('row', { name: /bot-0001/ });
    expect(within(bot).getByText('market maker bot')).toHaveClass('tag');
    const untagged = screen.getByRole('row', { name: /bot-0002/ });
    expect(within(untagged).getByText('bot')).toHaveClass('tag');
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

  it('creates noise bots with the endowment typed and shows the new population', async () => {
    const { server, state } = exchange('SETUP');
    server.on('POST /api/v1/admin/bots', () => {
      state.tagged.noise = 10;
      return {
        status: 201,
        json: {
          created: 10,
          firstBot: 'bot-0001',
          lastBot: 'bot-0010',
          population: 10,
          strategy: 'NOISE',
          commandSequence: '9',
        },
      };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    expect(await screen.findByText('No bots yet')).toBeInTheDocument();
    // The fields start empty, with examples, and the batch is what the operator typed.
    const count = within(form).getByLabelText('Number of bots');
    const cash = within(form).getByLabelText('Cash each');
    const nova = await within(form).findByLabelText('NOVA shares');
    expect([count, cash, nova].map((field) => (field as HTMLInputElement).value)).toEqual(['', '', '']);
    expect([count, cash, nova].map((field) => field.getAttribute('placeholder'))).toEqual([
      'e.g. 10',
      'e.g. 100000.00',
      'e.g. 1000',
    ]);
    fireEvent.change(count, { target: { value: '10' } });
    fireEvent.change(cash, { target: { value: '100000.00' } });
    fireEvent.change(nova, { target: { value: '1000' } });
    const strategy = within(form).getByRole('group', { name: 'Strategy' });
    expect(within(strategy).getAllByRole('radio').map((radio) => radio.getAttribute('value'))).toEqual([
      'MARKET_MAKER',
      'NOISE',
      'MOMENTUM',
    ]);
    expect(within(strategy).getByRole('radio', { name: 'Noise' })).toBeChecked();
    expect(within(strategy).getByRole('radio', { name: 'Noise' })).toHaveAccessibleDescription(
      'Places random limit orders near the current price.',
    );
    expect(within(form).getByText(/Up to 100 at a time, as many batches as you like/)).toBeInTheDocument();
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(
      await screen.findByText('Created bot-0001 to bot-0010 as noise bots. 10 bots in all.'),
    ).toBeInTheDocument();
    expect(await screen.findByText('10 bots')).toBeInTheDocument();
    expect(screen.getByText('10 noise')).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/admin/bots' && request.method === 'POST');
    expect(sent?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent?.body).toEqual({
      count: 10,
      cash: '100000.00',
      holdings: [{ instrumentId: NOVA.instrumentId, quantity: 1000 }],
      strategy: 'NOISE',
    });
  });

  it('creates bots with the strategy chosen and counts the population by strategy', async () => {
    const { server, state } = exchange('SETUP');
    state.tagged = { marketMakers: 0, noise: 12, momentum: 4 };
    state.bots = 2;
    server.on('POST /api/v1/admin/bots', () => {
      state.tagged.marketMakers = 1;
      return {
        status: 201,
        json: {
          created: 1,
          firstBot: 'bot-0019',
          lastBot: 'bot-0019',
          population: 19,
          strategy: 'MARKET_MAKER',
          commandSequence: '12',
        },
      };
    });
    renderPage();

    expect(await screen.findByText('12 noise · 4 momentum · 2 untagged')).toBeInTheDocument();
    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '1' } });
    fireEvent.change(within(form).getByLabelText('Cash each'), { target: { value: '50000.00' } });
    fireEvent.click(within(form).getByRole('radio', { name: 'Market maker' }));
    expect(within(form).getByRole('radio', { name: 'Noise' })).not.toBeChecked();
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(
      await screen.findByText('Created bot-0019 to bot-0019 as a market maker. 19 bots in all.'),
    ).toBeInTheDocument();
    expect(await screen.findByText('1 market maker · 12 noise · 4 momentum · 2 untagged')).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/admin/bots' && request.method === 'POST');
    expect(sent?.body).toEqual({ count: 1, cash: '50000.00', holdings: [], strategy: 'MARKET_MAKER' });
    // The choice stays for the next batch.
    expect(within(form).getByRole('radio', { name: 'Market maker' })).toBeChecked();
  });

  it('marks the fields a refused batch names, describes them by the refusal and focuses the first', async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/bots', {
      status: 400,
      problem: {
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'The bot request is invalid',
        errors: [
          { field: 'count', code: 'OUT_OF_RANGE', message: 'count must be between 1 and 100' },
          { field: 'cash', code: 'INVALID_AMOUNT', message: 'Amount must be a fixed-scale decimal such as 100.25' },
        ],
      },
    });
    renderPage();

    // Nothing typed: the exchange names both blank fields.
    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));
    const message = 'count must be between 1 and 100; Amount must be a fixed-scale decimal such as 100.25';
    expect(await screen.findByText(message)).toBeInTheDocument();
    const count = within(form).getByLabelText('Number of bots');
    const cash = within(form).getByLabelText('Cash each');
    for (const field of [count, cash]) {
      expect(field).toHaveAttribute('aria-invalid', 'true');
      expect(field).toHaveAccessibleDescription(message);
    }
    expect(within(form).getByLabelText('NOVA shares')).not.toHaveAttribute('aria-invalid');
    await waitFor(() => expect(count).toHaveFocus());
  });

  it("marks the instrument whose shares a refused funding names, by the holdings sent", async () => {
    const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' };
    const { server } = exchange('SETUP', [NOVA, ORBT]);
    server.on('POST /api/v1/admin/accounts', {
      status: 400,
      problem: {
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'The account request is invalid',
        errors: [{ field: 'holdings[0].quantity', code: 'OUT_OF_RANGE', message: 'quantity must be between 1 and 1000000' }],
      },
    });
    renderPage();

    // NOVA is left blank, so the first holding sent is ORBT's.
    const form = await screen.findByRole('form', { name: 'Fund a trader' });
    fireEvent.change(within(form).getByLabelText("Trader's username"), { target: { value: 'trader' } });
    fireEvent.change(within(form).getByLabelText('Cash'), { target: { value: '1.00' } });
    fireEvent.change(await within(form).findByLabelText('ORBT shares'), { target: { value: '00' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Fund account' }));

    expect(await screen.findByText('quantity must be between 1 and 1000000')).toBeInTheDocument();
    const orbt = within(form).getByLabelText('ORBT shares');
    expect(orbt).toHaveAttribute('aria-invalid', 'true');
    expect(orbt).toHaveAccessibleDescription('quantity must be between 1 and 1000000');
    expect(within(form).getByLabelText('NOVA shares')).not.toHaveAttribute('aria-invalid');
    expect(within(form).getByLabelText('Cash')).not.toHaveAttribute('aria-invalid');
    expect(orbt).toHaveValue('00');
    await waitFor(() => expect(orbt).toHaveFocus());
  });

  it("shows a refused bot request in the exchange's words and keeps the input", async () => {
    const { server } = exchange('SETUP');
    // The population has no limit now; this refusal is one a retry can still replay from before.
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

  it('keeps creating bots however many there are: no limit, no meter', async () => {
    const { state } = exchange('SETUP');
    state.bots = 10_000;
    const { container } = renderPage();

    expect(await screen.findByText('10,000 bots')).toBeInTheDocument();
    expect(screen.getByText('10,000 untagged')).toBeInTheDocument();
    const create = await button('Create bots');
    expect(create).toBeEnabled();
    expect(create).not.toHaveAttribute('aria-describedby');
    expect(container.querySelector('meter')).toBeNull();
    const panel = screen.getByRole('region', { name: 'Bots' });
    expect(within(panel).queryByText(/ of [\d,]+ bots|at its limit|more in all/)).toBeNull();
  });

  it('counts a single bot as one', async () => {
    const { state } = exchange('SETUP');
    state.bots = 1;
    renderPage();

    expect(await screen.findByText('1 bot')).toBeInTheDocument();
  });

  it('fixes the bot population once the market opens', async () => {
    const { state } = exchange('OPEN');
    state.bots = 12;
    renderPage();

    expect(await screen.findByText('12 bots')).toBeInTheDocument();
    expect(screen.getByText(/the population is fixed after that/)).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Create bots' })).toBeNull();
  });
});

describe('adding traders on the operator screen', () => {
  beforeEach(() => resetSession());

  const writeText = vi.fn<(text: string) => Promise<void>>();
  beforeEach(() => {
    writeText.mockReset();
    writeText.mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  });
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'clipboard');
  });

  const addForm = () => screen.findByRole('form', { name: 'Add a trader' });

  it('adds a trader with a generated password, shown and copied, then clears it', async () => {
    const { server, state } = exchange('SETUP');
    server.on('POST /api/v1/admin/traders', () => {
      state.unfunded = ['omar'];
      return {
        status: 201,
        json: { username: 'omar', accountId: null, cash: null, holdings: [], commandSequence: '7' },
      };
    });
    renderPage();

    const form = await addForm();
    const password = within(form).getByLabelText('Password');
    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveAttribute('autocomplete', 'new-password');
    expect(password).toHaveAccessibleDescription(/12 to 64 characters, not the username/);
    expect(within(form).getByRole('button', { name: 'Copy' })).toBeDisabled();

    // Generate fills in twenty readable characters and shows them, so they can be read or copied.
    fireEvent.click(within(form).getByRole('button', { name: 'Generate' }));
    const generated = (password as HTMLInputElement).value;
    expect(generated).toMatch(/^[a-km-np-zA-HJ-NP-Z2-9]{20}$/);
    expect(password).toHaveAttribute('type', 'text');
    expect(within(form).getByRole('button', { name: 'Show password' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(within(form).getByRole('button', { name: 'Copy' }));
    expect(await within(form).findByText('Password copied.')).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledWith(generated);
    // Show is a toggle; it is left on here, so adding has to hide the field again.
    fireEvent.click(within(form).getByRole('button', { name: 'Show password' }));
    expect(password).toHaveAttribute('type', 'password');
    fireEvent.click(within(form).getByRole('button', { name: 'Show password' }));
    expect(password).toHaveAttribute('type', 'text');

    fireEvent.change(within(form).getByLabelText('Username'), { target: { value: 'omar' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add trader' }));

    expect(
      await screen.findByText('Added omar, without an account yet: fund them below before the market opens.'),
    ).toBeInTheDocument();
    const sent = server.requests.find((request) => request.url === '/api/v1/admin/traders' && request.method === 'POST');
    expect(sent?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    // Without cash or shares, only the login is asked for.
    expect(sent?.body).toEqual({ username: 'omar', password: generated });
    // The password is gone from the form, hidden again, and the trader is now listed for funding.
    expect(password).toHaveValue('');
    expect(password).toHaveAttribute('type', 'password');
    expect(within(form).getByLabelText('Username')).toHaveValue('');
    expect(await screen.findByRole('rowheader', { name: 'omar' })).toBeInTheDocument();
    // Nothing of it was kept in the browser.
    expect(JSON.stringify({ ...window.localStorage })).not.toContain(generated);
    // eslint-disable-next-line no-restricted-properties -- only reads it, to show nothing went there
    expect(JSON.stringify({ ...window.sessionStorage })).not.toContain(generated);
  });

  it('adds a trader with a typed password and a starting endowment', async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/traders', {
      status: 201,
      json: {
        username: 'maria',
        accountId: 'a',
        cash: '25000.00',
        holdings: [{ instrumentId: NOVA.instrumentId, quantity: 300 }],
        commandSequence: '8',
      },
    });
    renderPage();

    const form = await addForm();
    fireEvent.change(within(form).getByLabelText('Username'), { target: { value: ' maria ' } });
    fireEvent.change(within(form).getByLabelText('Password'), { target: { value: 'a typed password' } });
    fireEvent.change(within(form).getByLabelText('Cash (optional)'), { target: { value: '25000.00' } });
    fireEvent.change(await within(form).findByLabelText('NOVA shares'), { target: { value: '300' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add trader' }));

    expect(await screen.findByText('Added maria with 25,000.00.')).toBeInTheDocument();
    expect(server.requests.find((request) => request.url === '/api/v1/admin/traders')?.body).toEqual({
      username: 'maria',
      password: 'a typed password',
      cash: '25000.00',
      holdings: [{ instrumentId: NOVA.instrumentId, quantity: 300 }],
    });
  });

  it('gives a trader with shares but no cash an account with 0.00', async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/traders', {
      status: 201,
      json: {
        username: 'lena',
        accountId: 'b',
        cash: '0.00',
        holdings: [{ instrumentId: NOVA.instrumentId, quantity: 50 }],
        commandSequence: '9',
      },
    });
    renderPage();

    const form = await addForm();
    fireEvent.change(within(form).getByLabelText('Username'), { target: { value: 'lena' } });
    fireEvent.change(within(form).getByLabelText('Password'), { target: { value: 'a typed password' } });
    fireEvent.change(await within(form).findByLabelText('NOVA shares'), { target: { value: '50' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add trader' }));

    expect(await screen.findByText('Added lena with 0.00.')).toBeInTheDocument();
    expect(server.requests.find((request) => request.url === '/api/v1/admin/traders')?.body).toEqual({
      username: 'lena',
      password: 'a typed password',
      cash: '0.00',
      holdings: [{ instrumentId: NOVA.instrumentId, quantity: 50 }],
    });
  });

  it('marks and focuses the field a refused trader names, keeping what was typed', async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/traders', {
      status: 400,
      problem: {
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'The trader request is invalid',
        errors: [{ field: 'password', code: 'INVALID_PASSWORD', message: 'password must be 12 to 64 characters' }],
      },
    });
    renderPage();

    const form = await addForm();
    fireEvent.change(within(form).getByLabelText('Username'), { target: { value: 'lena' } });
    fireEvent.change(within(form).getByLabelText('Password'), { target: { value: 'short' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add trader' }));

    expect(await screen.findByText('password must be 12 to 64 characters')).toBeInTheDocument();
    const password = within(form).getByLabelText('Password');
    expect(password).toHaveAttribute('aria-invalid', 'true');
    expect(password).toHaveAccessibleDescription(/12 to 64 characters, not the username.*password must be 12 to 64/);
    expect(within(form).getByLabelText('Username')).not.toHaveAttribute('aria-invalid');
    expect(password).toHaveValue('short');
    await waitFor(() => expect(password).toHaveFocus());
  });

  it('says when the password could not be copied', async () => {
    exchange('SETUP');
    writeText.mockRejectedValue(new Error('denied'));
    renderPage();

    const form = await addForm();
    fireEvent.click(within(form).getByRole('button', { name: 'Generate' }));
    fireEvent.click(within(form).getByRole('button', { name: 'Copy' }));
    expect(
      await within(form).findByText("The password couldn't be copied; select it and copy it by hand."),
    ).toBeInTheDocument();
  });

  it('lists traders without an account, and Fund picks one for the funding form', async () => {
    const { server, state } = exchange('SETUP');
    state.unfunded = ['omar', 'lena'];
    renderPage();

    const list = await screen.findByRole('table', { name: 'Traders without an account' });
    expect(within(list).getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual(['omar', 'lena']);
    const asked = server.requests.find((request) => request.url.startsWith('/api/v1/admin/traders'))!;
    expect(asked.url).toContain('funded=false');
    expect(asked.url).toContain('limit=10');
    fireEvent.click(within(list).getByRole('button', { name: 'Fund lena' }));

    const fund = screen.getByRole('form', { name: 'Fund a trader' });
    expect(within(fund).getByLabelText("Trader's username")).toHaveValue('lena');
    expect(within(fund).getByLabelText('Cash')).toHaveFocus();
  });

  it('drops a funded trader from the list, and Fund waits while a funding is unconfirmed', async () => {
    const { server, state } = exchange('SETUP');
    state.unfunded = ['omar', 'lena'];
    let answers = 0;
    server.on('POST /api/v1/admin/accounts', () => {
      answers += 1;
      if (answers === 1) {
        return { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY' } };
      }
      state.unfunded = ['omar'];
      return {
        status: 201,
        json: { accountId: 'l', username: 'lena', cash: '500.00', holdings: [], commandSequence: '11' },
      };
    });
    renderPage();

    const list = await screen.findByRole('table', { name: 'Traders without an account' });
    fireEvent.click(within(list).getByRole('button', { name: 'Fund lena' }));
    const fund = screen.getByRole('form', { name: 'Fund a trader' });
    fireEvent.change(within(fund).getByLabelText('Cash'), { target: { value: '500.00' } });
    fireEvent.click(within(fund).getByRole('button', { name: 'Fund account' }));

    // Unconfirmed: Retry would fund lena again under the same key, so Fund can't swap in another trader meanwhile.
    expect(await screen.findByText(/Not confirmed/)).toBeInTheDocument();
    expect(within(list).getByRole('button', { name: 'Fund omar' })).toBeDisabled();
    expect(within(fund).getByLabelText("Trader's username")).toHaveValue('lena');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('Funded lena with 500.00.')).toBeInTheDocument();
    await waitFor(() => expect(within(list).queryByRole('rowheader', { name: 'lena' })).toBeNull());
    expect(within(list).getByRole('button', { name: 'Fund omar' })).toBeEnabled();
  });

  it('says when every trader has an account', async () => {
    exchange('SETUP');
    renderPage();
    expect(await screen.findByText('Every trader has an account.')).toBeInTheDocument();
  });

  it('hides adding and funding once the market is open', async () => {
    exchange('OPEN');
    renderPage();
    await screen.findByText(/Endowments are frozen once the market opens/);
    expect(screen.queryByRole('form', { name: 'Add a trader' })).toBeNull();
    expect(screen.queryByRole('table', { name: 'Traders without an account' })).toBeNull();
  });
});

describe('bot runs on the operator screen', () => {
  beforeEach(() => resetSession());
  afterEach(() => vi.useRealTimers());

  const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' };
  const runs = () => screen.findByRole('region', { name: 'Bot runs' });
  /** Sets the browser's clock and nothing else: timers still run, so the screen keeps asking every two seconds. */
  const clockAt = (time: string) => vi.setSystemTime(new Date(time));

  it('ticks finished runs for a comparison, four at most, and links to it', async () => {
    const { state } = exchange('OPEN');
    const finished = (n: number, minute: number) =>
      finishedRun({ runId: `20000000-0000-0000-0000-00000000000${n}`, startedAt: `2026-11-06T0${minute}:00:00Z` });
    const unconfirmed = botRun({
      runId: '20000000-0000-0000-0000-000000000009',
      status: 'STOPPED',
      desiredState: 'STOPPED',
      startedAt: '2026-11-06T09:30:00Z',
    });
    state.runs = [unconfirmed, finished(5, 5), finished(4, 4), finished(3, 3), finished(2, 2), finished(1, 1)];
    renderPage();

    const panel = await runs();
    await within(panel).findByRole('table', { name: 'Recent runs' });
    expect(within(panel).getByText('Tick two to four finished runs to compare them.')).toBeInTheDocument();
    const box = (time: string) => within(panel).getByRole('checkbox', { name: new RegExp(`started ${time}`) });
    expect(box('09:30:00')).toBeDisabled();
    expect(box('09:30:00')).toHaveAccessibleDescription('Not finished');
    expect(within(panel).getByText('Not finished')).toBeVisible();

    fireEvent.click(box('05:00:00'));
    fireEvent.click(box('04:00:00'));
    expect(within(panel).getByRole('link', { name: 'Compare 2 runs' })).toHaveAttribute(
      'href',
      '/operator/runs/compare?runs=20000000-0000-0000-0000-000000000005,20000000-0000-0000-0000-000000000004',
    );
    fireEvent.click(box('03:00:00'));
    fireEvent.click(box('02:00:00'));
    expect(box('01:00:00')).toBeDisabled();
    expect(box('01:00:00')).toHaveAccessibleDescription('4 ticked');
    // Unticking makes room again; clearing unticks them all.
    fireEvent.click(box('04:00:00'));
    expect(box('01:00:00')).toBeEnabled();
    expect(within(panel).getByRole('link', { name: 'Compare 3 runs' })).toBeInTheDocument();
    fireEvent.click(within(panel).getByRole('button', { name: 'Clear 3 ticks' }));
    expect(box('05:00:00')).not.toBeChecked();
    expect(within(panel).getByText('Tick two to four finished runs to compare them.')).toBeInTheDocument();
  });

  it('finds its ticks in the address, so coming back from a comparison keeps them', async () => {
    const { state } = exchange('OPEN');
    const finished = (n: number, minute: number) =>
      finishedRun({ runId: `20000000-0000-0000-0000-00000000000${n}`, startedAt: `2026-11-06T0${minute}:00:00Z` });
    state.runs = [finished(3, 3), finished(2, 2), finished(1, 1)];
    renderAt(`/operator?runsSize=25&compare=20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000001`);

    const panel = await runs();
    await within(panel).findByRole('table', { name: 'Recent runs' });
    const box = (time: string) => within(panel).getByRole('checkbox', { name: new RegExp(`started ${time}`) });
    expect(box('03:00:00')).toBeChecked();
    expect(box('02:00:00')).not.toBeChecked();
    expect(box('01:00:00')).toBeChecked();
    expect(within(panel).getByRole('link', { name: 'Compare 2 runs' })).toHaveAttribute(
      'href',
      '/operator/runs/compare?runs=20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000001',
    );
  });

  it('starts a run with every tagged bot on its strategy, sending only what the exchange needs', async () => {
    const { server, state } = exchange('OPEN', [NOVA, ORBT]);
    state.tagged = { marketMakers: 2, noise: 6, momentum: 2 };
    state.bots = 1;
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
    expect(
      within(form).getByText(/11 bots exist: 2 market makers · 6 noise · 2 momentum · 1 untagged\. Each strategy is run by/),
    ).toBeInTheDocument();
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
      durationMinutes: 60,
    });
  });

  it('starts a run for an hour unless told otherwise, and never reads an empty duration as no end', async () => {
    const { server, state } = exchange('OPEN');
    state.tagged = { marketMakers: 1, noise: 1, momentum: 0 };
    server.on('POST /api/v1/admin/bot-runs', {
      status: 201,
      json: { runId: botRun().runId, bots: 2, commandSequence: '9' },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    await waitFor(() => expect(within(form).getByLabelText('Noise')).toHaveValue('1'));
    const length = within(form).getByRole('group', { name: 'Run length' });
    expect(within(length).getByRole('radio', { name: 'For a set time' })).toBeChecked();
    expect(within(length).getByRole('radio', { name: 'For a set time' })).toHaveAccessibleDescription(
      'Ends by itself after the minutes given, from 1 to 1440 (24 hours).',
    );
    expect(within(length).getByRole('radio', { name: 'Until I stop it' })).not.toBeChecked();
    const minutes = within(length).getByLabelText('Duration in minutes');
    expect(minutes).toHaveValue('60');
    expect(minutes).toBeEnabled();

    const posted = () => server.requests.filter((request) => request.method === 'POST' && request.url.endsWith('/bot-runs'));
    fireEvent.change(minutes, { target: { value: '' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    expect(await within(form).findByText('Duration must be a whole number.')).toBeInTheDocument();
    expect(posted()).toHaveLength(0);

    fireEvent.change(minutes, { target: { value: '15' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    expect(await within(form).findByText('Started a run of 2 bots.')).toBeInTheDocument();
    expect(within(form).queryByText('Duration must be a whole number.')).toBeNull();
    expect(posted()).toHaveLength(1);
    expect(posted()[0]?.body).toEqual({
      marketMakers: 1,
      noise: 1,
      momentum: 0,
      commandsPerSecond: '4.00',
      maxOrderQuantity: 10,
      maxPosition: 100,
      durationMinutes: 15,
    });
  });

  it('starts a run with no end only when that is chosen, and says what it means', async () => {
    const { server, state } = exchange('OPEN');
    state.tagged = { marketMakers: 1, noise: 1, momentum: 0 };
    server.on('POST /api/v1/admin/bot-runs', {
      status: 201,
      json: { runId: botRun().runId, bots: 2, commandSequence: '9' },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    await waitFor(() => expect(within(form).getByLabelText('Noise')).toHaveValue('1'));
    const untilStopped = within(form).getByRole('radio', { name: 'Until I stop it' });
    const minutes = within(form).getByLabelText('Duration in minutes');
    fireEvent.click(untilStopped);
    expect(untilStopped).toBeChecked();
    expect(within(form).getByRole('radio', { name: 'For a set time' })).not.toBeChecked();
    expect(untilStopped).toHaveAccessibleDescription(
      'Keeps trading until someone selects Stop run, and takes up again after every restart.',
    );
    // The minutes stay as they were for a change of mind, but can't be typed and aren't sent.
    expect(minutes).toBeDisabled();
    expect(minutes).toHaveValue('60');

    const posted = () => server.requests.filter((request) => request.method === 'POST' && request.url.endsWith('/bot-runs'));
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    await waitFor(() => expect(posted()).toHaveLength(1));
    expect(posted()[0]?.body).toEqual({
      marketMakers: 1,
      noise: 1,
      momentum: 0,
      commandsPerSecond: '4.00',
      maxOrderQuantity: 10,
      maxPosition: 100,
    });
    expect(await within(form).findByText('Started a run of 2 bots.')).toBeInTheDocument();

    fireEvent.click(within(form).getByRole('radio', { name: 'For a set time' }));
    expect(minutes).toBeEnabled();
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    await waitFor(() => expect(posted()).toHaveLength(2));
    expect(posted()[1]?.body).toMatchObject({ durationMinutes: 60 });
  });

  it('starts on the chosen instruments only, and never sends an empty choice', async () => {
    const { server, state } = exchange('OPEN', [NOVA, ORBT]);
    state.tagged = { marketMakers: 0, noise: 3, momentum: 0 };
    server.on('POST /api/v1/admin/bot-runs', {
      status: 422,
      problem: {
        status: 422,
        code: 'NOT_ENOUGH_BOTS',
        detail: 'The run asks for 3 noise bots; 2 are tagged',
        shortfall: { strategy: 'NOISE', requested: 3, available: 2 },
      },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    fireEvent.click(within(form).getByRole('checkbox', { name: 'ORBT' }));
    fireEvent.click(within(form).getByRole('checkbox', { name: 'NOVA' }));
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    expect(await within(form).findByText('Choose at least one instrument.')).toBeInTheDocument();
    expect(server.requests.some((request) => request.method === 'POST')).toBe(false);

    fireEvent.click(within(form).getByRole('checkbox', { name: 'NOVA' }));
    // More noise bots than are tagged as noise is blocked before anything is sent, and says so.
    fireEvent.change(within(form).getByLabelText('Noise'), { target: { value: '5' } });
    const start = within(form).getByRole('button', { name: 'Start run' });
    expect(start).toBeDisabled();
    expect(start).toHaveAccessibleDescription('Only 3 noise bots exist.');
    fireEvent.change(within(form).getByLabelText('Noise'), { target: { value: '3' } });
    fireEvent.change(within(form).getByLabelText('Seed'), { target: { value: '42' } });
    expect(start).toBeEnabled();
    fireEvent.click(start);

    // The exchange counts again when it starts the run, and its answer is shown in its words.
    expect(await within(form).findByText('The run asks for 3 noise bots; 2 are tagged')).toBeInTheDocument();
    expect(within(form).queryByText('Choose at least one instrument.')).toBeNull();
    const sent = server.requests.filter((request) => request.method === 'POST' && request.url.endsWith('/bot-runs'));
    expect(sent).toHaveLength(1);
    expect(sent[0]?.body).toMatchObject({ noise: 3, seed: 42, instrumentIds: [NOVA.instrumentId] });
    expect(within(form).getByLabelText('Noise')).toHaveValue('3');
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

  it('counts a single bot as one', async () => {
    const { state } = exchange('OPEN');
    state.tagged = { marketMakers: 1, noise: 0, momentum: 0 };
    renderPage();
    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    expect(await within(form).findByText(/^1 bot exists: 1 market maker\. Each strategy/)).toBeInTheDocument();
  });

  it('cannot start when no bot has a strategy, and says why', async () => {
    const { state } = exchange('OPEN');
    state.bots = 5;
    renderPage();
    const start = await button('Start run');
    await waitFor(() =>
      expect(start).toHaveAccessibleDescription('No bot has a strategy, and runs use only bots tagged with one.'),
    );
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
    // Its worker hasn't reported its simulator yet: nothing to open.
    expect(counters).toHaveTextContent('SimulatorNot reported yet');
    expect(within(panel).queryByText('Simulator settings')).toBeNull();
  });

  it('says how long the run has been going and how long a timed one has left, as of the last answer', async () => {
    const { state } = exchange('OPEN');
    const timed = botRun({ endsAt: '2026-11-06T11:00:00Z' });
    state.runs = [timed];
    // Its worker last reported at 10:01:20; the run is twelve and a half minutes old all the same.
    clockAt('2026-11-06T10:12:30Z');
    renderPage();

    const current = await within(await runs()).findByRole('group', { name: 'Current run' });
    expect(current).toHaveTextContent('Started10:00:00 UTCElapsed12 min 30 sEnds11:00:00 UTCTime left47 min 30 sLast');
    // Each answer moves both; nothing on the screen counts between answers.
    clockAt('2026-11-06T10:59:59Z');
    await waitFor(() => expect(current).toHaveTextContent('Elapsed59 min 59 sEnds11:00:00 UTCTime left1 sLast'), {
      timeout: 10_000,
    });
    clockAt('2026-11-06T11:00:04Z');
    await waitFor(() => expect(current).toHaveTextContent('Elapsed1 h 0 minEnds11:00:00 UTCTime leftEnding now'), {
      timeout: 10_000,
    });
    // The exchange asks for the stop at the end time; from then on there is nothing left to count down.
    state.runs = [{ ...timed, status: 'STOPPING', desiredState: 'STOPPED' }];
    await waitFor(() => expect(current).toHaveTextContent('Elapsed1 h 0 minEnds11:00:00 UTCLast heartbeat'), {
      timeout: 10_000,
    });
  }, 20_000);

  it('counts a run with no end from its start, in days once it is that old, with no time left to show', async () => {
    const { state } = exchange('OPEN');
    state.runs = [botRun({ startedAt: '2026-11-10T10:00:00Z', status: 'STALE' })];
    clockAt('2026-11-23T14:30:00Z');
    renderPage();

    const current = await within(await runs()).findByRole('group', { name: 'Current run' });
    expect(current).toHaveTextContent('Started10:00:00 UTCElapsed13 d 4 hEndsWhen stoppedLast heartbeat');
    expect(current).not.toHaveTextContent('Time left');
  });

  const LEFT_RUNNING =
    'Running for more than a day with no end time. It keeps trading until someone stops it, and takes up again ' +
    'after every restart. Select Stop run to end it.';

  it('warns once a run with no end has traded for more than a day, in words that stay the same', async () => {
    const { state } = exchange('OPEN');
    state.runs = [botRun()];
    // A day to the second: not yet.
    clockAt('2026-11-07T10:00:00Z');
    renderPage();

    const current = await within(await runs()).findByRole('group', { name: 'Current run' });
    expect(current).toHaveTextContent('Elapsed1 d 0 hEndsWhen stopped');
    expect(within(current).queryByText(/no end time/)).toBeNull();
    expect(within(current).getByRole('button', { name: 'Stop run' })).not.toHaveAccessibleDescription();

    clockAt('2026-11-07T10:00:01Z');
    const lead = await within(current).findByText('Running for more than a day with no end time.', {}, { timeout: 10_000 });
    const warning = lead.closest('p');
    expect(warning).toHaveTextContent(LEFT_RUNNING);
    // Said in words and marked up as a warning, not by its tint alone; announced politely, and read with the action.
    expect(lead.tagName).toBe('STRONG');
    expect(warning).toHaveClass('chart-note');
    expect(warning).toHaveAttribute('role', 'status');
    expect(within(current).getByRole('button', { name: 'Stop run' })).toHaveAccessibleDescription(LEFT_RUNNING);
    expect(within(current).getByRole('button', { name: 'Stop run' })).toBeEnabled();

    // Later answers move the time elapsed and leave the warning as it is, so it is announced once.
    clockAt('2026-11-08T13:00:00Z');
    await waitFor(() => expect(current).toHaveTextContent('Elapsed2 d 3 h'), { timeout: 10_000 });
    expect(within(current).getByText('Running for more than a day with no end time.').closest('p')).toBe(warning);
    expect(warning?.textContent).toBe(LEFT_RUNNING);
  }, 20_000);

  it('does not warn once a stop is asked for, nor ever about a timed run', async () => {
    const { state } = exchange('OPEN');
    state.runs = [botRun()];
    clockAt('2026-11-08T10:00:00Z');
    const view = renderPage();

    const current = await within(await runs()).findByRole('group', { name: 'Current run' });
    expect(await within(current).findByText(/no end time/)).toBeInTheDocument();
    state.runs = [botRun({ status: 'STOPPING', desiredState: 'STOPPED', stopRequestedAt: '2026-11-08T09:59:50Z' })];
    await waitFor(() => expect(within(current).queryByText(/no end time/)).toBeNull(), { timeout: 10_000 });
    expect(within(current).getByRole('button', { name: 'Stop run' })).toHaveAccessibleDescription(
      'A stop was asked for at 09:59:50 UTC; the run shows Stopped once the worker confirms.',
    );
    view.unmount();

    // The longest timed run, a day, half a minute past its end and not yet asked to stop: it ends by itself.
    state.runs = [botRun({ endsAt: '2026-11-07T10:00:00Z' })];
    clockAt('2026-11-07T10:00:30Z');
    renderPage();
    const timed = await within(await runs()).findByRole('group', { name: 'Current run' });
    expect(timed).toHaveTextContent('Elapsed1 d 0 hEnds10:00:00 UTCTime leftEnding now');
    expect(within(timed).queryByText(/no end time/)).toBeNull();
    expect(within(timed).getByRole('button', { name: 'Stop run' })).not.toHaveAccessibleDescription();
  }, 20_000);

  it("shows the simulator a running run's worker reported, and when another took over", async () => {
    const { state } = exchange('OPEN');
    state.runs = [botRun({ manifest: manifest() })];
    renderPage();

    const current = await within(await runs()).findByRole('group', { name: 'Current run' });
    expect(current).toHaveTextContent('Simulator0.1.0+27a70d6c1e2f');
    expect(within(current).queryByRole('table', { name: 'Simulator settings' })).toBeNull();
    fireEvent.click(within(current).getByText('Simulator settings'));
    const settings = await within(current).findByRole('table', { name: 'Simulator settings' });
    expect(within(settings).getByRole('columnheader', { name: 'Value' })).toBeInTheDocument();
    expect(within(settings).getByRole('rowheader', { name: 'Momentum' })).toBeInTheDocument();
    expect(within(settings).getByRole('row', { name: /^Defaults: maxInFlight/ })).toHaveTextContent('32');
    // One run alone differs from nothing.
    expect(within(settings).queryByText('Differs')).toBeNull();
    expect(within(current).queryByText(/changed during the run/)).toBeNull();

    state.runs = [botRun({ manifest: manifest({ reports: 2, workerId: 'sim-2' }) })];
    expect(
      await within(current).findByText(
        'The simulator changed during the run: 2 different reports. The version and settings shown are the first, from sim-2 at 10:00:01 UTC.',
        {},
        { timeout: 10_000 },
      ),
    ).toBeInTheDocument();
  }, 15_000);

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
