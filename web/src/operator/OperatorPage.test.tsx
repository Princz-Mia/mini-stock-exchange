import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { resetSession } from '../api/client';
import type { BotJob, BotRun, MarketStatus } from '../api/types';
import { TOURS } from '../help/tours';
import { botJob } from '../test/botJob';
import { botRun, finishedRun, manifest } from '../test/botRun';
import { signedIn } from '../test/fakeServer';
import { noTrades } from '../test/fakeStream';
import { WAITING_FAST_FOR_MS } from './addedInstruments';
import { inUseMessage } from './InstrumentSetup';
import { closedText, unfinishedText } from './MarketControl';
import { OperatorPage, restingOrdersText } from './OperatorPage';

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

const SIMULATION = '10000000-0000-0000-0000-000000000001';
/** The instrument list as the page asks for it: this market's, by its simulation. */
const LIST = `/api/v1/instruments?simulationId=${SIMULATION}`;

function market(state: MarketStatus['state']): MarketStatus {
  return state === 'NOT_SET_UP'
    ? {
        state,
        simulationId: null,
        name: null,
        openedAt: null,
        closesAt: null,
        closedAt: null,
        closedOnSchedule: false,
        commandSequence: null,
      }
    : {
        state,
        simulationId: SIMULATION,
        name: null,
        openedAt: state === 'SETUP' ? null : '2026-11-05T09:30:00Z',
        closesAt: null,
        closedAt: state === 'CLOSED' ? '2026-11-05T16:00:00Z' : null,
        closedOnSchedule: false,
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
    // The exchange's clock, sent with its answers about the runs as the Date header; none when null.
    clock: null as string | null,
    // Trader logins without an account yet.
    unfunded: [] as string[],
    // The newest bot job, as the exchange reads it; none until one is started.
    job: null as BotJob | null,
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
    .on('GET /api/v1/admin/bot-runs', () => ({
      status: 200,
      json: { runs: state.runs, nextCursor: null },
      ...(state.clock === null ? {} : { headers: { Date: new Date(state.clock).toUTCString() } }),
    }))
    .on('GET /api/v1/admin/bot-jobs/current', () => (state.job === null ? { status: 204 } : { status: 200, json: state.job }));
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

    // Not before the instruments are read: a market is not said to have none while its list is on its way.
    expect(await button('Open market')).toHaveAccessibleDescription('Reading the instruments…');
    await waitFor(async () => expect(await button('Open market')).toBeEnabled());
    expect(await button('Halt trading')).toBeDisabled();
    expect(await button('Halt trading')).toHaveAccessibleDescription('Only an open market can be halted.');
    expect(await button('Resume trading')).toHaveAccessibleDescription('Only a halted market can be resumed.');
    expect(screen.getByText('Opening freezes the instruments and every account\'s endowment.')).toBeInTheDocument();
  });

  it("names the market in its panel, and says when it has no name or there is no market", async () => {
    const { state } = exchange('SETUP');
    state.market = { ...market('SETUP'), name: 'Thursday seminar' };
    const named = renderPage();
    const panel = await screen.findByRole('region', { name: 'Market' });
    await waitFor(() => expect(within(panel).getByText('Name', { selector: 'dt' }).nextElementSibling).toHaveTextContent('Thursday seminar'));
    named.unmount();

    for (const [state, said] of [['OPEN', 'Not named'], ['NOT_SET_UP', 'No market yet']] as const) {
      exchange(state, state === 'OPEN' ? [NOVA] : []);
      const view = renderPage();
      const each = await screen.findByRole('region', { name: 'Market' });
      expect(within(each).getByText('Name', { selector: 'dt' }).nextElementSibling).toHaveTextContent(said);
      view.unmount();
    }
  });

  it('cannot open a market without instruments', async () => {
    exchange('NOT_SET_UP', []);
    renderPage();

    expect(await button('Open market')).toHaveAccessibleDescription(
      'Start a simulation or add an instrument first; either creates the market.',
    );
  });

  it('cannot open a market in setup until it has an instrument', async () => {
    exchange('SETUP', []);
    renderPage();

    expect(await button('Open market')).toBeDisabled();
    expect(await button('Open market')).toHaveAccessibleDescription('Add at least one instrument first.');
  });

  it("lists this market's instruments, asked for by the simulation the status names", async () => {
    const { server } = exchange('SETUP');
    renderPage();

    await screen.findByRole('table', { name: 'Instruments' });
    const asked = server.requests.filter((request) => request.url.split('?')[0] === '/api/v1/instruments');
    expect(asked.some((request) => request.url === LIST)).toBe(true);
    // Without the simulation only once, at the same time as the status, and that list says of no simulation whose it
    // is, so it is never shown.
    expect(asked.filter((request) => request.url !== LIST).map((request) => request.url)).toEqual(['/api/v1/instruments']);
  });

  it('shows no list while there is no market, whatever Market Data still holds', async () => {
    // Market Data answers with NOVA, of some simulation the exchange no longer has.
    const { server } = exchange('NOT_SET_UP');
    server.on('GET /api/v1/instruments', {
      status: 200,
      json: { simulationId: '10000000-0000-0000-0000-000000000009', instruments: [NOVA], feed: { state: 'LIVE', lag: 0 } },
    });
    renderPage();

    expect(await button('Open market')).toHaveAccessibleDescription('Start a simulation or add an instrument first; either creates the market.');
    await screen.findByText('No accounts yet.');
    expect(screen.getByText('No instruments yet.')).toBeInTheDocument();
    expect(screen.queryByRole('rowheader', { name: 'NOVA' })).toBeNull();
    // Only the early read made while the status was on its way, and nothing after it.
    expect(server.requests.filter((request) => request.url.startsWith('/api/v1/instruments')).map((request) => request.url)).toEqual([
      '/api/v1/instruments',
    ]);
  });

  it('reads the instruments again when the exchange names another simulation, showing none of the one before', async () => {
    const NEXT = '10000000-0000-0000-0000-000000000002';
    const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT' };
    const { server, state } = exchange('CLOSED');
    server.on('GET /api/v1/instruments', (request) => ({
      status: 200,
      json: { instruments: request.url.endsWith(NEXT) ? [ORBT] : [NOVA], feed: { state: 'LIVE', lag: 0 } },
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);
    expect(await screen.findByRole('rowheader', { name: 'NOVA' })).toBeInTheDocument();

    state.market = { ...market('SETUP'), simulationId: NEXT };
    await client.invalidateQueries({ queryKey: ['market'] });

    expect(await screen.findByRole('rowheader', { name: 'ORBT' })).toBeInTheDocument();
    expect(screen.queryByRole('rowheader', { name: 'NOVA' })).toBeNull();
    expect(server.requests.filter((request) => request.url.startsWith('/api/v1/instruments')).at(-1)?.url).toBe(
      `/api/v1/instruments?simulationId=${NEXT}`,
    );
  });

  it('shows a closed market as ended: when it closed, what stays to read, and nothing to operate', async () => {
    const { server, state } = exchange('CLOSED');
    state.tagged = { marketMakers: 1, noise: 2, momentum: 0 };
    renderPage();

    expect(await screen.findByText('Closed', { selector: '.state-badge' })).toHaveClass('state-closed');
    // Read days later, perhaps: both times carry their dates.
    expect(screen.getByText('Closed', { selector: 'dt' }).nextElementSibling).toHaveTextContent('5 Nov 2026, 16:00:00 UTC');
    expect(screen.getByText('Opened', { selector: 'dt' }).nextElementSibling).toHaveTextContent('5 Nov 2026, 09:30:00 UTC');
    // No transition leads out of it, so none is drawn, not even disabled.
    for (const name of ['Open market', 'Halt trading', 'Resume trading', 'Close the market']) {
      expect(screen.queryByRole('button', { name })).toBeNull();
    }
    expect(screen.getByText('Closing is final: this market cannot be opened, halted or resumed again.')).toBeInTheDocument();
    expect(screen.queryByText("Opening freezes the instruments and every account's endowment.")).toBeNull();
    expect(screen.getByRole('button', { name: 'What is closed?' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /halted/ })).toBeNull();

    // First on the page: that it ended, when, and what is still here.
    const ended = screen.getByRole('region', { name: 'This market has ended' });
    expect(ended).toHaveTextContent('It closed on 5 Nov 2026, 16:00:00 UTC.');
    // The runs and the tokens end with the close itself. The orders are cancelled after it, and the status does not
    // say how far that is: the page says what a close does, not that it is done.
    expect(ended).toHaveTextContent("Trading is over for good: every bot run ended, and every bot's token stopped working.");
    expect(ended).toHaveTextContent(
      'A close cancels every order that still rested and releases its hold, which on a full market takes up to about half a minute.',
    );
    expect(ended).not.toHaveTextContent('was cancelled');
    expect(ended).toHaveTextContent('Nothing was deleted.');
    const order = screen.getAllByRole('region').map((region) => region.getAttribute('aria-labelledby'));
    expect(order.indexOf('ended-title')).toBeLessThan(order.indexOf('runs-title'));
    expect(order.indexOf('runs-title')).toBeLessThan(order.indexOf('instruments-title'));

    // What it left is listed, read-only: a line in place of each form, and no form at all.
    await screen.findByRole('table', { name: 'Instruments' });
    expect(screen.queryByRole('button', { name: 'Edit NOVA' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete NOVA' })).toBeNull();
    expect(
      screen.getByText('The market has closed, so nothing can be added or changed. The instruments stay as they were.'),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "The market has closed, so no bot can be created. These bots trade no more: the close revoked every bot's token.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'The market has closed, so no trader can be added or funded. The accounts stay listed with what they started with.',
      ),
    ).toBeInTheDocument();
    expect(await screen.findByText('3 bots')).toBeInTheDocument();
    expect(
      await screen.findByText('The market has closed, so no run can start. Its runs stay listed, and can still be compared.'),
    ).toBeInTheDocument();
    // The one form left starts the next simulation, under the panel that says this one has ended.
    expect(screen.getAllByRole('form').map((form) => form.getAttribute('aria-label'))).toEqual(['Start a new simulation']);
    expect(order.indexOf('start-title')).toBe(order.indexOf('ended-title') + 1);
    for (const name of ['Add instrument', 'Create bots', 'Fund account', 'Add trader', 'Start run']) {
      expect(screen.queryByRole('button', { name })).toBeNull();
    }
    expect(screen.queryByText(/frozen once the market opens/)).toBeNull();
    // Reading is all the page does: no command was sent.
    expect(server.requests.some((request) => request.method !== 'GET')).toBe(false);
  });

  it('keeps how trading is doing on the page of a closed market, where a stopped exchange would show', async () => {
    const { server } = exchange('CLOSED');
    server.on('GET /api/v1/admin/health', {
      status: 200,
      json: {
        writer: { available: false, queueDepth: 0, queueCapacity: 256 },
        lastMinute: { committed: 0, failed: 0, withinBudget: 0, refused: 0 },
        outbox: { admitting: true, pendingEvents: 0, oldestPendingMillis: 0 },
      },
    });
    renderPage();

    const health = await screen.findByRole('region', { name: 'Health' });
    expect(await within(health).findByText('Trading stopped until Execution restarts')).toBeInTheDocument();
    // A reload, or another operator's page, knows nothing of the close's answer. A stopped exchange is what it can
    // see, and with it that the close may not have got to every order.
    expect(screen.getByRole('region', { name: 'This market has ended' })).toHaveTextContent(
      'The exchange has stopped taking commands: any order the close had not cancelled by then rests, with its hold, until the exchange is restarted.',
    );
  });

  it('says of the orders of a closed market only what is known of them', () => {
    const text = (close: Parameters<typeof restingOrdersText>[0]['close'], stillResting?: number | null, writerHalted = false) =>
      restingOrdersText({ close, stillResting, writerHalted });
    expect(text('done')).toBe('Every order that still rested was cancelled and its hold released.');
    expect(text('sending')).toMatch(/^The orders that still rested are being cancelled .* until the close answers, some still rest\.$/);
    expect(text('unresolved')).toBe('The close has not answered yet, so some orders may still rest with their holds.');
    // Left unfinished: said with the count whatever came after it under the same key, and whatever the writer says.
    for (const close of ['unresolved', 'sending'] as const) {
      expect(text(close, 4200, true)).toBe(
        'At most 4,200 orders still rest, with their holds: the close was left unfinished. The exchange has stopped, and cancels them when it is restarted.',
      );
    }
    // The exchange's count is an upper bound, and is never printed as how many rest.
    expect(text('unresolved', 1)).toBe(
      'At most 1 order still rests, with its hold: the close was left unfinished. The exchange has stopped, and cancels it when it is restarted.',
    );
    expect(text('unresolved', null)).toMatch(/^Some orders still rest, with their holds: /);
    expect(text('unresolved', 0)).toBe(
      'The close was left unfinished, though the exchange counted no order still resting when it stopped. It finishes the close when it is restarted.',
    );
    // Nothing known of the close: what a close does, and a stopped exchange if there is one. Never that it is done.
    for (const close of ['idle', 'refused'] as const) {
      expect(text(close)).toMatch(/^A close cancels every order that still rested and releases its hold, which/);
      expect(text(close, undefined, true)).toMatch(/The exchange has stopped taking commands: any order the close had not cancelled/);
      expect(text(close, undefined, true)).not.toMatch(/was cancelled|were cancelled/);
    }
    // Closed by the exchange for its set time: a close sent from here that never answered, or is still on its way,
    // says nothing of the orders, which the exchange's own close cancels.
    for (const close of ['unresolved', 'sending'] as const) {
      expect(restingOrdersText({ close, stillResting: undefined, writerHalted: false, closedOnSchedule: true })).toMatch(
        /^A close cancels every order that still rested and releases its hold, which/,
      );
    }
    expect(restingOrdersText({ close: 'unresolved', stillResting: 3, writerHalted: true, closedOnSchedule: true })).toMatch(
      /^At most 3 orders still rest/,
    );
  });

  it("reads the traders without an account again when the exchange names another simulation", async () => {
    const NEXT = '10000000-0000-0000-0000-000000000002';
    const { server, state } = exchange('SETUP');
    state.unfunded = ['dana'];
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);
    expect(await screen.findByRole('rowheader', { name: 'dana' })).toBeInTheDocument();
    const reads = () => server.requests.filter((request) => request.url.split('?')[0] === '/api/v1/admin/traders').length;
    const before = reads();

    // Still in setup, so the list stays on screen: only the simulation is another, and its logins' accounts with it.
    state.unfunded = ['erin'];
    state.market = { ...market('SETUP'), simulationId: NEXT };
    await client.invalidateQueries({ queryKey: ['market'] });

    // The list has no poll of its own: without this it would show the other simulation's until the page is left.
    expect(await screen.findByRole('rowheader', { name: 'erin' }, { timeout: 900 })).toBeInTheDocument();
    expect(screen.queryByRole('rowheader', { name: 'dana' })).toBeNull();
    expect(reads()).toBeGreaterThan(before);
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
    await screen.findByRole('table', { name: 'Instruments' });
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
    // The refusal was about the form as it was.
    expect(screen.queryByText('An instrument with symbol ORBT already exists')).toBeNull();
    fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));
    expect(await screen.findByText(/Added ORBT \(Orbit Labs\) at 50.00/)).toBeInTheDocument();
    expect(lastAdd()).toEqual({ symbol: 'ORBT', referencePrice: '50.00', displayName: 'Orbit Labs' });
    expect(within(form).getByLabelText('Name (optional)')).toHaveValue('');
    const keys = server.requests
      .filter((request) => request.url === '/api/v1/admin/instruments')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys[1]).not.toBe(keys[0]);
  });

  describe('an instrument just added', () => {
    const ORBT = { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000002', symbol: 'ORBT', referencePrice: '50.00' };
    const ORBT_ADDED = {
      instrumentId: ORBT.instrumentId,
      symbol: 'ORBT',
      displayName: null,
      referencePrice: '50.00',
      commandSequence: '9',
    };
    const listOf = (instruments: readonly (typeof NOVA)[]) => ({
      status: 200,
      json: { instruments, feed: { state: 'LIVE', lag: 0 } },
    });
    const pause = (ms: number) => act(() => new Promise((resolve) => setTimeout(resolve, ms)));
    const rowOf = (symbol: string) =>
      within(screen.getByRole('table', { name: 'Instruments' })).getByRole('rowheader', { name: symbol }).closest('tr');

    it('shows at once as waiting for market data, and the list is read quickly until it has it', async () => {
      const { server } = exchange('SETUP');
      // Market Data has it a little after the exchange answers: later than the read straight after the answer.
      let listedFrom: number | null = null;
      server.on('GET /api/v1/instruments', () =>
        listOf(listedFrom !== null && Date.now() >= listedFrom ? [NOVA, ORBT] : [NOVA]),
      );
      server.on('POST /api/v1/admin/instruments', () => {
        listedFrom = Date.now() + 600;
        return { status: 201, json: ORBT_ADDED };
      });
      renderPage();

      const form = await screen.findByRole('form', { name: 'Add an instrument' });
      await screen.findByRole('table', { name: 'Instruments' });
      fireEvent.click(within(form).getByRole('button', { name: 'ORBT 50.00' }));
      fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));

      expect(
        await screen.findByText('Added ORBT at 50.00. The list shows it as waiting until market data has it.'),
      ).toBeInTheDocument();
      // From the exchange's answer, and said to be that: no controls until market data lists it.
      const waiting = rowOf('ORBT');
      expect(waiting).toHaveTextContent('Added, waiting for market data');
      expect(waiting).toHaveTextContent('50.00');
      expect(within(waiting as HTMLElement).queryByRole('button')).toBeNull();
      // Nor is it offered again, which would only send it twice.
      expect(within(form).queryByRole('button', { name: 'ORBT 50.00' })).toBeNull();

      // Listed within a second or so, not five seconds after the read that missed it.
      expect(await screen.findByRole('button', { name: 'Edit ORBT' }, { timeout: 2_000 })).toBeInTheDocument();
      expect(screen.queryByText('Added, waiting for market data')).toBeNull();
      // Then back to the usual pace.
      const reads = () => server.requests.filter((request) => request.url === LIST).length;
      const listed = reads();
      await pause(1_200);
      expect(reads()).toBe(listed);
    });

    it('says so when market data takes longer than usual, and goes back to the usual pace', async () => {
      const { server } = exchange('SETUP');
      const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      // Answered nearly twenty seconds ago, and still not listed.
      client.setQueryData(
        ['instruments-added'],
        [{ ...ORBT_ADDED, scope: SIMULATION, answeredAt: Date.now() - WAITING_FAST_FOR_MS + 300 }],
      );
      renderWith(client);

      expect(await screen.findByText('Added, waiting for market data')).toBeInTheDocument();
      expect(
        await screen.findByText('Added; market data is taking longer than usual to list it', undefined, { timeout: 1_500 }),
      ).toBeInTheDocument();
      // At most one more quick read, the one already timed when the time ran out; then the usual pace.
      await pause(500);
      const reads = () => server.requests.filter((request) => request.url === LIST).length;
      const slow = reads();
      await pause(1_200);
      expect(reads()).toBe(slow);
    });

    it('is still shown as waiting on coming back to the page', async () => {
      const { server } = exchange('SETUP');
      server.on('POST /api/v1/admin/instruments', { status: 201, json: ORBT_ADDED });
      const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      const first = renderWith(client);
      const form = await screen.findByRole('form', { name: 'Add an instrument' });
      fireEvent.click(within(form).getByRole('button', { name: 'ORBT 50.00' }));
      fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));
      await screen.findByText('Added, waiting for market data');

      // The operator looks at another page for a while.
      first.unmount();
      await pause(20);
      renderWith(client);
      expect(await screen.findByText('Added, waiting for market data')).toBeInTheDocument();
      expect(rowOf('ORBT')).toHaveTextContent('50.00');
    });

    it('is checked against before sending, as listed symbols are, and nothing is sent for either', async () => {
      const { server } = exchange('SETUP');
      let listed = [NOVA];
      server.on('GET /api/v1/instruments', () => listOf(listed));
      server.on('POST /api/v1/admin/instruments', { status: 201, json: ORBT_ADDED });
      const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      renderWith(client);
      const form = await screen.findByRole('form', { name: 'Add an instrument' });
      await screen.findByRole('table', { name: 'Instruments' });
      const symbol = within(form).getByLabelText('Symbol');
      const add = () => fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));
      const posts = () => server.requests.filter((request) => request.method === 'POST').length;

      fireEvent.change(symbol, { target: { value: ' nova ' } });
      fireEvent.change(within(form).getByLabelText('Reference price'), { target: { value: '1.00' } });
      add();
      const said = (await screen.findByText(/NOVA is already in this market/)).closest('[role="alert"]');
      expect(said).toHaveTextContent('NOVA is already in this market, and a symbol is listed only once. Nothing was sent.');
      expect(symbol).toHaveAttribute('aria-invalid', 'true');
      expect(symbol).toHaveAccessibleDescription(/NOVA is already in this market/);
      expect(posts()).toBe(0);
      // Changing the form puts it away.
      fireEvent.change(symbol, { target: { value: 'NOV' } });
      expect(screen.queryByText(/already in this market/)).toBeNull();
      expect(symbol).not.toHaveAttribute('aria-invalid');

      fireEvent.click(within(form).getByRole('button', { name: 'ORBT 50.00' }));
      add();
      await screen.findByText('Added, waiting for market data');
      fireEvent.change(symbol, { target: { value: 'ORBT' } });
      fireEvent.change(within(form).getByLabelText('Reference price'), { target: { value: '50.00' } });
      add();
      expect(await screen.findByText(/ORBT was just added, and is waiting for market data to list it\. Nothing was sent\./)).toBeInTheDocument();
      expect(posts()).toBe(1);
      // Once listed, it is said as listed.
      listed = [NOVA, ORBT];
      await act(() => client.invalidateQueries({ queryKey: ['instruments'] }));
      expect(await screen.findByText(/ORBT is already in this market/)).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
      expect(screen.queryByText(/already in this market/)).toBeNull();
      expect(symbol).toHaveFocus();
      expect(posts()).toBe(1);
    });

    it('lets a refused add go when the form changes, a chip is picked, it is dismissed, or the list shows the symbol', async () => {
      const { server } = exchange('SETUP');
      let listed = [NOVA];
      server.on('GET /api/v1/instruments', () => listOf(listed));
      // Another operator added each of these a moment ago, and market data does not list them yet.
      server.on('POST /api/v1/admin/instruments', (request) => ({
        status: 409,
        problem: {
          status: 409,
          code: 'SYMBOL_TAKEN',
          detail: `An instrument with symbol ${(request.body as { symbol: string }).symbol} already exists`,
        },
      }));
      const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      renderWith(client);
      const form = await screen.findByRole('form', { name: 'Add an instrument' });
      await screen.findByRole('table', { name: 'Instruments' });
      const add = () => fireEvent.click(within(form).getByRole('button', { name: 'Add instrument' }));

      fireEvent.click(within(form).getByRole('button', { name: 'HELI 200.00' }));
      add();
      expect(await screen.findByText('An instrument with symbol HELI already exists')).toBeInTheDocument();
      fireEvent.change(within(form).getByLabelText('Reference price'), { target: { value: '201.00' } });
      expect(screen.queryByText('An instrument with symbol HELI already exists')).toBeNull();

      add();
      expect(await screen.findByText('An instrument with symbol HELI already exists')).toBeInTheDocument();
      fireEvent.click(within(form).getByRole('button', { name: 'QUAD 25.00' }));
      expect(screen.queryByText('An instrument with symbol HELI already exists')).toBeNull();

      add();
      expect(await screen.findByText('An instrument with symbol QUAD already exists')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
      expect(screen.queryByText('An instrument with symbol QUAD already exists')).toBeNull();
      expect(within(form).getByLabelText('Symbol')).toHaveFocus();

      fireEvent.click(within(form).getByRole('button', { name: 'VELO 80.00' }));
      add();
      expect(await screen.findByText('An instrument with symbol VELO already exists')).toBeInTheDocument();
      listed = [NOVA, { ...NOVA, instrumentId: '00000000-0000-0000-0000-000000000005', symbol: 'VELO' }];
      await act(() => client.invalidateQueries({ queryKey: ['instruments'] }));
      expect(await screen.findByRole('button', { name: 'Edit VELO' })).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByText('An instrument with symbol VELO already exists')).toBeNull());
    });
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
    const listReads = () => server.requests.filter((request) => request.url === LIST).length;
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

    const listReads = server.requests.filter((request) => request.url === LIST).length;
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
      expect(server.requests.filter((request) => request.url === LIST).length).toBeGreaterThan(listReads),
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

  it('creates any number of bots with the usual split preselected, and sends the exact counts it shows', async () => {
    const { server, state } = exchange('SETUP');
    server.on('POST /api/v1/admin/bot-jobs', () => {
      state.job = botJob({
        total: 20_000,
        strategies: {
          marketMakers: { requested: 5_000, created: 0 },
          noise: { requested: 11_000, created: 0 },
          momentum: { requested: 4_000, created: 0 },
        },
      });
      return { status: 202, json: state.job };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    expect(await screen.findByText('No bots yet')).toBeInTheDocument();
    // The count and the endowment start empty, with examples; the mix starts at the usual split.
    const count = within(form).getByLabelText('Number of bots');
    const cash = within(form).getByLabelText('Cash each');
    const nova = await within(form).findByLabelText('NOVA shares');
    expect([count, cash, nova].map((field) => (field as HTMLInputElement).value)).toEqual(['', '', '']);
    expect([count, cash, nova].map((field) => field.getAttribute('placeholder'))).toEqual([
      'e.g. 20,000',
      'e.g. 100000.00',
      'e.g. 1000',
    ]);
    const mix = within(form).getByRole('group', { name: 'Strategy mix' });
    expect(within(mix).getByLabelText('Market makers, %')).toHaveValue('20');
    expect(within(mix).getByLabelText('Momentum, %')).toHaveValue('20');
    expect(within(mix).getByText(/Noise: the rest,/)).toHaveTextContent('Noise: the rest, 60%');
    const create = within(form).getByRole('button', { name: 'Create bots' });
    expect(create).toBeDisabled();
    expect(create).toHaveAccessibleDescription('Type how many bots to create.');

    fireEvent.change(count, { target: { value: '20,000' } });
    expect(within(mix).getByLabelText('Market makers, %')).toHaveAccessibleDescription(
      'That is 4,000 market makers, 12,000 noise and 4,000 momentum bots: 20,000 in all.',
    );
    fireEvent.change(within(mix).getByLabelText('Market makers, %'), { target: { value: '25' } });
    expect(count).toHaveAccessibleDescription(
      'That is 5,000 market makers, 11,000 noise and 4,000 momentum bots: 20,000 in all.',
    );
    expect(within(mix).getByText(/Noise: the rest,/)).toHaveTextContent('Noise: the rest, 55%');
    fireEvent.change(cash, { target: { value: '100000.00' } });
    fireEvent.change(nova, { target: { value: '1000' } });
    expect(create).toBeEnabled();
    fireEvent.click(create);

    // The job answers at once; its progress takes the form's place, focus goes to it from the button it replaced,
    // and a screen reader hears that it has started, though the panel had no job when the page opened.
    const heading = await screen.findByRole('heading', { name: 'Creating bots' });
    await waitFor(() => expect(heading).toHaveFocus());
    const panel = screen.getByRole('region', { name: 'Bots' });
    const announcements = within(panel).getAllByRole('status').find((node) => node.className === 'visually-hidden')!;
    expect(announcements).toHaveTextContent('Creating 20,000 bots.');
    const bar = screen.getByRole('progressbar', { name: 'Creating bots' });
    expect(bar).toHaveAttribute('aria-valuenow', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '20000');
    expect(bar).toHaveAttribute('aria-valuetext', '0 of 20,000 bots made (0%)');
    expect(screen.getByText('Working out the time left…', { exact: false })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Create bots' })).toBeNull();
    const sent = server.requests.filter((request) => request.url === '/api/v1/admin/bot-jobs');
    expect(sent).toHaveLength(1);
    expect(sent[0]?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent[0]?.body).toEqual({
      count: 20_000,
      strategies: { marketMakers: 5_000, noise: 11_000, momentum: 4_000 },
      cash: '100000.00',
      holdings: [{ instrumentId: NOVA.instrumentId, quantity: 1000 }],
    });
    // The market waits for the job.
    expect(await button('Open market')).toBeDisabled();
    expect(await button('Open market')).toHaveAccessibleDescription(
      'Bots are still being created: 0 of 20,000 bots made (0%). The market can open once the job is done or stopped.',
    );
  });

  it('warns plainly when the bots would get no shares, as none of them could ever sell', async () => {
    exchange('SETUP');
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    const nova = await within(form).findByLabelText('NOVA shares');
    const warning =
      'No shares given: these bots would own none, and a bot with no shares can never sell, so only bids would reach the book. Give each bot shares of at least one instrument.';
    expect(within(form).getByText(warning)).toBeInTheDocument();
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '20' } });
    fireEvent.change(within(form).getByLabelText('Cash each'), { target: { value: '100000.00' } });
    // A warning, not a refusal: the exchange takes such bots, so Create stays offered, and says why it warns.
    const create = within(form).getByRole('button', { name: 'Create bots' });
    expect(create).toBeEnabled();
    expect(create).toHaveAccessibleDescription(warning);
    // A 0 gives none either.
    fireEvent.change(nova, { target: { value: '0' } });
    expect(within(form).getByText(warning)).toBeInTheDocument();
    fireEvent.change(nova, { target: { value: '1000' } });
    expect(within(form).queryByText(warning)).toBeNull();
    expect(create).not.toHaveAccessibleDescription();
  });

  it('warns that bots made before any instrument is listed own no shares', async () => {
    exchange('SETUP', []);
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    expect(
      within(form).getByText(
        'No instrument is listed yet, so these bots would own no shares, and a bot with no shares can never sell.',
      ),
    ).toBeInTheDocument();
  });

  it('blocks Create while the mix cannot be worked out, saying why', async () => {
    exchange('SETUP');
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    const create = within(form).getByRole('button', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: 'many' } });
    expect(create).toBeDisabled();
    expect(create).toHaveAccessibleDescription('The number of bots is a whole number from 1 to 2,147,483,647.');
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '2147483647' } });
    expect(create).toBeEnabled();
    fireEvent.change(within(form).getByLabelText('Market makers, %'), { target: { value: '70' } });
    fireEvent.change(within(form).getByLabelText('Momentum, %'), { target: { value: '40' } });
    expect(create).toBeDisabled();
    expect(create).toHaveAccessibleDescription(
      'Market makers and momentum together can be at most 100%; noise is the rest.',
    );
    expect(within(form).getByText(/Noise: the rest,/)).toHaveTextContent('Noise: the rest, —');
  });

  it('shows the progress as it goes, per strategy with the time left, and announces it only at quarters', async () => {
    const { state } = exchange('SETUP');
    const going = (created: number, secondsLeft: number | null) =>
      botJob({
        created,
        secondsLeft,
        partsDone: created / 1_000,
        strategies: {
          marketMakers: { requested: 4_000, created: created / 5 },
          noise: { requested: 12_000, created: (created * 3) / 5 },
          momentum: { requested: 4_000, created: created / 5 },
        },
      });
    state.job = going(5_000, 30);
    state.tagged = { marketMakers: 1_000, noise: 3_000, momentum: 1_000 };
    renderPage();

    const bar = await screen.findByRole('progressbar', { name: 'Creating bots' });
    expect(bar).toHaveAttribute('aria-valuenow', '5000');
    expect(bar).toHaveAttribute('aria-valuetext', '5,000 of 20,000 bots made (25%)');
    expect(screen.getByText('About 30 s left', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('Market makers 1,000 of 4,000 · Noise 3,000 of 12,000 · Momentum 1,000 of 4,000'))
      .toBeInTheDocument();
    expect(await screen.findByText('5,000 bots')).toBeInTheDocument();
    const panel = screen.getByRole('region', { name: 'Bots' });
    const announcements = within(panel).getAllByRole('status').find((node) => node.className === 'visually-hidden')!;
    // A job found running on opening the page is shown, not announced.
    expect(announcements).toHaveTextContent('');

    state.job = going(6_000, 28);
    await waitFor(() => expect(bar).toHaveAttribute('aria-valuenow', '6000'), { timeout: 3_000 });
    expect(announcements).toHaveTextContent('');

    state.job = going(10_000, 20);
    state.tagged = { marketMakers: 2_000, noise: 6_000, momentum: 2_000 };
    await waitFor(() => expect(announcements).toHaveTextContent('50% of the 20,000 bots are made.'), { timeout: 3_000 });
    // The bar has the count while the job runs; the population is read again once it ends, not with every part.
    expect(screen.getByText('5,000 bots')).toBeInTheDocument();

    state.job = { ...going(20_000, null), state: 'DONE', finishedAt: '2026-11-24T10:00:24Z' };
    state.tagged = { marketMakers: 4_000, noise: 12_000, momentum: 4_000 };
    const done = 'Created 20,000 bots: 4,000 market makers, 12,000 noise and 4,000 momentum bots.';
    await waitFor(() => expect(announcements).toHaveTextContent(done), { timeout: 3_000 });
    expect(within(panel).getByText(done, { selector: 'p.outcome' })).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).toBeNull();
    expect(await screen.findByRole('form', { name: 'Create bots' })).toBeInTheDocument();
    expect(await button('Open market')).toBeEnabled();
    expect(await screen.findByText('20,000 bots')).toBeInTheDocument();
    // Four reads a second apart, each waited for: more than the default five seconds on a busy machine.
  }, 15_000);

  it('reads the bots and accounts once more when a job ends, not with each part it makes', async () => {
    const { server, state } = exchange('SETUP');
    state.job = botJob({ total: 3_000, created: 1_000, partsDone: 1 });
    renderPage();
    const reads = (url: string) => server.requests.filter((request) => request.url.split('?')[0] === url).length;
    const bar = await screen.findByRole('progressbar', { name: 'Creating bots' });
    await waitFor(() => expect(reads('/api/v1/admin/accounts')).toBe(1));
    const bots = reads('/api/v1/admin/bots');

    state.job = botJob({ total: 3_000, created: 2_000, partsDone: 2 });
    await waitFor(() => expect(bar).toHaveAttribute('aria-valuenow', '2000'), { timeout: 3_000 });
    state.job = { ...botJob({ total: 3_000, created: 3_000, partsDone: 3 }), state: 'DONE', finishedAt: '2026-11-24T10:00:03Z' };
    expect(await screen.findByRole('form', { name: 'Create bots' }, { timeout: 3_000 })).toBeInTheDocument();

    await waitFor(() => expect(reads('/api/v1/admin/accounts')).toBe(2));
    expect(reads('/api/v1/admin/bots')).toBe(bots + 1);
  }, 10_000);

  it('stops a running job, and says that what was made stays', async () => {
    const { server, state } = exchange('SETUP');
    state.job = botJob({ created: 3_000, partsDone: 3, secondsLeft: 40 });
    server.on(`POST /api/v1/admin/bot-jobs/${state.job.jobId}/stop`, () => {
      state.job = { ...state.job!, state: 'STOPPED', finishedAt: '2026-11-24T10:00:10Z', secondsLeft: null };
      return { status: 200, json: state.job };
    });
    renderPage();

    const stop = await button('Stop creating bots');
    expect(stop).toHaveAccessibleDescription('The bots made so far stay.');
    stop.focus();
    fireEvent.click(stop);

    const stopped = 'Stopped after 3,000 of 20,000 bots. The 3,000 made stay. The rest were not made.';
    const outcome = await screen.findByText(stopped, { selector: 'p.outcome' });
    // Stop is gone with the bar, so focus goes to what the job came to, not back to the top of the page.
    await waitFor(() => expect(outcome).toHaveFocus());
    expect(screen.queryByRole('progressbar')).toBeNull();
    expect(screen.getByRole('form', { name: 'Create bots' })).toBeInTheDocument();
    const sent = server.requests.filter((request) => request.url.endsWith('/stop'));
    expect(sent).toHaveLength(1);
    expect(sent[0]?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('shows the same progress after a reload, and on another screen, from the exchange', async () => {
    const { server, state } = exchange('SETUP');
    state.job = botJob({ created: 7_000, partsDone: 7, secondsLeft: 3_900, resumed: 1 });
    const first = renderPage();
    expect(await screen.findByRole('progressbar', { name: 'Creating bots' })).toHaveAttribute('aria-valuenow', '7000');
    first.unmount();

    renderPage();
    const bar = await screen.findByRole('progressbar', { name: 'Creating bots' });
    expect(bar).toHaveAttribute('aria-valuetext', '7,000 of 20,000 bots made (35%)');
    expect(screen.getByText('About 1 h 5 min left', { exact: false })).toBeInTheDocument();
    expect(
      screen.getByText('The exchange restarted during this job and took it up again where it was; no bot is made twice.'),
    ).toBeInTheDocument();
    expect(server.requests.filter((request) => request.method === 'POST')).toHaveLength(0);
    // A job found running is shown where it is: focus stays with the page.
    expect(document.body).toHaveFocus();
  });

  it('says when a job failed, with its reason, and when the exchange stops answering in the middle of one', async () => {
    const { server, state } = exchange('SETUP');
    state.job = botJob({
      state: 'FAILED',
      created: 2_000,
      finishedAt: '2026-11-24T10:00:05Z',
      failure: 'Bots could not be made: No space left on device',
    });
    const first = renderPage();
    expect(
      await screen.findByText(
        'Creating bots failed after 2,000 of 20,000: Bots could not be made: No space left on device. The 2,000 made stay. Create the rest once the cause is fixed.',
      ),
    ).toBeInTheDocument();
    first.unmount();

    state.job = botJob({ created: 4_000, partsDone: 4, secondsLeft: 16 });
    renderPage();
    expect(await screen.findByRole('progressbar', { name: 'Creating bots' })).toBeInTheDocument();
    server.on('GET /api/v1/admin/bot-jobs/current', {
      status: 503,
      problem: { status: 503, code: 'TRADING_UNAVAILABLE', detail: 'Trading is unavailable' },
    });
    expect(
      await screen.findByText(/^Interrupted: the exchange is not answering/, {}, { timeout: 3_000 }),
    ).toBeInTheDocument();
    // The last progress stays on screen, not a form that would start a second job.
    expect(screen.getByRole('progressbar', { name: 'Creating bots' })).toHaveAttribute('aria-valuenow', '4000');
    expect(screen.queryByRole('form', { name: 'Create bots' })).toBeNull();
  }, 15_000);

  it('says in plain words when a running job has stopped moving, and gives no time left for it', async () => {
    const { state } = exchange('SETUP');
    state.job = botJob({
      created: 12_000,
      partsDone: 12,
      secondsLeft: null,
      lastProgressAt: '2026-11-24T10:00:12Z',
      stalled: true,
    });
    renderPage();

    const bar = await screen.findByRole('progressbar', { name: 'Creating bots' });
    expect(bar).toHaveAttribute('aria-valuetext', '12,000 of 20,000 bots made (60%)');
    expect(screen.getByText('No progress since 10:00:12 UTC', { exact: false })).toBeInTheDocument();
    expect(screen.queryByText(/About .* left/)).toBeNull();
    expect(screen.queryByText(/Working out the time left/)).toBeNull();
    expect(
      screen.getByText(
        'Interrupted: no bots have been made since 10:00:12 UTC, so the time left is unknown. The exchange may have stopped taking commands. Restarting the exchange takes the job up again where it was, and no bot is made twice; Stop keeps the bots made so far.',
      ),
    ).toHaveAttribute('role', 'alert');
    expect(await button('Stop creating bots')).toBeEnabled();
    expect(await button('Open market')).toHaveAccessibleDescription(
      'Bots are still being created, but nothing has moved since 10:00:12 UTC: 12,000 of 20,000 bots made (60%). The market can open once the job is done or stopped.',
    );
  });

  it("shows a job refused for another operator's beside that job, then lets it go with that job, keeping the figures", async () => {
    const { server, state } = exchange('SETUP');
    server.on('POST /api/v1/admin/bot-jobs', () => {
      // Another operator started one meanwhile: the refusal names it, and its progress shows here.
      state.job = botJob({ created: 1_000, partsDone: 1 });
      return {
        status: 409,
        problem: {
          status: 409,
          code: 'BOT_JOB_RUNNING',
          detail: 'Bots are already being created: 1,000 of 20,000 bots are made. Wait for that job to finish, or stop it',
        },
      };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '50' } });
    fireEvent.change(within(form).getByLabelText('Market makers, %'), { target: { value: '10' } });
    fireEvent.change(within(form).getByLabelText('Cash each'), { target: { value: '250.00' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    // In the page's words: the exchange's quote that job's progress as it was, and the bar has it as it is.
    const refusal = await screen.findByText(
      'Not started: a job is already creating bots, shown above. Wait for it to finish, or stop it; what you typed is kept for then.',
    );
    expect(refusal).toHaveAttribute('role', 'alert');
    expect(screen.queryByText(/1,000 of 20,000 bots are made/)).toBeNull();
    const bar = await screen.findByRole('progressbar', { name: 'Creating bots' });
    expect(bar).toHaveAttribute('aria-valuenow', '1000');
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Creating bots' })).toHaveFocus());

    // That job ends: the refusal goes with it, and the form comes back as it was typed, ready to send.
    state.job = { ...botJob({ created: 20_000, partsDone: 20 }), state: 'DONE', finishedAt: '2026-11-24T10:00:24Z' };
    const again = await screen.findByRole('form', { name: 'Create bots' }, { timeout: 3_000 });
    expect(screen.queryByText(/^Not started/)).toBeNull();
    expect(within(again).getByLabelText('Number of bots')).toHaveValue('50');
    expect(within(again).getByLabelText('Market makers, %')).toHaveValue('10');
    expect(within(again).getByLabelText('Cash each')).toHaveValue('250.00');
    expect(within(again).getByRole('button', { name: 'Create bots' })).toBeEnabled();
  }, 15_000);

  it('marks the fields a refused job names, describes them by the refusal and focuses the first', async () => {
    const { server } = exchange('SETUP');
    server.on('POST /api/v1/admin/bot-jobs', {
      status: 400,
      problem: {
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'The bot job request is invalid',
        errors: [{ field: 'cash', code: 'INVALID_AMOUNT', message: 'Amount must be a fixed-scale decimal such as 100.25' }],
      },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '20' } });
    fireEvent.change(within(form).getByLabelText('Cash each'), { target: { value: '1.005' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    const message = 'Amount must be a fixed-scale decimal such as 100.25';
    expect(await screen.findByText(message)).toBeInTheDocument();
    const cash = within(form).getByLabelText('Cash each');
    expect(cash).toHaveAttribute('aria-invalid', 'true');
    expect(cash).toHaveAccessibleDescription(message);
    expect(within(form).getByLabelText('Number of bots')).not.toHaveAttribute('aria-invalid');
    expect(within(form).getByLabelText('Number of bots')).toHaveValue('20');
    await waitFor(() => expect(cash).toHaveFocus());
  });

  it('sends a job whose answer did not come again under the same key, never as a second job', async () => {
    const { server, state } = exchange('SETUP');
    let asked = 0;
    server.on('POST /api/v1/admin/bot-jobs', () => {
      asked++;
      if (asked === 1) {
        return { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'The command may still complete' } };
      }
      state.job = botJob();
      return { status: 202, json: state.job };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '20000' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));
    fireEvent.click(await button('Retry'));

    expect(await screen.findByRole('progressbar', { name: 'Creating bots' })).toBeInTheDocument();
    const keys = server.requests
      .filter((request) => request.url === '/api/v1/admin/bot-jobs')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('settles a start whose answer was lost from the job it made, under the same key, without a Retry', async () => {
    const { server, state } = exchange('SETUP');
    let asked = 0;
    server.on('POST /api/v1/admin/bot-jobs', () => {
      asked++;
      // The first did start the job; only its answer was lost. Sent again, it is answered from its stored answer.
      state.job = botJob({ created: asked === 1 ? 0 : 2_000, partsDone: asked === 1 ? 0 : 2 });
      return asked === 1
        ? { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'The command may still complete' } }
        : { status: 202, json: state.job };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '20000' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(await screen.findByRole('progressbar', { name: 'Creating bots' })).toBeInTheDocument();
    await waitFor(() => expect(asked).toBe(2));
    await waitFor(() => expect(screen.queryByText(/^Not confirmed/)).toBeNull());
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    const keys = server.requests
      .filter((request) => request.url === '/api/v1/admin/bot-jobs')
      .map((request) => request.headers['Idempotency-Key']);
    expect(keys).toEqual([keys[0], keys[0]]);
  });

  it('settles a lost start whose job has already ended, so the form is not left waiting for a Retry', async () => {
    const { server, state } = exchange('SETUP');
    const ended = { ...botJob({ total: 30, created: 30, partsDone: 1 }), state: 'DONE' as const };
    let asked = 0;
    server.on('POST /api/v1/admin/bot-jobs', () => {
      asked++;
      state.job = { ...ended, finishedAt: '2026-11-24T10:00:01Z' };
      return asked === 1
        ? { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'The command may still complete' } }
        : { status: 202, json: state.job };
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '30' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(await screen.findByText(/^Created 30 bots/, { selector: 'p.outcome' })).toBeInTheDocument();
    await waitFor(() => expect(asked).toBe(2));
    await waitFor(() => expect(within(form).getByLabelText('Number of bots')).toBeEnabled());
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('leaves a lost start to Retry while the panel shows only the job it showed when it was sent', async () => {
    const { server, state } = exchange('SETUP');
    // An earlier job, done before this start: it says nothing about whether this one was made.
    state.job = { ...botJob({ total: 10, created: 10, partsDone: 1 }), state: 'DONE', finishedAt: '2026-11-24T09:00:00Z' };
    let asked = 0;
    server.on('POST /api/v1/admin/bot-jobs', () => {
      asked++;
      return { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'The command may still complete' } };
    });
    renderPage();

    expect(await screen.findByText(/^Created 10 bots/, { selector: 'p.outcome' })).toBeInTheDocument();
    const form = screen.getByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '500' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Create bots' }));

    expect(await button('Retry')).toBeInTheDocument();
    // The job is read again after the answer; the same job shown is no reason to send the start again.
    await waitFor(() =>
      expect(server.requests.filter((request) => request.url === '/api/v1/admin/bot-jobs/current').length).toBeGreaterThan(1),
    );
    expect(asked).toBe(1);
    expect(within(form).getByLabelText('Number of bots')).toBeDisabled();
  });

  it('keeps creating bots however many there are: no limit, no meter', async () => {
    const { state } = exchange('SETUP');
    state.bots = 10_000;
    const { container } = renderPage();

    expect(await screen.findByText('10,000 bots')).toBeInTheDocument();
    expect(screen.getByText('10,000 untagged')).toBeInTheDocument();
    const form = await screen.findByRole('form', { name: 'Create bots' });
    fireEvent.change(within(form).getByLabelText('Number of bots'), { target: { value: '1000000' } });
    fireEvent.change(await within(form).findByLabelText('NOVA shares'), { target: { value: '1000' } });
    const create = await button('Create bots');
    expect(create).toBeEnabled();
    expect(create).not.toHaveAttribute('aria-describedby');
    expect(container.querySelector('meter')).toBeNull();
    const panel = screen.getByRole('region', { name: 'Bots' });
    expect(within(panel).queryByText(/ of [\d,]+ bots|at its limit|more in all/)).toBeNull();
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
    // A tenth of the bots asked for, at least one a second.
    expect(within(form).getByLabelText('Commands per second')).toHaveValue('1.00');
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
      commandsPerSecond: '1.00',
      maxOrderQuantity: 10,
      maxPosition: 100,
      durationMinutes: 60,
    });
  });

  it('starts from a rate that follows the bots asked for until one is typed, and says how the run will pace itself', async () => {
    const { state } = exchange('OPEN');
    state.tagged = { marketMakers: 200, noise: 600, momentum: 200 };
    renderPage();

    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    const rate = within(form).getByLabelText('Commands per second');
    await waitFor(() => expect(rate).toHaveValue('100.00'));
    expect(rate).toHaveAccessibleDescription(
      'Each bot acts about every 10 s; first trades in about 2 s, once every market maker has had its first turn.',
    );
    fireEvent.change(within(form).getByLabelText('Noise'), { target: { value: '0' } });
    expect(rate).toHaveValue('40.00');
    expect(rate).toHaveAccessibleDescription(
      'Each bot acts about every 10 s; first trades in about 5 s, once every market maker has had its first turn.',
    );

    // A rate typed is the operator's: it stays as the counts change, and the words follow it.
    fireEvent.change(rate, { target: { value: '4.00' } });
    fireEvent.change(within(form).getByLabelText('Noise'), { target: { value: '600' } });
    expect(rate).toHaveValue('4.00');
    expect(rate).toHaveAccessibleDescription(
      'Each bot acts about every 4 min 10 s; first trades in about 50 s, once every market maker has had its first turn.',
    );
    fireEvent.change(rate, { target: { value: '' } });
    expect(rate).not.toHaveAccessibleDescription();
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
      commandsPerSecond: '1.00',
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
    const timed = within(form).getByRole('radio', { name: 'For a set time' });
    const minutes = within(form).getByLabelText('Duration in minutes');
    // One group of two, so the arrow keys move between them.
    expect(untilStopped).toHaveAttribute('name', 'run-length');
    expect(timed).toHaveAttribute('name', 'run-length');
    fireEvent.click(untilStopped);
    expect(untilStopped).toBeChecked();
    expect(timed).not.toBeChecked();
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
      commandsPerSecond: '1.00',
      maxOrderQuantity: 10,
      maxPosition: 100,
    });
    expect(await within(form).findByText('Started a run of 2 bots.')).toBeInTheDocument();

    fireEvent.click(timed);
    expect(minutes).toBeEnabled();
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    await waitFor(() => expect(posted()).toHaveLength(2));
    expect(posted()[1]?.body).toMatchObject({ durationMinutes: 60 });
  });

  it('keeps the minutes typed through a change of mind about the run length', async () => {
    const { server, state } = exchange('OPEN');
    state.tagged = { marketMakers: 1, noise: 1, momentum: 0 };
    server.on('POST /api/v1/admin/bot-runs', {
      status: 201,
      json: { runId: botRun().runId, bots: 2, commandSequence: '9' },
    });
    renderPage();

    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    await waitFor(() => expect(within(form).getByLabelText('Noise')).toHaveValue('1'));
    const minutes = within(form).getByLabelText('Duration in minutes');
    fireEvent.change(minutes, { target: { value: '15' } });
    fireEvent.click(within(form).getByRole('radio', { name: 'Until I stop it' }));
    expect(minutes).toBeDisabled();
    expect(minutes).toHaveValue('15');
    fireEvent.click(within(form).getByRole('radio', { name: 'For a set time' }));
    expect(minutes).toBeEnabled();
    expect(minutes).toHaveValue('15');

    const posted = () => server.requests.filter((request) => request.method === 'POST' && request.url.endsWith('/bot-runs'));
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    await waitFor(() => expect(posted()).toHaveLength(1));
    expect(posted()[0]?.body).toEqual({
      marketMakers: 1,
      noise: 1,
      momentum: 0,
      commandsPerSecond: '1.00',
      maxOrderQuantity: 10,
      maxPosition: 100,
      durationMinutes: 15,
    });
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

  it('offers no run to start once the market has closed, and keeps its runs and their comparison', async () => {
    const { state } = exchange('CLOSED');
    state.tagged = { marketMakers: 1, noise: 2, momentum: 0 };
    state.runs = [
      finishedRun({ runId: '20000000-0000-0000-0000-000000000002', startedAt: '2026-11-05T11:00:00Z' }),
      finishedRun({ runId: '20000000-0000-0000-0000-000000000001', startedAt: '2026-11-05T10:00:00Z' }),
    ];
    renderPage();

    const panel = await runs();
    expect(
      await within(panel).findByText('The market has closed, so no run can start. Its runs stay listed, and can still be compared.'),
    ).toBeInTheDocument();
    expect(within(panel).queryByRole('form', { name: 'Start a bot run' })).toBeNull();
    expect(within(panel).queryByRole('button', { name: 'Start run' })).toBeNull();

    // The history and its comparison are what a closed market is kept for.
    const recent = await within(panel).findByRole('table', { name: 'Recent runs' });
    expect(within(recent).getAllByRole('row')).toHaveLength(3);
    for (const box of within(recent).getAllByRole('checkbox')) {
      expect(box).toBeEnabled();
      fireEvent.click(box);
    }
    expect(within(panel).getByRole('link', { name: 'Compare 2 runs' })).toHaveAttribute(
      'href',
      '/operator/runs/compare?runs=20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000001',
    );
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
    // Past its end the exchange shows the run as stopping, and as still wanting to run until its worker confirms.
    // Nothing is left to count, and it can still be stopped by hand should the worker hang.
    clockAt('2026-11-06T11:00:04Z');
    state.runs = [{ ...timed, status: 'STOPPING' }];
    await waitFor(() => expect(current).toHaveTextContent('Elapsed1 h 0 minEnds11:00:00 UTCTime leftEnding now'), {
      timeout: 10_000,
    });
    expect(within(current).getByRole('status', { name: 'Run status: Stopping' })).toBeInTheDocument();
    expect(within(current).getByRole('button', { name: 'Stop run' })).toBeEnabled();
    expect(within(current).getByRole('button', { name: 'Stop run' })).not.toHaveAccessibleDescription();
    // Once the operator asks for the stop, the end time decides nothing and is no longer counted down to.
    state.runs = [{ ...timed, status: 'STOPPING', desiredState: 'STOPPED', stopRequestedAt: '2026-11-06T11:00:06Z' }];
    await waitFor(() => expect(current).toHaveTextContent('Elapsed1 h 0 minEnds11:00:00 UTCLast heartbeat'), {
      timeout: 10_000,
    });
    expect(within(current).getByRole('button', { name: 'Stop run' })).toBeDisabled();
    expect(within(current).getByRole('button', { name: 'Stop run' })).toHaveAccessibleDescription(
      'A stop was asked for at 11:00:06 UTC; the run shows Stopped once the worker confirms.',
    );
  }, 30_000);

  it("counts on the exchange's clock, whichever way the browser's is off", async () => {
    const { state } = exchange('OPEN');
    // A two-minute run two seconds old, on a browser six minutes ahead of the exchange.
    state.runs = [botRun({ endsAt: '2026-11-06T10:02:00Z' })];
    state.clock = '2026-11-06T10:00:02Z';
    clockAt('2026-11-06T10:06:02Z');
    const view = renderPage();

    const current = await within(await runs()).findByRole('group', { name: 'Current run' });
    expect(current).toHaveTextContent('Elapsed2 sEnds10:02:00 UTCTime left1 min 58 sLast');
    // The exchange's answers move the readout; the browser's clock, moved alone, does not.
    clockAt('2026-11-06T10:30:00Z');
    state.clock = '2026-11-06T10:00:40Z';
    await waitFor(() => expect(current).toHaveTextContent('Elapsed40 sEnds10:02:00 UTCTime left1 min 20 sLast'), {
      timeout: 10_000,
    });
    view.unmount();

    // A run with no end, a minute short of a day old, on a browser five minutes ahead: no warning yet. Then a minute
    // past the day on a browser a quarter of an hour behind: warned about.
    state.runs = [botRun()];
    state.clock = '2026-11-07T09:59:00Z';
    clockAt('2026-11-07T10:04:00Z');
    renderPage();
    const old = await within(await runs()).findByRole('group', { name: 'Current run' });
    expect(old).toHaveTextContent('Elapsed23 h 59 minEndsWhen stopped');
    expect(within(old).queryByText(/no end time/)).toBeNull();
    state.clock = '2026-11-07T10:01:00Z';
    clockAt('2026-11-07T09:46:00Z');
    expect(await within(old).findByText(/no end time/, {}, { timeout: 10_000 })).toBeInTheDocument();
    expect(old).toHaveTextContent('Elapsed1 d 0 hEndsWhen stopped');
    // The history counts the run to the same answer.
    const history = within(await runs()).getByRole('table', { name: 'Recent runs' });
    await waitFor(() => expect(within(history).getAllByRole('row')[1]).toHaveTextContent('1 d 0 h so far'), {
      timeout: 10_000,
    });
  }, 30_000);

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

    // The longest timed run, a day, half a minute past its end and not yet confirmed: it ends by itself.
    state.runs = [botRun({ endsAt: '2026-11-07T10:00:00Z', status: 'STOPPING' })];
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
    expect(recent).not.toHaveTextContent('Market close');
  });

  it('says which run the market close ended, since no worker confirmed that stop', async () => {
    const { state } = exchange('CLOSED');
    state.bots = 3;
    state.runs = [
      botRun({
        runId: 'b',
        status: 'STOPPED',
        desiredState: 'STOPPED',
        stopRequestedAt: '2026-11-06T10:05:00Z',
        stoppedAt: '2026-11-06T10:05:00Z',
        window: {
          startSequence: '41',
          stopSequence: '90',
          endSequence: '90',
          endedBy: 'MARKET_CLOSE',
          endedAt: '2026-11-06T10:05:00Z',
        },
      }),
    ];
    renderPage();

    const recent = await screen.findByRole('table', { name: 'Recent runs' });
    expect(within(recent).getByRole('row', { name: /10:00:00 UTC/ })).toHaveTextContent('10:05:00 UTC · Market close');
  });

  it("keeps a run that ended before the close as not confirmed, though the close left its time on it", async () => {
    const { state } = exchange('CLOSED');
    const closedAt = '2026-11-06T12:00:00Z';
    state.market = { ...market('CLOSED'), closedAt };
    state.bots = 3;
    // What the close records on every run no worker had confirmed: a stop, at the time it closed.
    const recorded = { status: 'STOPPED', desiredState: 'STOPPED', stopRequestedAt: closedAt, stoppedAt: closedAt } as const;
    const window = (endedBy: BotRun['window']['endedBy'], endedAt: string | null): BotRun['window'] => ({
      startSequence: '41',
      stopSequence: null,
      endSequence: endedAt === null ? null : '90',
      endedBy,
      endedAt,
    });
    state.runs = [
      // Still going when the market closed: the close ended it.
      botRun({ runId: 'd', startedAt: '2026-11-06T11:00:00Z', ...recorded, window: window('MARKET_CLOSE', closedAt) }),
      // Ended by the next run's start, its worker never heard from again.
      botRun({ runId: 'c', startedAt: '2026-11-06T10:00:00Z', ...recorded, window: window('NEXT_START', '2026-11-06T11:00:00Z') }),
      // Confirmed by its worker long before.
      finishedRun({ runId: 'b', startedAt: '2026-11-06T09:00:00Z', stoppedAt: '2026-11-06T09:10:00Z' }),
      // From before windows were kept, never confirmed; the exchange writes the same moment with its fraction.
      botRun({
        runId: 'a',
        startedAt: '2026-11-06T08:00:00Z',
        ...recorded,
        stoppedAt: '2026-11-06T12:00:00.000Z',
        window: window('NOT_KEPT', null),
        activity: { ...botRun().activity, stopFailures: 1 },
      }),
    ];
    renderPage();

    const recent = await screen.findByRole('table', { name: 'Recent runs' });
    const endedCell = (started: RegExp) => within(recent).getByRole('row', { name: started }).lastElementChild;
    expect(endedCell(/11:00:00 UTC/)).toHaveTextContent(/^12:00:00 UTC · Market close$/);
    // No worker confirmed a stop at noon: that is when the market closed, and says nothing of these runs.
    expect(endedCell(/10:00:00 UTC/)).toHaveTextContent(/^Not confirmed$/);
    expect(endedCell(/08:00:00 UTC/)).toHaveTextContent(/^Not confirmed · 1 not canceled$/);
    expect(endedCell(/09:00:00 UTC/)).toHaveTextContent(/^09:10:00 UTC$/);
  });

  it('says how long each run lasted in the history, and how long the one still going has been', async () => {
    const { state } = exchange('OPEN');
    state.runs = [
      // Still going, its worker lost on the first day.
      botRun({ runId: 'c', startedAt: '2026-11-10T10:00:00Z', status: 'STALE' }),
      // Confirmed by its worker ten minutes in.
      finishedRun({ runId: 'b' }),
      // Stopped without ever having had a worker.
      botRun({
        runId: 'a',
        status: 'STOPPED',
        desiredState: 'STOPPED',
        startedAt: '2026-11-06T09:00:00Z',
        worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null },
      }),
    ];
    clockAt('2026-11-23T14:30:00Z');
    renderPage();

    const history = await within(await runs()).findByRole('table', { name: 'Recent runs' });
    expect(within(history).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Compare',
      'Started',
      'Ran for',
      'Bots',
      'Placed',
      'Canceled',
      'Ended',
    ]);
    // After the tick box: how long, then the bots.
    const ranFor = () =>
      within(history)
        .getAllByRole('row')
        .slice(1)
        .map((row) => within(row).getAllByRole('cell')[1]?.textContent);
    expect(ranFor()).toEqual(['13 d 4 h so far', '10 min', 'Unknown']);

    // The run still going is counted to the same answer as its readout above.
    clockAt('2026-11-23T15:30:00Z');
    await waitFor(() => expect(ranFor()).toEqual(['13 d 5 h so far', '10 min', 'Unknown']), { timeout: 10_000 });
    expect(within(await runs()).getByRole('group', { name: 'Current run' })).toHaveTextContent('Elapsed13 d 5 h');

    // Sorted by length, shortest first: the one of unknown length, then ten minutes, then thirteen days.
    fireEvent.click(within(history).getByRole('button', { name: 'Ran for' }));
    expect(ranFor()).toEqual(['Unknown', '10 min', '13 d 5 h so far']);
    fireEvent.click(within(history).getByRole('button', { name: 'Ran for' }));
    expect(ranFor()).toEqual(['13 d 5 h so far', '10 min', 'Unknown']);
  }, 15_000);

  it('keeps an unconfirmed run at its own length in the history once the next run has started', async () => {
    const { state } = exchange('OPEN');
    // Each window ended when the run after it started, three days and two days after the run's own start.
    const nextStart = (endedAt: string) => ({
      startSequence: '41',
      stopSequence: null,
      endSequence: '900',
      endedBy: 'NEXT_START' as const,
      endedAt,
    });
    state.runs = [
      finishedRun({
        runId: 'd',
        startedAt: '2026-11-09T10:00:00Z',
        stoppedAt: '2026-11-09T10:10:00Z',
        window: { ...finishedRun().window, endedAt: '2026-11-09T10:10:00Z' },
      }),
      // No end; its worker last reported 80 seconds in, and it was stopped half an hour in with none to confirm.
      botRun({
        runId: 'c',
        status: 'STOPPED',
        desiredState: 'STOPPED',
        startedAt: '2026-11-07T10:00:00Z',
        stopRequestedAt: '2026-11-07T10:30:00Z',
        worker: { workerId: 'sim-1', heartbeatAt: '2026-11-07T10:01:20Z', leaseExpiresAt: '2026-11-07T10:01:35Z' },
        window: nextStart('2026-11-09T10:00:00Z'),
      }),
      // For 60 minutes, its worker gone two seconds before the end.
      botRun({
        runId: 'b',
        status: 'STOPPED',
        desiredState: 'STOPPED',
        startedAt: '2026-11-06T12:00:00Z',
        endsAt: '2026-11-06T13:00:00Z',
        stopRequestedAt: '2026-11-06T13:00:00Z',
        worker: { workerId: 'sim-1', heartbeatAt: '2026-11-06T12:59:58Z', leaseExpiresAt: '2026-11-06T13:00:13Z' },
        window: nextStart('2026-11-07T10:00:00Z'),
      }),
      // For 60 minutes, and no worker ever took it.
      botRun({
        runId: 'a',
        status: 'STOPPED',
        desiredState: 'STOPPED',
        endsAt: '2026-11-06T11:00:00Z',
        stopRequestedAt: '2026-11-06T11:00:00Z',
        worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null },
        window: nextStart('2026-11-06T12:00:00Z'),
      }),
    ];
    clockAt('2026-11-23T14:30:00Z');
    renderPage();

    const history = await within(await runs()).findByRole('table', { name: 'Recent runs' });
    const cells = within(history)
      .getAllByRole('row')
      .slice(1)
      .map((row) => {
        const all = within(row).getAllByRole('cell');
        return [all[1]?.textContent, all[all.length - 1]?.textContent];
      });
    // Not the two days and the day until the next start: what each run's own stop, end and last report allow.
    expect(cells).toEqual([
      ['10 min', '10:10:00 UTC'],
      ['About 1 min 20 s', 'Not confirmed'],
      ['About 59 min 58 s', 'Not confirmed'],
      ['Unknown', 'Not confirmed'],
    ]);
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
    // The states the market can be put in from here are explained beside its controls.
    expect(screen.getByRole('button', { name: 'What are halted and closed?' })).toBeInTheDocument();
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

describe('closing the market on the operator screen', () => {
  beforeEach(() => resetSession());

  const CLOSE = '/api/v1/admin/market/close';
  const closes = (server: ReturnType<typeof exchange>['server']) =>
    server.requests.filter((request) => request.method === 'POST' && request.url === CLOSE);
  const keys = (server: ReturnType<typeof exchange>['server']) =>
    closes(server).map((request) => request.headers['Idempotency-Key']);
  const dialog = () => screen.getByRole('alertdialog', { name: 'Close the market for good?' });
  /** Opens the confirmation and confirms it. */
  const confirmClose = async () => {
    fireEvent.click(await button('Close the market'));
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Close the market for good' }));
  };
  /** What the market panel says of the last command, when it is a warning; other panels have alerts of their own. */
  const said = () => within(screen.getByRole('region', { name: 'Market' })).findByRole('alert');
  const closedAnswer = { state: 'CLOSED', commandSequence: '8', canceledOrders: 1234, endedRuns: 1 };
  /** An answer the test gives when it chooses to: the request stays on its way until then. */
  const answerLater = () => {
    type Reply = { status: number; json?: unknown; problem?: unknown };
    let answer: (reply: Reply) => void = () => {};
    const reply = new Promise<Reply>((resolve) => {
      answer = resolve;
    });
    return { reply, answer: (given: Reply) => answer(given) };
  };

  it('offers a close only for a market that has opened, and says why not before', async () => {
    exchange('SETUP');
    const setup = renderPage();
    expect(await button('Close the market')).toBeDisabled();
    expect(await button('Close the market')).toHaveAccessibleDescription('Only a market that has opened can be closed.');
    setup.unmount();

    exchange('NOT_SET_UP', []);
    const none = renderPage();
    expect(await button('Close the market')).toBeDisabled();
    none.unmount();

    for (const state of ['OPEN', 'HALTED'] as const) {
      exchange(state);
      const opened = renderPage();
      expect(await button('Close the market')).toBeEnabled();
      // Said before it is pressed: this one has no way back, and pressing it does not close yet.
      expect(await button('Close the market')).toHaveAccessibleDescription('Ends this market for good. Asks first.');
      opened.unmount();
    }
  });

  it('asks first, saying what a close does and that it cannot be undone, and sends nothing until confirmed', async () => {
    const { server } = exchange('OPEN');
    renderPage();

    const close = await button('Close the market');
    close.focus();
    fireEvent.click(close);

    const asked = dialog();
    expect(asked).toHaveAttribute('open');
    for (const said of [
      'This cannot be undone.',
      'A closed market never opens again.',
      'Trading ends for good: no order is taken after the close.',
      'Every resting order is cancelled, and what it had reserved is released.',
      'Every bot run ends.',
      "Every bot's token stops working.",
      'Nothing is deleted: portfolios, orders, trades, accounts and bot runs stay readable.',
    ]) {
      expect(asked).toHaveAccessibleDescription(expect.stringContaining(said));
    }
    // Focus moves into the confirmation, onto the button that changes nothing: the close is never the default.
    const keep = within(asked).getByRole('button', { name: 'Do not close' });
    expect(keep).toHaveFocus();
    expect(within(asked).getAllByRole('button').map((each) => each.textContent)).toEqual([
      'Do not close',
      'Close the market for good',
    ]);
    expect(closes(server)).toHaveLength(0);

    // Not closing puts focus back on the button the confirmation came from.
    fireEvent.click(keep);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(await button('Close the market')).toHaveFocus();
    expect(closes(server)).toHaveLength(0);
    expect(screen.getByText('Open', { selector: '.state-badge' })).toBeInTheDocument();
  });

  it('opens the confirmation as a modal, and closes it while it is still on the page', async () => {
    // The stand-in for a browser's dialog cannot hold the keyboard in or hand focus back, so what the page relies on
    // is checked as what it calls: a modal, which does both, closed before it is removed, or nothing is handed back.
    const showModal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
    const closing = HTMLDialogElement.prototype.close;
    const closedWhile: boolean[] = [];
    const close = vi.spyOn(HTMLDialogElement.prototype, 'close').mockImplementation(function (this: HTMLDialogElement) {
      closedWhile.push(this.isConnected);
      closing.call(this);
    });
    try {
      exchange('OPEN');
      renderPage();

      fireEvent.click(await button('Close the market'));
      expect(showModal).toHaveBeenCalledTimes(1);
      expect(showModal.mock.contexts[0]).toBe(dialog());
      expect(dialog()).toHaveAttribute('open');

      fireEvent.click(within(dialog()).getByRole('button', { name: 'Do not close' }));
      expect(screen.queryByRole('alertdialog')).toBeNull();
      expect(closedWhile).toEqual([true]);
    } finally {
      showModal.mockRestore();
      close.mockRestore();
    }
  });

  it('leaves the market as it is on Escape, with focus back where it was', async () => {
    const { server } = exchange('HALTED');
    renderPage();

    fireEvent.click(await button('Close the market'));
    // What a browser sends a modal dialog for Escape.
    const escape = new Event('cancel', { cancelable: true });
    fireEvent(dialog(), escape);

    expect(escape.defaultPrevented).toBe(true);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(await button('Close the market')).toHaveFocus();
    expect(closes(server)).toHaveLength(0);
    // And it can be asked again.
    fireEvent.click(await button('Close the market'));
    expect(within(dialog()).getByRole('button', { name: 'Do not close' })).toHaveFocus();
  });

  it('closes once confirmed, under one key, and says what the close did from its answer', async () => {
    const { server, state } = exchange('OPEN');
    server.on(`POST ${CLOSE}`, () => {
      state.market = market('CLOSED');
      return { status: 200, json: closedAnswer };
    });
    renderPage();

    await confirmClose();
    // On its way: said so, the confirmation gone, and neither a second close nor another transition on offer.
    expect(screen.queryByRole('alertdialog')).toBeNull();
    const closing = screen.getByText(/^Closing the market…/);
    expect(closing).toHaveAttribute('role', 'status');
    // Named, with the seconds it has taken so far, and what it is doing meanwhile.
    expect(closing).toHaveTextContent(/^Closing the market… \d+ s$/);
    expect(screen.getByText(/^Every resting order is being cancelled, which can take up to about half a minute/))
      .toBeInTheDocument();
    for (const name of ['Close the market', 'Halt trading', 'Open market', 'Resume trading']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }

    expect(
      await screen.findByText('The market is closed. 1,234 resting orders were cancelled, and 1 bot run ended.'),
    ).toHaveAttribute('role', 'status');
    expect(closes(server)).toHaveLength(1);
    expect(keys(server)[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(closes(server)[0]?.body).toBeUndefined();
    // The page turns into the ended market's, and what the close did stays on it.
    await waitFor(() => expect(screen.getByText('Closed', { selector: '.state-badge' })).toBeInTheDocument());
    expect(await screen.findByRole('region', { name: 'This market has ended' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close the market' })).toBeNull();
    expect(screen.getByText('The market is closed. 1,234 resting orders were cancelled, and 1 bot run ended.')).toBeInTheDocument();
    // The button the close was started from is gone, so focus goes to the panel's heading, beside the answer.
    expect(screen.getByRole('heading', { name: 'Market' })).toHaveFocus();
  });

  it('counts what a close did in words, whatever the numbers', () => {
    const closed = (canceledOrders: number, endedRuns: number) =>
      closedText({ state: 'CLOSED', commandSequence: '8', canceledOrders, endedRuns });
    expect(closed(0, 0)).toBe('The market is closed. No order was resting, and no bot run was going.');
    expect(closed(1, 1)).toBe('The market is closed. 1 resting order was cancelled, and 1 bot run ended.');
    expect(closed(200000, 2)).toBe('The market is closed. 200,000 resting orders were cancelled, and 2 bot runs ended.');
    expect(closed(0, 1)).toBe('The market is closed. No order was resting, and 1 bot run ended.');
  });

  it('reads everything of the simulation again once the market has closed, whoever closed it', async () => {
    const { server, state } = exchange('OPEN');
    server.on('GET /api/v1/admin/health', {
      status: 200,
      json: {
        writer: { available: true, queueDepth: 0, queueCapacity: 256 },
        lastMinute: { committed: 0, failed: 0, withinBudget: 0, refused: 0 },
        outbox: { admitting: true, pendingEvents: 0, oldestPendingMillis: 0 },
      },
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);
    await screen.findByRole('table', { name: 'Instruments' });
    await screen.findByText('No accounts yet.');
    const lists = ['/api/v1/instruments', '/api/v1/admin/accounts', '/api/v1/admin/bots', '/api/v1/admin/bot-runs', '/api/v1/admin/health'];
    const reads = () => lists.map((path) => server.requests.filter((request) => request.url.split('?')[0] === path).length);
    await waitFor(() => expect(reads().every((count) => count > 0)).toBe(true));
    const before = reads();

    // Closed elsewhere: by another operator, or from another tab. This page only hears of it from the status.
    state.market = market('CLOSED');
    await client.invalidateQueries({ queryKey: ['market'] });

    await screen.findByRole('region', { name: 'This market has ended' });
    // Each list at once, not at its own next poll; the accounts and the bots have none.
    await waitFor(() => expect(reads().every((count, index) => count > before[index]!)).toBe(true), { timeout: 900 });
  });

  it('says a close that found the market already closed changed nothing, and one refused in setup why', async () => {
    const { server } = exchange('OPEN');
    const refusal = (state: string | undefined) => ({
      status: 409,
      problem: {
        status: 409,
        code: 'MARKET_TRANSITION_NOT_ALLOWED',
        detail: `The market is ${state}; it cannot close from there`,
        ...(state === undefined ? {} : { state }),
        commandSequence: '9',
      },
    });
    const answers = [
      refusal('CLOSED'),
      refusal('SETUP'),
      refusal(undefined),
      { status: 409, problem: { status: 409, code: 'MARKET_NOT_SET_UP', detail: 'No market has been set up yet' } },
    ];
    server.on(`POST ${CLOSE}`, () => answers[closes(server).length - 1]!);
    renderPage();

    await confirmClose();
    expect(await screen.findByText('The market had already closed, so this close changed nothing.')).toHaveAttribute('role', 'alert');
    // Answered for good: the next attempt is a new command under a new key.
    await confirmClose();
    expect(
      await screen.findByText(
        'The market has not opened yet, and only a market that has opened can be closed. Nothing was changed.',
      ),
    ).toHaveAttribute('role', 'alert');
    // A state this page does not know of, or none: the exchange's own words.
    await confirmClose();
    expect(await screen.findByText('The market is undefined; it cannot close from there')).toBeInTheDocument();
    await confirmClose();
    expect(await screen.findByText('No market has been set up yet')).toBeInTheDocument();
    expect(new Set(keys(server)).size).toBe(4);
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('asks again under the same key when the close outlasts the wait for its answer', async () => {
    const { server, state } = exchange('OPEN');
    server.on(`POST ${CLOSE}`, () => {
      if (closes(server).length === 1) {
        return {
          status: 504,
          problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'The outcome is not known yet', commandKey: 'k' },
        };
      }
      state.market = market('CLOSED');
      return { status: 200, json: closedAnswer };
    });
    renderPage();

    await confirmClose();
    const unknown = await said();
    expect(unknown).toHaveTextContent(
      'The close is taking longer than the exchange waits before it answers, so its outcome is not known yet. It goes on by itself.',
    );
    expect(unknown).toHaveTextContent('Asking again sends the same key, so the market can never be closed twice.');
    // No second close while this one is not known, and no other transition either.
    expect(await button('Close the market')).toBeDisabled();
    expect(await button('Halt trading')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();

    fireEvent.click(within(unknown).getByRole('button', { name: 'Ask again' }));
    expect(
      await screen.findByText('The market is closed. 1,234 resting orders were cancelled, and 1 bot run ended.'),
    ).toBeInTheDocument();
    expect(keys(server)).toHaveLength(2);
    expect(keys(server)[1]).toBe(keys(server)[0]);
  });

  it('says a close left unfinished is a closed market, with no retry as if nothing had happened', async () => {
    const { server, state } = exchange('OPEN');
    const answers = [
      {
        status: 503,
        problem: {
          status: 503,
          code: 'MARKET_CLOSE_UNFINISHED',
          detail: 'The market is closed, but 4200 orders still rest.',
          state: 'CLOSED',
          commandSequence: '8',
          restingOrders: 4200,
          commandKey: 'k',
        },
      },
      // Asked again before the restart: the exchange is still stopped.
      { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE', detail: 'Trading is unavailable' } },
      { status: 200, json: { ...closedAnswer, canceledOrders: 9000 } },
    ];
    server.on(`POST ${CLOSE}`, () => {
      state.market = market('CLOSED');
      return answers[closes(server).length - 1]!;
    });
    renderPage();

    await confirmClose();
    const unfinished = await said();
    expect(unfinished).toHaveTextContent(
      "The market is closed, but at most 4,200 orders still rest. The exchange has stopped trading and cancels them when it is restarted. The count is the exchange's own from when it stopped; fewer may rest. Nothing needs to be sent again.",
    );
    // An upper bound is never said as a count: no sentence on the page has the number without "at most".
    expect(document.body.textContent).not.toMatch(/(?<!at most )4,200 orders still rest/i);
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(screen.queryByText(/Not confirmed/)).toBeNull();
    // The market is closed, and the page shows it so: with the orders that still rest, not as if all were cancelled.
    const ended = await screen.findByRole('region', { name: 'This market has ended' });
    expect(ended).toHaveTextContent(
      'At most 4,200 orders still rest, with their holds: the close was left unfinished. The exchange has stopped, and cancels them when it is restarted.',
    );
    expect(ended).not.toHaveTextContent(/was cancelled|A close cancels/);
    expect(screen.queryByRole('button', { name: 'Close the market' })).toBeNull();

    // What is left to ask for is the close's answer, after the restart, under the same key. Asked too early, the
    // exchange is still stopped, and the market is no less closed for that.
    fireEvent.click(screen.getByRole('button', { name: "Ask for the close's answer" }));
    await waitFor(() => expect(closes(server)).toHaveLength(2));
    expect(await said()).toHaveTextContent('The market is closed, but at most 4,200 orders still rest.');
    expect(screen.queryByText(/whether the market closed is not known/)).toBeNull();
    expect(ended).toHaveTextContent('At most 4,200 orders still rest, with their holds');

    fireEvent.click(await button("Ask for the close's answer"));
    expect(
      await screen.findByText('The market is closed. 9,000 resting orders were cancelled, and 1 bot run ended.'),
    ).toBeInTheDocument();
    expect(new Set(keys(server)).size).toBe(1);
    expect(keys(server)).toHaveLength(3);
    // Only now, with the close's own answer, does the page say they were all cancelled.
    expect(ended).toHaveTextContent('Every order that still rested was cancelled and its hold released.');
    expect(ended).not.toHaveTextContent('still rest,');
  });

  it('asks for the answer of an unfinished close without saying anything is being cancelled', async () => {
    const { server, state } = exchange('OPEN');
    const later = answerLater();
    server.on(`POST ${CLOSE}`, () => {
      state.market = market('CLOSED');
      return closes(server).length === 1
        ? { status: 503, problem: { status: 503, code: 'MARKET_CLOSE_UNFINISHED', detail: 'unfinished', restingOrders: 7 } }
        : later.reply;
    });
    renderPage();

    await confirmClose();
    fireEvent.click(await button("Ask for the close's answer"));

    // The exchange is stopped, or just back: nothing is being cancelled by this request, which only asks.
    expect(await screen.findByText("Asking for the close's answer…")).toHaveAttribute('role', 'status');
    expect(screen.queryByText(/Closing the market…/)).toBeNull();
    expect(screen.getByRole('region', { name: 'This market has ended' })).toHaveTextContent('At most 7 orders still rest, with their holds');
    later.answer({ status: 200, json: closedAnswer });
    expect(await screen.findByText(/^The market is closed\. 1,234 resting orders were cancelled/)).toBeInTheDocument();
  });

  it('does not say every order was cancelled while the close is still cancelling them', async () => {
    const { server, state } = exchange('OPEN');
    const later = answerLater();
    server.on(`POST ${CLOSE}`, () => {
      // Closed from the close's first transaction; the answer comes once the last order is cancelled.
      state.market = market('CLOSED');
      return later.reply;
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);

    await confirmClose();
    await waitFor(() => expect(closes(server)).toHaveLength(1));
    // The status is polled meanwhile, and says CLOSED well before the close answers.
    await client.invalidateQueries({ queryKey: ['market'] });

    const ended = await screen.findByRole('region', { name: 'This market has ended' });
    expect(ended).toHaveTextContent(
      'The orders that still rested are being cancelled and their holds released; until the close answers, some still rest.',
    );
    expect(ended).not.toHaveTextContent(/was cancelled|A close cancels/);
    expect(screen.getByText(/^Closing the market…/)).toBeInTheDocument();

    later.answer({ status: 200, json: closedAnswer });
    expect(await screen.findByText(/^The market is closed\. 1,234 resting orders were cancelled/)).toBeInTheDocument();
    expect(ended).toHaveTextContent('Every order that still rested was cancelled and its hold released.');
  });

  it('says the market is closed once the status says so, whatever the close was answered or was not', async () => {
    const { server, state } = exchange('OPEN');
    const later = answerLater();
    const answers = [
      // The answer of a close that went on, stopped half way and was answered unfinished is lost with this one.
      {
        status: 504,
        problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'The outcome is not known yet', commandKey: 'k' },
      },
      // The same key then meets the stopped exchange,
      { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE', detail: 'Trading is unavailable' } },
      // then the proxy, with the exchange being restarted: no problem body at all.
      { status: 502 },
    ];
    server.on(`POST ${CLOSE}`, () => {
      state.market = market('CLOSED');
      return answers[closes(server).length - 1] ?? later.reply;
    });
    renderPage();

    await confirmClose();
    const closedUnanswered =
      'The market is closed, but the close has not given its answer here, so how many orders it cancelled and how many runs it ended is not known yet. Until it answers, some orders may still rest';
    for (const sent of [1, 2, 3]) {
      await waitFor(() => expect(closes(server)).toHaveLength(sent));
      await waitFor(async () => expect(await said()).toHaveTextContent(closedUnanswered));
      // Nothing that doubts the close itself, beside a badge that says Closed, and no retry as if nothing happened.
      const note = await said();
      expect(note).toHaveTextContent('Asking sends the same key, so nothing is closed twice.');
      expect(note).not.toHaveTextContent(/is not known here|outcome is not known yet|Not confirmed/);
      expect(screen.getByText('Closed', { selector: '.state-badge' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Ask again' })).toBeNull();
      expect(screen.getByRole('region', { name: 'This market has ended' })).toHaveTextContent(
        'The close has not answered yet, so some orders may still rest with their holds.',
      );
      fireEvent.click(within(note).getByRole('button', { name: "Ask for the close's answer" }));
    }

    // Asked a fourth time, and this one waits: the market being closed, it asks, and closes nothing.
    expect(await screen.findByText("Asking for the close's answer…")).toHaveAttribute('role', 'status');
    expect(screen.queryByText(/Closing the market…/)).toBeNull();
    later.answer({ status: 200, json: closedAnswer });
    expect(await screen.findByText(/^The market is closed\. 1,234 resting orders were cancelled/)).toBeInTheDocument();
    expect(keys(server)).toHaveLength(4);
    expect(new Set(keys(server)).size).toBe(1);
  });

  it('finds a close with no answer as it was on coming back to the page, and sends the same key', async () => {
    const { server, state } = exchange('OPEN');
    server.on(`POST ${CLOSE}`, () => {
      if (closes(server).length === 1) {
        return {
          status: 504,
          problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'The outcome is not known yet', commandKey: 'k' },
        };
      }
      state.market = market('CLOSED');
      return { status: 200, json: closedAnswer };
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const before = renderWith(client);
    await confirmClose();
    expect(await said()).toHaveTextContent('The close is taking longer than the exchange waits before it answers');

    // To the terminal and back, to a comparison, to Help: each takes this page down and builds it anew.
    before.unmount();
    renderWith(client);

    const unknown = await said();
    expect(unknown).toHaveTextContent('The close is taking longer than the exchange waits before it answers');
    // Not a market that could be closed afresh, under a new key.
    expect(await button('Close the market')).toBeDisabled();
    expect(await button('Halt trading')).toBeDisabled();
    // Coming back is not an answer arriving: focus is left where the page put it.
    expect(screen.getByRole('heading', { name: 'Market' })).not.toHaveFocus();

    fireEvent.click(within(unknown).getByRole('button', { name: 'Ask again' }));
    expect(await screen.findByText(/^The market is closed\. 1,234 resting orders were cancelled/)).toBeInTheDocument();
    expect(keys(server)).toHaveLength(2);
    expect(keys(server)[1]).toBe(keys(server)[0]);
  });

  it('keeps an unfinished close and its count through leaving the page', async () => {
    const { server, state } = exchange('OPEN');
    server.on(`POST ${CLOSE}`, () => {
      state.market = market('CLOSED');
      return closes(server).length === 1
        ? { status: 503, problem: { status: 503, code: 'MARKET_CLOSE_UNFINISHED', detail: 'unfinished', restingOrders: 4200 } }
        : { status: 200, json: closedAnswer };
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const before = renderWith(client);
    await confirmClose();
    expect(await said()).toHaveTextContent('The market is closed, but at most 4,200 orders still rest.');
    before.unmount();
    renderWith(client);

    expect(await said()).toHaveTextContent('The market is closed, but at most 4,200 orders still rest.');
    expect(await screen.findByRole('region', { name: 'This market has ended' })).toHaveTextContent(
      'At most 4,200 orders still rest, with their holds',
    );
    fireEvent.click(await button("Ask for the close's answer"));
    expect(await screen.findByText(/^The market is closed\. 1,234 resting orders were cancelled/)).toBeInTheDocument();
    expect(new Set(keys(server)).size).toBe(1);
  });

  it('goes on with a close that was on its way when the page was left, and shows its answer on coming back', async () => {
    const { server, state } = exchange('OPEN');
    const later = answerLater();
    server.on(`POST ${CLOSE}`, () => later.reply);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const before = renderWith(client);
    await confirmClose();
    await waitFor(() => expect(closes(server)).toHaveLength(1));
    before.unmount();

    // Back while it is still on its way: said so, and no second close on offer.
    const during = renderWith(client);
    expect(await screen.findByText(/^Closing the market…/)).toBeInTheDocument();
    for (const name of ['Close the market', 'Halt trading']) {
      expect(await button(name)).toBeDisabled();
    }
    during.unmount();

    // Answered with no page there to show it.
    state.market = market('CLOSED');
    later.answer({ status: 200, json: closedAnswer });
    await waitFor(() => expect(client.getQueryState(['market'])?.isInvalidated).toBe(true));
    renderWith(client);
    expect(await screen.findByText('The market is closed. 1,234 resting orders were cancelled, and 1 bot run ended.')).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'This market has ended' })).toHaveTextContent(
      'Every order that still rested was cancelled and its hold released.',
    );
    expect(closes(server)).toHaveLength(1);
    expect(screen.getByRole('heading', { name: 'Market' })).not.toHaveFocus();
  });

  it("keeps a close to the session and the market it was sent in", async () => {
    const NEXT = '10000000-0000-0000-0000-000000000002';
    const { server, state } = exchange('OPEN');
    server.on(`POST ${CLOSE}`, { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE', detail: 'Trading is unavailable' } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const before = renderWith(client);
    await confirmClose();
    expect(await said()).toHaveTextContent('Trading is unavailable');
    before.unmount();

    // Another session's page, as after signing out and in, starts with nothing: the cache went with the session.
    const other = renderPage();
    expect(await button('Close the market')).toBeEnabled();
    expect(within(screen.getByRole('region', { name: 'Market' })).queryByRole('alert')).toBeNull();
    other.unmount();

    // And another market is not the one that close was sent to.
    state.market = { ...market('OPEN'), simulationId: NEXT };
    await client.invalidateQueries({ queryKey: ['market'] });
    renderWith(client);
    await waitFor(async () => expect(await button('Close the market')).toBeEnabled());
    expect(within(screen.getByRole('region', { name: 'Market' })).queryByRole('alert')).toBeNull();
  });

  it('lets the next command take the place of a refused close, also after leaving the page', async () => {
    const { server, state } = exchange('OPEN');
    server
      .on(`POST ${CLOSE}`, { status: 409, problem: { status: 409, code: 'IDEMPOTENCY_CONFLICT', detail: 'The key was used for another command' } })
      .on('POST /api/v1/admin/market/halt', () => {
        state.market = market('HALTED');
        return { status: 200, json: { state: 'HALTED', commandSequence: '9' } };
      });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const before = renderWith(client);
    await confirmClose();
    expect(await screen.findByText('The key was used for another command')).toBeInTheDocument();

    fireEvent.click(await button('Halt trading'));
    expect(await screen.findByText('Trading is halted. Cancels still work.')).toBeInTheDocument();
    expect(screen.queryByText('The key was used for another command')).toBeNull();
    before.unmount();
    renderWith(client);
    await button('Resume trading');
    expect(screen.queryByText('The key was used for another command')).toBeNull();
  });

  it('says what an unfinished close left as an upper bound: one order, none counted, or no count at all', async () => {
    const { server, state } = exchange('HALTED');
    server.on(`POST ${CLOSE}`, () => {
      state.market = market('CLOSED');
      return { status: 503, problem: { status: 503, code: 'MARKET_CLOSE_UNFINISHED', detail: 'unfinished', restingOrders: 1 } };
    });
    renderPage();

    await confirmClose();
    expect(await said()).toHaveTextContent(
      'The market is closed, but at most 1 order still rests. The exchange has stopped trading and cancels it when it is restarted.',
    );
    expect(await screen.findByRole('region', { name: 'This market has ended' })).toHaveTextContent(
      'At most 1 order still rests, with its hold',
    );

    expect(unfinishedText(1)).toContain("The count is the exchange's own from when it stopped; fewer may rest.");
    expect(unfinishedText(12000)).toMatch(/^The market is closed, but at most 12,000 orders still rest\. .* cancels them when it is restarted\./);
    // No count given: nothing to bound, and nothing said of a count.
    expect(unfinishedText(null)).toBe(
      'The market is closed, but some orders still rest. The exchange has stopped trading and cancels them when it is restarted.',
    );
    // None counted is not "at most 0 orders": the close is unfinished all the same, and the restart finishes it.
    expect(unfinishedText(0)).toBe(
      'The market is closed and the exchange counted no order still resting, but the close did not finish. The exchange has stopped trading and finishes the close when it is restarted.',
    );
  });

  it('says so when trading is unavailable, without claiming the market closed or did not', async () => {
    const { server } = exchange('OPEN');
    server.on(`POST ${CLOSE}`, {
      status: 503,
      problem: { status: 503, code: 'TRADING_UNAVAILABLE', detail: 'Trading is unavailable' },
    });
    renderPage();

    await confirmClose();
    const unavailable = await said();
    expect(unavailable).toHaveTextContent(
      'Trading is unavailable: the exchange has stopped taking commands, so whether the market closed is not known here.',
    );
    expect(unavailable).not.toHaveTextContent('The market is closed');
    expect(screen.getByText('Open', { selector: '.state-badge' })).toBeInTheDocument();
    expect(await button('Close the market')).toBeDisabled();

    fireEvent.click(within(unavailable).getByRole('button', { name: 'Ask again' }));
    await waitFor(() => expect(closes(server)).toHaveLength(2));
    expect(keys(server)[1]).toBe(keys(server)[0]);
  });

  it('retries a close the busy exchange never queued under the same key', async () => {
    const { server, state } = exchange('OPEN');
    server.on(`POST ${CLOSE}`, () => {
      if (closes(server).length === 1) {
        return { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'busy' } };
      }
      state.market = market('CLOSED');
      return { status: 200, json: { ...closedAnswer, canceledOrders: 0, endedRuns: 0 } };
    });
    renderPage();

    await confirmClose();
    expect(await screen.findByText(/Not confirmed: the exchange was busy or did not answer/)).toBeInTheDocument();
    fireEvent.click(await button('Retry'));

    expect(await screen.findByText('The market is closed. No order was resting, and no bot run was going.')).toBeInTheDocument();
    expect(keys(server)).toHaveLength(2);
    expect(keys(server)[1]).toBe(keys(server)[0]);
  });

  it('leaves focus where the user moved it while the close was on its way', async () => {
    const { server, state } = exchange('OPEN');
    server.on(`POST ${CLOSE}`, () => {
      state.market = market('CLOSED');
      return { status: 200, json: closedAnswer };
    });
    renderPage();
    await screen.findByRole('table', { name: 'Instruments' });

    await confirmClose();
    const elsewhere = screen.getByRole('button', { name: 'What are halted and closed?' });
    elsewhere.focus();

    expect(await screen.findByText(/^The market is closed\./)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Market' })).not.toHaveFocus();
  });

  it('goes on halting and resuming as before, one command at a time with the close', async () => {
    const { server, state } = exchange('OPEN');
    server.on('POST /api/v1/admin/market/halt', () => {
      state.market = market('HALTED');
      return { status: 200, json: { state: 'HALTED', commandSequence: '9' } };
    });
    renderPage();

    fireEvent.click(await button('Halt trading'));
    // While the halt is on its way, the close waits its turn.
    expect(screen.getByRole('button', { name: 'Close the market' })).toBeDisabled();
    expect(await screen.findByText('Trading is halted. Cancels still work.')).toBeInTheDocument();
    await waitFor(async () => expect(await button('Close the market')).toBeEnabled());
    expect(closes(server)).toHaveLength(0);
  });
});

describe('the operator screen when the exchange names another simulation', () => {
  beforeEach(() => resetSession());

  const NEXT = '10000000-0000-0000-0000-000000000002';
  /** The address the page is at, so what it keeps there can be read. */
  function Address() {
    return <span data-testid="address">{useLocation().search}</span>;
  }

  it('shows the new one at once, with nothing of the closed market left on the page', async () => {
    const { server, state } = exchange('OPEN');
    const finished = (n: number) =>
      finishedRun({ runId: `20000000-0000-0000-0000-00000000000${n}`, startedAt: `2026-11-06T0${n}:00:00Z` });
    state.runs = [finished(2), finished(1)];
    state.tagged = { marketMakers: 2, noise: 3, momentum: 0 };
    const account = (username: string) => ({
      accountId: username,
      username,
      kind: 'TRADER',
      cash: '1000.00',
      holdings: [],
      token: null,
      strategy: null,
    });
    let accounts = [account('alice')];
    server
      .on('GET /api/v1/instruments', (request) => ({
        status: 200,
        json: { instruments: request.url.endsWith(NEXT) ? [] : [NOVA], feed: { state: 'LIVE', lag: 0 } },
      }))
      .on('GET /api/v1/admin/accounts', () => ({ status: 200, json: { accounts, nextCursor: null } }))
      .on('GET /api/v1/admin/health', {
        status: 200,
        json: {
          writer: { available: true, queueDepth: 0, queueCapacity: 256 },
          lastMinute: { committed: 10, failed: 0, withinBudget: 10, refused: 0 },
          outbox: { admitting: true, pendingEvents: 0, oldestPendingMillis: 0 },
        },
      })
      .on('POST /api/v1/admin/market/close', () => {
        state.market = market('CLOSED');
        return { status: 200, json: { state: 'CLOSED', commandSequence: '8', canceledOrders: 3, endedRuns: 1 } };
      });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/operator?compare=20000000-0000-0000-0000-000000000002']}>
          <OperatorPage />
          <Address />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.click(await button('Close the market'));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Close the market for good' }));
    const ended = await screen.findByRole('region', { name: 'This market has ended' });
    expect(await within(ended).findByText('Every order that still rested was cancelled and its hold released.')).toBeInTheDocument();
    expect(await screen.findByText('The market is closed. 3 resting orders were cancelled, and 1 bot run ended.')).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Health' })).toBeInTheDocument();
    expect(await screen.findByRole('rowheader', { name: 'alice' })).toBeInTheDocument();
    expect(await screen.findByRole('rowheader', { name: 'NOVA' })).toBeInTheDocument();
    expect(await screen.findByText('5 bots')).toBeInTheDocument();
    expect(await screen.findByRole('checkbox', { name: /started 02:00:00/ })).toBeChecked();
    expect(screen.getByTestId('address')).toHaveTextContent('compare=');

    // Another operator, or this one, starts the next simulation: it has nothing yet.
    state.market = { ...market('SETUP'), simulationId: NEXT, commandSequence: '1' };
    state.runs = [];
    state.tagged = { marketMakers: 0, noise: 0, momentum: 0 };
    accounts = [];
    await client.invalidateQueries({ queryKey: ['market'] });

    expect(await screen.findByText('Setup', { selector: '.state-badge' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'This market has ended' })).toBeNull();
    // Neither the close's answer, kept for its own market, nor anything the closed market listed.
    expect(screen.queryByText(/^The market is closed\./)).toBeNull();
    expect(screen.queryByText(/cancelled and its hold released/)).toBeNull();
    expect(screen.queryByRole('region', { name: 'Health' })).toBeNull();
    expect(screen.queryByRole('rowheader', { name: 'NOVA' })).toBeNull();
    expect(screen.queryByRole('rowheader', { name: 'alice' })).toBeNull();
    expect(screen.queryByText('5 bots')).toBeNull();
    expect(screen.queryByRole('checkbox', { name: /started 0/ })).toBeNull();
    // Taken out of the address by an effect once the new market's page is drawn, so in the moment after it.
    await waitFor(() => expect(screen.getByTestId('address')).not.toHaveTextContent('compare='));
    // The new market's own, read for it: its setup forms, and its empty lists.
    expect(await screen.findByText('No instruments yet.')).toBeInTheDocument();
    expect(await screen.findByText('No accounts yet.')).toBeInTheDocument();
    expect(await screen.findByText('No bots yet')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Start run' })).toBeDisabled();
    expect(screen.queryByRole('table', { name: 'Recent runs' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Add instrument' })).toBeEnabled();
    expect(await button('Open market')).toHaveAccessibleDescription('Add at least one instrument first.');
  });

  it('drops what was typed in the simulation before, and keeps it through a close', async () => {
    const { state } = exchange('SETUP');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);
    const symbol = await screen.findByRole('textbox', { name: 'Symbol' });
    fireEvent.change(symbol, { target: { value: 'KEPT' } });

    // The same simulation, read again: what is being typed stays.
    state.market = { ...market('SETUP'), commandSequence: '9' };
    await client.invalidateQueries({ queryKey: ['market'] });
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Symbol' })).toHaveValue('KEPT'));

    state.market = { ...market('SETUP'), simulationId: NEXT, commandSequence: '1' };
    await client.invalidateQueries({ queryKey: ['market'] });
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Symbol' })).toHaveValue(''));
  });
});

describe('starting a new simulation on the operator screen', () => {
  beforeEach(() => resetSession());

  const NEXT = '10000000-0000-0000-0000-000000000002';
  const START = '/api/v1/admin/simulations';
  const starts = (server: ReturnType<typeof exchange>['server']) =>
    server.requests.filter((request) => request.method === 'POST' && request.url === START);
  const keys = (server: ReturnType<typeof exchange>['server']) =>
    starts(server).map((request) => request.headers['Idempotency-Key']);
  const panel = () => screen.findByRole('region', { name: 'Start a new simulation' });
  const nameField = () => screen.getByRole('textbox', { name: 'Name' });
  /** Types a name and presses the button. */
  const startAs = async (name: string) => {
    const form = await screen.findByRole('form', { name: /^Start (a new|the first) simulation$/ });
    fireEvent.change(within(form).getByRole('textbox', { name: 'Name' }), { target: { value: name } });
    fireEvent.click(within(form).getByRole('button', { name: 'Start simulation' }));
  };
  const started = (name: string) => ({
    status: 201,
    json: { simulationId: NEXT, name, state: 'SETUP', commandSequence: '1' },
  });
  const newMarket = (name: string | null): MarketStatus => ({
    ...market('SETUP'),
    simulationId: NEXT,
    name,
    commandSequence: '1',
  });

  it('offers a new simulation on a closed market, under the panel that says it has ended, and says what it means', async () => {
    exchange('CLOSED');
    renderPage();

    const start = await panel();
    expect(start).toHaveTextContent(
      'A fresh market, in setup, with its own instruments, accounts and bots to set up again: nothing is copied from this one. Trader logins stay.',
    );
    expect(start).toHaveTextContent('This market and its history are kept, but these screens then show the new one.');
    expect(within(start).getByRole('textbox', { name: 'Name' })).toHaveAccessibleDescription(
      "1 to 80 characters, different from every earlier simulation's name. Traders see it at the top of their screens.",
    );
    expect(within(start).getByRole('button', { name: 'What is new simulation?' })).toBeInTheDocument();
    expect(within(start).getByRole('button', { name: 'Start simulation' })).toBeEnabled();

    // Nowhere else: not while the market is still being set up, open or halted.
    for (const state of ['SETUP', 'OPEN', 'HALTED'] as const) {
      cleanup();
      exchange(state);
      renderPage();
      await screen.findByRole('region', { name: 'Market' });
      expect(screen.queryByRole('form', { name: /^Start/ })).toBeNull();
      expect(screen.queryByRole('region', { name: /^Start/ })).toBeNull();
    }
  });

  it("starts under a key with the name as kept, and is the new simulation's setup page at once", async () => {
    const { server, state } = exchange('CLOSED');
    let statusReads = 0;
    server
      .on('GET /api/v1/market', () => {
        statusReads += 1;
        // Once the start has answered, the status is not read back in this test: the page has it from the answer.
        return state.market.state === 'SETUP' ? new Promise<never>(() => {}) : { status: 200, json: state.market };
      })
      .on(`POST ${START}`, () => {
        state.market = newMarket('Thursday seminar');
        return started('Thursday seminar');
      });
    renderPage();

    await startAs('  Thursday seminar  ');

    expect(await screen.findByText('Setup', { selector: '.state-badge' })).toBeInTheDocument();
    const notice = screen.getByRole('region', { name: 'A new simulation' });
    expect(notice).toHaveTextContent(
      'Thursday seminar has started, in setup. Nothing was copied from the market before: add its instruments, fund the traders again and create its bots. Trader logins are kept.',
    );
    const control = screen.getByRole('region', { name: 'Market' });
    expect(within(control).getByText('Name', { selector: 'dt' }).nextElementSibling).toHaveTextContent('Thursday seminar');
    expect(within(control).getByText('Last command', { selector: 'dt' }).nextElementSibling).toHaveTextContent('1');
    expect(screen.queryByRole('region', { name: 'This market has ended' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Start a new simulation' })).toBeNull();
    expect(screen.getByRole('form', { name: 'Add an instrument' })).toBeInTheDocument();
    expect(statusReads).toBeGreaterThan(1);

    expect(starts(server)).toHaveLength(1);
    expect(starts(server)[0]?.body).toEqual({ name: 'Thursday seminar' });
    expect(keys(server)[0]).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('says a refusal for a market that has not closed in its own words, on the page of that market', async () => {
    const { server, state } = exchange('CLOSED');
    server.on(`POST ${START}`, () => {
      // Another operator was quicker.
      state.market = newMarket('Their seminar');
      return {
        status: 409,
        problem: { status: 409, code: 'SIMULATION_NOT_CLOSED', state: 'SETUP', commandSequence: '1', detail: 'Not closed' },
      };
    });
    renderPage();

    await startAs('Our seminar');

    const notice = await screen.findByRole('region', { name: 'A new simulation' });
    expect(within(notice).getByRole('alert')).toHaveTextContent(
      'Nothing was started: a simulation is already being set up. This page now shows it; set that one up instead.',
    );
    const control = screen.getByRole('region', { name: 'Market' });
    await waitFor(() =>
      expect(within(control).getByText('Name', { selector: 'dt' }).nextElementSibling).toHaveTextContent('Their seminar'),
    );
    expect(screen.queryByText(/Our seminar/)).toBeNull();
  });

  it('says a refusal for an open or halted market that has not closed on that market\'s page too', async () => {
    for (const other of ['OPEN', 'HALTED'] as const) {
      cleanup();
      const { server, state } = exchange('CLOSED');
      server.on(`POST ${START}`, () => {
        // This page was out of date: another operator had started a simulation, and opened it.
        state.market = { ...newMarket('Their seminar'), state: other, openedAt: '2026-11-06T09:00:00Z', commandSequence: '5' };
        return {
          status: 409,
          problem: { status: 409, code: 'SIMULATION_NOT_CLOSED', state: other, commandSequence: '5', detail: 'Not closed' },
        };
      });
      renderPage();

      await startAs('Our seminar');

      const notice = await screen.findByRole('region', { name: 'A new simulation' });
      expect(within(notice).getByRole('alert')).toHaveTextContent(
        `Nothing was started: a simulation that has not closed is ${other === 'OPEN' ? 'open' : 'halted'}. This page now shows it; close it before starting another.`,
      );
      expect(await screen.findByText(other === 'OPEN' ? 'Open' : 'Halted', { selector: '.state-badge' })).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'Start a new simulation' })).toBeNull();
    }
  });

  it('lets go of an unanswered start once the page has shown another simulation, and sends the name typed then', async () => {
    const { server, state } = exchange('CLOSED');
    const THIRD = '10000000-0000-0000-0000-000000000003';
    server.on(`POST ${START}`, (request) => {
      if ((request.body as { name: string }).name === 'Friday') {
        // Friday commits, but its answer is lost.
        state.market = newMarket('Friday');
        return { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN' } };
      }
      state.market = { ...market('SETUP'), simulationId: THIRD, name: 'Saturday', commandSequence: '1' };
      return { status: 201, json: { simulationId: THIRD, name: 'Saturday', state: 'SETUP', commandSequence: '1' } };
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);
    await startAs('Friday');

    // The page becomes Friday's. Its operator never asks again: they set it up, open it and close it, on this page.
    const notice = await screen.findByRole('region', { name: 'A new simulation' });
    await within(notice).findByRole('button', { name: 'Ask again' });
    state.market = { ...newMarket('Friday'), state: 'OPEN', openedAt: '2026-11-06T09:00:00Z', commandSequence: '5' };
    await client.invalidateQueries({ queryKey: ['market'] });
    expect(await screen.findByText('Open', { selector: '.state-badge' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'A new simulation' })).toHaveTextContent('has not given its answer');
    state.market = {
      ...newMarket('Friday'),
      state: 'CLOSED',
      openedAt: '2026-11-06T09:00:00Z',
      closedAt: '2026-11-06T16:00:00Z',
      commandSequence: '9',
    };
    await client.invalidateQueries({ queryKey: ['market'] });

    // Friday's own ended page offers a start of its own, which is not the one sent from the market before.
    const start = await panel();
    expect(nameField()).toBeEnabled();
    expect(within(start).queryByRole('alert')).toBeNull();
    await startAs('Saturday');

    expect(await screen.findByRole('region', { name: 'A new simulation' })).toHaveTextContent('Saturday has started, in setup.');
    expect(starts(server).map((request) => request.body)).toEqual([{ name: 'Friday' }, { name: 'Saturday' }]);
    expect(new Set(keys(server)).size).toBe(2);
  });

  it('leaves the page of a simulation that has moved on as it is when an earlier start is answered again', async () => {
    const { server, state } = exchange('CLOSED');
    let answered = false;
    server.on(`POST ${START}`, () => {
      state.market = newMarket('Friday');
      return answered ? started('Friday') : { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN' } };
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);
    await startAs('Friday');
    await within(await screen.findByRole('region', { name: 'A new simulation' })).findByRole('button', { name: 'Ask again' });

    // Friday started, and has been opened since; the page shows it, with the start still unanswered.
    state.market = { ...newMarket('Friday'), state: 'OPEN', openedAt: '2026-11-06T09:00:00Z', commandSequence: '5' };
    await client.invalidateQueries({ queryKey: ['market'] });
    const notice = await screen.findByRole('region', { name: 'A new simulation' });
    expect(await screen.findByText('Open', { selector: '.state-badge' })).toBeInTheDocument();
    // From here on, the page has only what the start's answer would give it.
    server.on('GET /api/v1/market', () => new Promise<never>(() => {}));

    answered = true;
    fireEvent.click(within(notice).getByRole('button', { name: 'Ask again' }));

    expect(await within(notice).findByText('The start sent from here started this simulation, Friday.')).toBeInTheDocument();
    expect(screen.getByText('Open', { selector: '.state-badge' })).toBeInTheDocument();
    expect(screen.queryByText('Setup', { selector: '.state-badge' })).toBeNull();
    expect(new Set(keys(server)).size).toBe(1);
  });

  it('takes focus to what became of the start, as the button that sent it is disabled or gone', async () => {
    const { server, state } = exchange('CLOSED');
    let answered = false;
    server.on(`POST ${START}`, () => {
      if (!answered) {
        return { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN' } };
      }
      state.market = newMarket('Monday');
      return started('Monday');
    });
    renderPage();

    await startAs('Monday');
    const start = await panel();
    await waitFor(() => expect(within(start).getByRole('button', { name: 'Ask again' })).toHaveFocus());

    answered = true;
    fireEvent.click(within(start).getByRole('button', { name: 'Ask again' }));
    const heading = await screen.findByRole('heading', { name: 'A new simulation' });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(heading).toHaveAccessibleDescription(/^Monday has started, in setup\. Nothing was copied/);
  });

  it('marks the name a refusal is about, keeps it, and starts again under a new key', async () => {
    const { server, state } = exchange('CLOSED');
    let answer: { status: number; problem?: unknown; json?: unknown } = {
      status: 409,
      problem: { status: 409, code: 'SIMULATION_NAME_TAKEN', commandSequence: '9', detail: 'Name taken' },
    };
    server.on(`POST ${START}`, () => answer);
    renderPage();

    await startAs('Monday');
    expect(await within(await panel()).findByRole('alert')).toHaveTextContent(
      'Nothing was started: another simulation already has this name, whatever its case. Choose another name.',
    );
    expect(nameField()).toHaveAttribute('aria-invalid', 'true');
    expect(nameField()).toHaveFocus();
    expect(nameField()).toHaveValue('Monday');

    // The exchange checks the name, as for every form here: its own words for the field.
    answer = {
      status: 400,
      problem: {
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'The simulation request is invalid',
        errors: [{ field: 'name', code: 'INVALID_NAME', message: 'name must be 1 to 80 characters' }],
      },
    };
    await startAs(' ');
    await waitFor(async () =>
      expect(within(await panel()).getByRole('alert')).toHaveTextContent('name must be 1 to 80 characters'),
    );
    expect(nameField()).toHaveAttribute('aria-invalid', 'true');
    expect(starts(server)[1]?.body).toEqual({ name: '' });

    server.on(`POST ${START}`, () => {
      state.market = newMarket('Tuesday');
      return started('Tuesday');
    });
    await startAs('Tuesday');
    expect(await screen.findByRole('region', { name: 'A new simulation' })).toHaveTextContent('Tuesday has started');
    // A refused key is spent: each attempt had its own.
    expect(new Set(keys(server)).size).toBe(3);
  });

  it('asks again under the same key and name when the answer did not come, and only then', async () => {
    const { server, state } = exchange('CLOSED');
    const answers = [
      { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'Unknown' } },
      { status: 503, problem: { status: 503, code: 'TRADING_UNAVAILABLE', detail: 'Unavailable' } },
      { status: 503, problem: { status: 503, code: 'EXCHANGE_BUSY', detail: 'Busy' } },
    ];
    server.on(`POST ${START}`, () => {
      const next = answers.shift();
      if (next !== undefined) {
        return next;
      }
      state.market = newMarket('Wednesday');
      return started('Wednesday');
    });
    renderPage();

    await startAs('Wednesday');
    const start = await panel();
    expect(await within(start).findByRole('alert')).toHaveTextContent(
      'The start did not answer in time, so whether the simulation started is not known yet. Asking again sends the same key and name, so it can never start twice.',
    );
    // Nothing else can be sent meanwhile: the name stays as it was sent.
    expect(nameField()).toBeDisabled();
    expect(within(start).getByRole('button', { name: 'Start simulation' })).toBeDisabled();

    fireEvent.click(within(start).getByRole('button', { name: 'Ask again' }));
    expect(await within(start).findByText(/^Trading is unavailable: the exchange has stopped taking commands/)).toBeInTheDocument();
    fireEvent.click(within(start).getByRole('button', { name: 'Ask again' }));
    expect(await within(start).findByText(/^Not confirmed: the exchange was busy/)).toBeInTheDocument();
    fireEvent.click(within(start).getByRole('button', { name: 'Retry' }));

    expect(await screen.findByRole('region', { name: 'A new simulation' })).toHaveTextContent('Wednesday has started');
    expect(new Set(keys(server)).size).toBe(1);
    expect(starts(server).map((request) => request.body)).toEqual(Array(4).fill({ name: 'Wednesday' }));
  });

  it('says on the new page when a simulation started while the start sent from here had no answer', async () => {
    const { server, state } = exchange('CLOSED');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    let answered = false;
    server.on(`POST ${START}`, () => {
      state.market = newMarket('Friday');
      return answered ? started('Friday') : { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN' } };
    });
    renderWith(client);

    await startAs('Friday');
    // The start committed though its answer was lost: the next read of the status shows the new simulation.
    await client.invalidateQueries({ queryKey: ['market'] });
    const notice = await screen.findByRole('region', { name: 'A new simulation' });
    expect(within(notice).getByRole('alert')).toHaveTextContent(
      "A new simulation has started, but the start sent from here has not given its answer, so whether it is this one is not known. Asking again sends the same key and name: it returns that start's answer and never starts another.",
    );
    answered = true;
    fireEvent.click(within(notice).getByRole('button', { name: 'Ask again' }));
    expect(await within(notice).findByText(/^Friday has started, in setup\./)).toBeInTheDocument();
    expect(new Set(keys(server)).size).toBe(1);
  });

  it('keeps an unanswered start when the page is left, and asks again under the same key', async () => {
    const { server } = exchange('CLOSED');
    server.on(`POST ${START}`, { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN' } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const first = renderWith(client);
    await startAs('Saturday');
    await within(await panel()).findByRole('button', { name: 'Ask again' });
    first.unmount();

    renderWith(client);
    expect(await within(await panel()).findByText(/did not answer in time/)).toBeInTheDocument();
    expect(nameField()).toBeDisabled();
    fireEvent.click(within(await panel()).getByRole('button', { name: 'Ask again' }));
    await waitFor(() => expect(keys(server)).toHaveLength(2));
    expect(new Set(keys(server)).size).toBe(1);
  });

  it('offers the first simulation by name on a stack with no market, beside adding an instrument, and keeps the page', async () => {
    const { server, state } = exchange('NOT_SET_UP', []);
    server.on(`POST ${START}`, () => {
      state.market = newMarket('Opening day');
      return started('Opening day');
    });
    renderPage();

    const first = await screen.findByRole('region', { name: 'Start the first simulation' });
    expect(first).toHaveTextContent('A market by name, in setup. Or add an instrument below: that creates a market too, with no name.');
    expect(await button('Open market')).toHaveAccessibleDescription(
      'Start a simulation or add an instrument first; either creates the market.',
    );
    // Typed into the instrument form before the market is named: the page that names it goes on with it.
    fireEvent.change(screen.getByRole('textbox', { name: 'Symbol' }), { target: { value: 'NOVA' } });

    await startAs('Opening day');

    expect(await screen.findByRole('region', { name: 'A new simulation' })).toHaveTextContent('Opening day has started, in setup.');
    expect(screen.queryByRole('region', { name: 'Start the first simulation' })).toBeNull();
    expect(screen.getByRole('textbox', { name: 'Symbol' })).toHaveValue('NOVA');
    expect(starts(server)[0]?.body).toEqual({ name: 'Opening day' });
  });

  it('says nothing of a start on the page of a later market', async () => {
    const { server, state } = exchange('CLOSED');
    server.on(`POST ${START}`, () => {
      state.market = newMarket('Sunday');
      return started('Sunday');
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    renderWith(client);
    await startAs('Sunday');
    await screen.findByRole('region', { name: 'A new simulation' });

    // Sunday opens and closes: its own ended page, with the form empty and no word of the start that made it.
    state.market = { ...newMarket('Sunday'), state: 'CLOSED', closedAt: '2026-11-08T16:00:00Z', openedAt: '2026-11-08T09:00:00Z' };
    await client.invalidateQueries({ queryKey: ['market'] });
    const start = await panel();
    expect(screen.queryByRole('region', { name: 'A new simulation' })).toBeNull();
    expect(within(start).queryByRole('status')).toBeNull();
    expect(nameField()).toHaveValue('');
  });
});

describe('a market opened for a set time, on the operator screen', () => {
  beforeEach(() => resetSession());

  const OPEN = '/api/v1/admin/market/open';
  const CLOSES_AT = '2026-11-05T11:00:00Z';
  const marketPanel = () => screen.findByRole('region', { name: 'Market' });
  const opens = (server: ReturnType<typeof exchange>['server']) =>
    server.requests.filter((request) => request.method === 'POST' && request.url === OPEN);
  /** The market after an opening: open, and when it was given a duration, with the time the exchange closes it. */
  const opened = (closesAt: string | null): MarketStatus => ({ ...market('OPEN'), closesAt });
  const confirmClose = async () => {
    fireEvent.click(await button('Close the market'));
    const dialog = screen.getByRole('alertdialog', { name: 'Close the market for good?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close the market for good' }));
  };

  it('opens until it is closed unless a set time is chosen, and then sends no body', async () => {
    const { server, state } = exchange('SETUP');
    server.on(`POST ${OPEN}`, () => {
      state.market = opened(null);
      return { status: 200, json: { state: 'OPEN', commandSequence: '8', closesAt: null } };
    });
    renderPage();

    const length = await screen.findByRole('group', { name: 'Market length' });
    expect(within(length).getByRole('radio', { name: 'Until I close it' })).toBeChecked();
    expect(within(length).getByRole('textbox', { name: 'Hours' })).toBeDisabled();
    await waitFor(async () => expect(await button('Open market')).toBeEnabled());
    fireEvent.click(await button('Open market'));

    expect(await screen.findByText('The market is open.')).toBeInTheDocument();
    expect(opens(server)).toHaveLength(1);
    expect(opens(server)[0]?.body).toBeUndefined();
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Market length' })).toBeNull());
    expect(within(await marketPanel()).queryByText('Closes')).toBeNull();
  });

  it('opens for a set time in hours and minutes, and says when the exchange closes it', async () => {
    const { server, state } = exchange('SETUP');
    server.on(`POST ${OPEN}`, () => {
      state.market = opened(CLOSES_AT);
      return { status: 200, json: { state: 'OPEN', commandSequence: '8', closesAt: CLOSES_AT } };
    });
    renderPage();

    const length = await screen.findByRole('group', { name: 'Market length' });
    fireEvent.click(within(length).getByRole('radio', { name: 'For a set time' }));
    fireEvent.change(within(length).getByRole('textbox', { name: 'Hours' }), { target: { value: '1' } });
    fireEvent.change(within(length).getByRole('textbox', { name: 'Minutes' }), { target: { value: '30' } });
    await waitFor(async () => expect(await button('Open market')).toBeEnabled());
    fireEvent.click(await button('Open market'));

    expect(
      await screen.findByText('The market is open. The exchange closes it by itself at 5 Nov 2026, 11:00:00 UTC.'),
    ).toBeInTheDocument();
    expect(opens(server).map((request) => request.body)).toEqual([{ durationMinutes: 90 }]);
    const panel = await marketPanel();
    await waitFor(() => expect(within(panel).getByText('Closes')).toBeInTheDocument());
    expect(within(panel).getByText('5 Nov 2026, 11:00:00 UTC')).toBeInTheDocument();
  });

  it('says why a set time cannot be sent, and does not send it', async () => {
    const { server } = exchange('SETUP');
    renderPage();

    const length = await screen.findByRole('group', { name: 'Market length' });
    fireEvent.click(within(length).getByRole('radio', { name: 'For a set time' }));
    const hours = within(length).getByRole('textbox', { name: 'Hours' });
    const minutes = within(length).getByRole('textbox', { name: 'Minutes' });
    const cases: [string, string, string][] = [
      ['0', '4', 'A set time is at least 5 minutes.'],
      ['721', '0', 'A set time is at most 30 days (720 hours).'],
      ['0', '75', 'Minutes go up to 59; give a longer time in hours.'],
      ['one', '0', 'Give the set time in whole hours and minutes.'],
    ];
    for (const [h, m, problem] of cases) {
      fireEvent.change(hours, { target: { value: h } });
      fireEvent.change(minutes, { target: { value: m } });
      expect(await within(length).findByRole('alert')).toHaveTextContent(problem);
      expect(hours).toHaveAttribute('aria-invalid', 'true');
      expect(await button('Open market')).toBeDisabled();
    }
    // The limits themselves are taken, as the exchange takes them.
    for (const [h, m] of [
      ['0', '5'],
      ['720', '0'],
    ] as const) {
      fireEvent.change(hours, { target: { value: h } });
      fireEvent.change(minutes, { target: { value: m } });
      await waitFor(() => expect(within(length).queryByRole('alert')).toBeNull());
    }
    fireEvent.click(within(length).getByRole('radio', { name: 'Until I close it' }));
    expect(within(length).queryByRole('alert')).toBeNull();
    expect(opens(server)).toHaveLength(0);
  });

  it("counts down to the close time on the exchange's clock, and says when the time has come", async () => {
    const { server, state } = exchange('OPEN');
    state.market = opened(CLOSES_AT);
    // The exchange's clock, which this browser's is not: its answers carry it, ninety seconds before the close.
    let clock = '2026-11-05T10:58:30Z';
    server.on('GET /api/v1/market', () => ({
      status: 200,
      json: state.market,
      headers: { Date: new Date(clock).toUTCString() },
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWith(client);

    const panel = await marketPanel();
    expect(
      await within(panel).findByText(
        /^The market closes at its set time, 5 Nov 2026, 11:00:00 UTC, in 1 min (30|29) s\.$/,
      ),
    ).toBeInTheDocument();
    clock = '2026-11-05T11:00:00Z';
    await client.invalidateQueries({ queryKey: ['market'] });
    expect(
      await within(panel).findByText(
        'Its set time, 5 Nov 2026, 11:00:00 UTC, has come: the exchange is closing the market, and new orders are refused.',
        {},
        { timeout: 2_000 },
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('Open', { selector: '.state-badge' })).toBeInTheDocument();
  });

  it('says a market closed at its set time, not by anyone here', async () => {
    const { state } = exchange('CLOSED');
    state.market = { ...market('CLOSED'), closesAt: '2026-11-05T16:00:00Z', closedOnSchedule: true };
    renderPage();

    const ended = await screen.findByRole('region', { name: 'This market has ended' });
    expect(ended).toHaveTextContent('It closed at its set time on 5 Nov 2026, 16:00:00 UTC. Trading is over for good');
    const panel = await marketPanel();
    expect(within(panel).getByText(/· at its set time/)).toBeInTheDocument();
    expect(panel).toHaveTextContent('The market closed at its set time. Closing is final');
  });

  it('does not claim a close sent here closed a market that closed at its set time first', async () => {
    const { server, state } = exchange('OPEN');
    state.market = opened(CLOSES_AT);
    const scheduled = { ...market('CLOSED'), closesAt: CLOSES_AT, closedAt: CLOSES_AT, closedOnSchedule: true };
    server.on('POST /api/v1/admin/market/close', () => {
      state.market = scheduled;
      return {
        status: 409,
        problem: {
          status: 409,
          code: 'MARKET_TRANSITION_NOT_ALLOWED',
          detail: 'closed',
          state: 'CLOSED',
          commandSequence: '12',
        },
      };
    });
    renderPage();

    await confirmClose();
    expect(
      await screen.findByText('The market had already closed at its set time, so this close changed nothing.'),
    ).toBeInTheDocument();
  });

  it('says a close with no answer changed nothing once the status shows the set time closed the market', async () => {
    const { server, state } = exchange('OPEN');
    state.market = opened(CLOSES_AT);
    server.on('POST /api/v1/admin/market/close', () => {
      state.market = { ...market('CLOSED'), closesAt: CLOSES_AT, closedAt: CLOSES_AT, closedOnSchedule: true };
      return { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'later' } };
    });
    renderPage();

    await confirmClose();
    expect(
      await screen.findByText('The market closed at its set time, so this close changed nothing.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/the close has not given its answer here/)).toBeNull();
    expect(screen.queryByRole('button', { name: "Ask for the close's answer" })).toBeNull();
    // Nothing will ever answer that close, and the orders are the exchange's own close's to cancel.
    const ended = await screen.findByRole('region', { name: 'This market has ended' });
    expect(ended).toHaveTextContent('A close cancels every order that still rested and releases its hold');
    expect(ended).not.toHaveTextContent('The close has not answered yet');
  });

  it('names both times when the exchange closed the market well after its set time', async () => {
    const { state } = exchange('CLOSED');
    // Down from before its set time until three hours after it: closed at the start.
    state.market = { ...market('CLOSED'), closesAt: '2026-11-05T13:00:00Z', closedOnSchedule: true };
    renderPage();

    const ended = await screen.findByRole('region', { name: 'This market has ended' });
    expect(ended).toHaveTextContent(
      'It closed on 5 Nov 2026, 16:00:00 UTC (its set time, 5 Nov 2026, 13:00:00 UTC, had passed). Trading is over for good',
    );
    expect(ended).not.toHaveTextContent('at its set time');
    const panel = await marketPanel();
    expect(within(panel).getByText('Set time')).toBeInTheDocument();
    expect(within(panel).getByText('5 Nov 2026, 13:00:00 UTC')).toBeInTheDocument();
    expect(within(panel).getByText(/· after its set time had passed/)).toBeInTheDocument();
    expect(panel).toHaveTextContent('The market closed after its set time had passed. Closing is final');
    expect(panel).not.toHaveTextContent('at its set time');
  });

  it('keeps calling a close a minute after its set time a close at that time', async () => {
    const { state } = exchange('CLOSED');
    state.market = { ...market('CLOSED'), closesAt: '2026-11-05T15:59:00Z', closedOnSchedule: true };
    renderPage();

    const ended = await screen.findByRole('region', { name: 'This market has ended' });
    expect(ended).toHaveTextContent('It closed at its set time on 5 Nov 2026, 16:00:00 UTC.');
    expect(within(await marketPanel()).getByText(/· at its set time/)).toBeInTheDocument();
  });
});

describe('operator commands across leaving the page', () => {
  beforeEach(() => resetSession());

  const HALT = '/api/v1/admin/market/halt';
  const RUNS = '/api/v1/admin/bot-runs';
  const newClient = () =>
    new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const sent = (server: ReturnType<typeof exchange>['server'], url: string) =>
    server.requests.filter((request) => request.method === 'POST' && request.url === url);
  const starting = () =>
    botRun({ status: 'STARTING', worker: { workerId: null, heartbeatAt: null, leaseExpiresAt: null } });
  const unknown = { status: 504, problem: { status: 504, code: 'COMMAND_OUTCOME_UNKNOWN', detail: 'It may complete' } };
  /** An answer the test gives when it chooses to: the request stays on its way until then. */
  const answerLater = () => {
    type Reply = { status: number; json?: unknown; problem?: unknown };
    let answer: (reply: Reply) => void = () => {};
    const reply = new Promise<Reply>((resolve) => {
      answer = resolve;
    });
    return { reply, answer: (given: Reply) => answer(given) };
  };
  /** Fills the start form from the bots there are and sends it. */
  const startRun = async () => {
    const form = await screen.findByRole('form', { name: 'Start a bot run' });
    await waitFor(() => expect(within(form).getByLabelText('Market makers')).toHaveValue('1'));
    fireEvent.click(within(form).getByRole('button', { name: 'Start run' }));
    return form;
  };

  it('keeps a halt on its way when the page is left, and shows its answer on coming back', async () => {
    const { server, state } = exchange('OPEN');
    const later = answerLater();
    server.on(`POST ${HALT}`, () => later.reply);
    const client = newClient();
    const first = renderWith(client);

    fireEvent.click(await button('Halt trading'));
    await waitFor(() => expect(sent(server, HALT)).toHaveLength(1));
    expect(screen.getByText(/^Halting trading…/)).toHaveTextContent(/^Halting trading… \d+ s$/);
    first.unmount();
    // The operator looks at another page for a while: nothing on screen holds the command meanwhile.
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));

    renderWith(client);
    // Still on its way, named and counted, with nothing else on offer meanwhile.
    expect(await screen.findByText(/^Halting trading…/)).toHaveAttribute('role', 'status');
    for (const name of ['Halt trading', 'Resume trading', 'Close the market']) {
      expect(await button(name)).toBeDisabled();
    }
    state.market = market('HALTED');
    later.answer({ status: 200, json: { state: 'HALTED', commandSequence: '9' } });

    expect(await screen.findByText('Trading is halted. Cancels still work.')).toBeInTheDocument();
    expect(sent(server, HALT)).toHaveLength(1);
    await waitFor(async () => expect(await button('Resume trading')).toBeEnabled());
  });

  it('names a start on its way and counts its seconds beside the button that sent it', async () => {
    const { server, state } = exchange('OPEN');
    state.tagged = { marketMakers: 1, noise: 2, momentum: 0 };
    const later = answerLater();
    server.on(`POST ${RUNS}`, () => later.reply);
    renderPage();

    const form = await startRun();

    const status = await within(form).findByText(/^Starting the run…/);
    expect(status).toHaveTextContent(/^Starting the run… \d+ s$/);
    const start = within(form).getByRole('button', { name: 'Start run' });
    expect(start).toBeDisabled();
    expect(status.parentElement).toBe(start.parentElement);
    state.runs = [starting()];
    later.answer({ status: 201, json: { runId: botRun().runId, bots: 3, commandSequence: '9' } });
    // The form gives way to the run, and the start's answer stays in sight above it.
    const panel = await screen.findByRole('region', { name: 'Bot runs' });
    expect(await within(panel).findByText('Started a run of 3 bots.')).toBeInTheDocument();
    expect(within(panel).getByRole('group', { name: 'Current run' })).toBeInTheDocument();
  });

  it('lets go of a start once its run has ended, so the form comes back as new', async () => {
    const { server, state } = exchange('OPEN');
    state.tagged = { marketMakers: 1, noise: 2, momentum: 0 };
    server.on(`POST ${RUNS}`, () => {
      state.runs = [starting()];
      return { status: 201, json: { runId: botRun().runId, bots: 3, commandSequence: '9' } };
    });
    renderPage();

    await startRun();
    const panel = await screen.findByRole('region', { name: 'Bot runs' });
    expect(await within(panel).findByText('Started a run of 3 bots.')).toBeInTheDocument();
    state.runs = [finishedRun()];

    const form = await within(panel).findByRole('form', { name: 'Start a bot run' }, { timeout: 4_000 });
    await waitFor(() => expect(within(panel).queryByText('Started a run of 3 bots.')).toBeNull());
    expect(within(form).getByRole('button', { name: 'Start run' })).toBeEnabled();
  });

  it('offers nothing from what was cached before the page opened until the exchange has been read', async () => {
    const { server, state } = exchange('HALTED');
    state.runs = [starting()];
    const client = newClient();
    // As the cache held them when the page was left: the market open, and no run.
    const before = Date.now() - 60_000;
    client.setQueryData(['market'], market('OPEN'), { updatedAt: before });
    client.setQueryData(['bot-runs'], { runs: [], nextCursor: null, answeredAt: before }, { updatedAt: before });
    const marketRead = answerLater();
    const runsRead = answerLater();
    server.on('GET /api/v1/market', () => marketRead.reply).on(`GET ${RUNS}`, () => runsRead.reply);
    renderWith(client);

    const panel = await screen.findByRole('region', { name: 'Bot runs' });
    expect(within(panel).getByText('Checking the exchange…')).toHaveAttribute('role', 'status');
    expect(within(panel).queryByRole('button', { name: 'Start run' })).toBeNull();
    expect(await button('Halt trading')).toBeDisabled();
    expect(await button('Halt trading')).toHaveAccessibleDescription('Checking the exchange…');

    marketRead.answer({ status: 200, json: market('HALTED') });
    runsRead.answer({ status: 200, json: { runs: state.runs, nextCursor: null } });

    expect(await within(panel).findByRole('group', { name: 'Current run' })).toBeInTheDocument();
    expect(within(panel).queryByText('Checking the exchange…')).toBeNull();
    await waitFor(async () => expect(await button('Resume trading')).toBeEnabled());
    expect(await button('Halt trading')).toBeDisabled();
  });

  it('says a start with no answer went through once the run is read as going', async () => {
    const { server, state } = exchange('OPEN');
    state.tagged = { marketMakers: 1, noise: 2, momentum: 0 };
    server.on(`POST ${RUNS}`, () => {
      state.runs = [starting()];
      return unknown;
    });
    renderPage();

    await startRun();

    const panel = await screen.findByRole('region', { name: 'Bot runs' });
    const said = 'Your earlier start already went through: the run is going.';
    expect(await within(panel).findByText(said)).toBeInTheDocument();
    expect(within(panel).getByRole('group', { name: 'Current run' })).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(sent(server, RUNS)).toHaveLength(1);
  });

  it('says a start refused because a run is going is an earlier start that went through', async () => {
    const { server, state } = exchange('OPEN');
    state.tagged = { marketMakers: 1, noise: 2, momentum: 0 };
    server.on(`POST ${RUNS}`, () => {
      state.runs = [starting()];
      return { status: 409, problem: { status: 409, code: 'RUN_ACTIVE', detail: 'A bot run is already running' } };
    });
    renderPage();

    await startRun();

    const panel = await screen.findByRole('region', { name: 'Bot runs' });
    const said = 'A run is already going: an earlier start already went through, so nothing new was started.';
    expect(await within(panel).findByText(said)).toHaveAttribute('role', 'alert');
    expect(within(panel).getByRole('group', { name: 'Current run' })).toBeInTheDocument();
  });

  it('says a halt with no answer went through once the market reads halted, and offers Resume', async () => {
    const { server, state } = exchange('OPEN');
    server.on(`POST ${HALT}`, () => {
      state.market = market('HALTED');
      return unknown;
    });
    renderPage();

    fireEvent.click(await button('Halt trading'));

    expect(await screen.findByText('Your earlier halt already went through: trading is halted.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    await waitFor(async () => expect(await button('Resume trading')).toBeEnabled());
  });

  it('says a halt refused because trading is halted already is an earlier halt that went through', async () => {
    const { server, state } = exchange('OPEN');
    server.on(`POST ${HALT}`, () => {
      state.market = market('HALTED');
      return {
        status: 409,
        problem: {
          status: 409,
          code: 'MARKET_TRANSITION_NOT_ALLOWED',
          state: 'HALTED',
          detail: 'The market is HALTED; it cannot halt from there',
        },
      };
    });
    renderPage();

    fireEvent.click(await button('Halt trading'));

    const said = 'Trading is already halted: an earlier halt already went through, so this one changed nothing.';
    expect(await screen.findByText(said)).toHaveAttribute('role', 'alert');
  });
});
