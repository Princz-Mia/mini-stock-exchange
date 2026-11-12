/**
 * The first-login tour, one per role. Raise TOUR_VERSION when the steps change enough to show again: the server keeps
 * the newest version each user has seen, and the tour shows while that is lower.
 */
export const TOUR_VERSION = 1;

export type TourRole = 'TRADER' | 'OPERATOR';

/** One pointer: the part of the page it highlights, found by a selector, and what it says about it. */
export interface TourStep {
  anchor: string;
  title: string;
  body: string;
  /** Said instead of highlighting when the part is not on the page yet. */
  absent?: string;
}

export interface TourScript {
  welcome: { title: string; body: string };
  steps: readonly TourStep[];
}

const LATER = 'It appears here once there is an instrument to trade.';

export const TOURS: Record<TourRole, TourScript> = {
  TRADER: {
    welcome: {
      title: 'Welcome to Mini Stock Exchange',
      body:
        'A simulated market with virtual funds: nothing here is real money. This short tour shows where things are. ' +
        'Skip it if you like; Help in the top bar has it again.',
    },
    steps: [
      {
        anchor: 'section[aria-labelledby="watchlist-title"]',
        title: 'Watchlist',
        body: 'Every instrument in this simulation, with its last price. Choose one to see its chart, book and ticket.',
      },
      {
        anchor: 'section[aria-labelledby="chart-title"]',
        title: 'Price chart',
        body:
          'Bars from the analytics, topped up with live trades. Switch between candles, a line and an area, and ' +
          'choose the range and bar size.',
        absent: LATER,
      },
      {
        anchor: 'section[aria-labelledby="book-title"]',
        title: 'Order book',
        body: 'Orders waiting to trade, best prices first: bids to buy on one side, asks to sell on the other.',
        absent: LATER,
      },
      {
        anchor: 'section[aria-labelledby="ticket-title"]',
        title: 'Order ticket',
        body:
          'Buy or sell. A limit order waits in the book at your price or better; a protected market order fills now, ' +
          'within a price you set, and never waits. The ticket shows what an order reserves before you send it.',
        absent: LATER,
      },
      {
        anchor: '.terminal-orders .panel',
        title: 'Your orders',
        body: 'Your open orders, which you can cancel, and your recent ones with how each ended.',
      },
      {
        anchor: 'nav a[href="/portfolio"]',
        title: 'Portfolio',
        body: 'Your cash and shares, what is reserved for open orders, and your full history of orders and fills.',
      },
    ],
  },
  OPERATOR: {
    welcome: {
      title: 'Welcome to Mini Stock Exchange',
      body:
        'You run this simulated market: set it up, open it, run the bots and watch how trading keeps up. This short ' +
        'tour shows where things are. Skip it if you like; Help in the top bar has it again.',
    },
    steps: [
      {
        anchor: 'section[aria-labelledby="market-title"]',
        title: 'Market state',
        body:
          'Open, halt and resume the market. Instruments, accounts and bots are set up before it opens, and it opens ' +
          'once.',
      },
      {
        anchor: 'section[aria-labelledby="instruments-title"]',
        title: 'Instruments',
        body: 'The symbols to trade and their reference prices, fixed once the market opens.',
      },
      {
        anchor: 'section[aria-labelledby="accounts-title"]',
        title: 'Accounts and endowments',
        body: 'Each trader and bot with its starting cash and shares. Nothing is granted after the market opens.',
      },
      {
        anchor: 'section[aria-labelledby="bots-title"]',
        title: 'Bots',
        body: 'The bot population, created before the market opens and fixed after that.',
      },
      {
        anchor: 'section[aria-labelledby="runs-title"]',
        title: 'Bot runs',
        body: 'Start a run once the market is open, choosing how many bots of each kind trade and how fast, and stop it.',
      },
      {
        anchor: 'section[aria-labelledby="health-title"]',
        title: 'Health',
        body:
          'How trading keeps up: the writer and its queue, commands in the last minute, the outbox, market data and ' +
          'analytics. A warning says in words what needs attention.',
        absent: 'It appears here once the market is open.',
      },
    ],
  },
};
