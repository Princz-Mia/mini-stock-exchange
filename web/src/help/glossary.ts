/**
 * The one glossary behind the term tips and the "How this exchange works" page, worded like the project's handbook.
 * Each term is explained once, here, so a tip and the page never disagree.
 */
export const GLOSSARY = {
  limit: {
    term: 'Limit order',
    definition:
      'Buy or sell at a stated price or better. Any part that does not trade at once waits in the book until it fills or you cancel it. ' +
      'It never trades with your own resting order: it stops there instead, and the rest is cancelled.',
  },
  protected: {
    term: 'Protected market order',
    definition:
      'Trades at once against the best orders in the book, but only within the price you set. Any part that cannot trade within it is cancelled; it never waits in the book.',
  },
  available: {
    term: 'Available',
    definition: 'What you can use for a new order now: your cash or shares minus what is reserved for open orders.',
  },
  reserved: {
    term: 'Reserved',
    definition:
      'Cash or shares set aside for your open orders, so they cannot be spent twice. A fill pays from it, and a buyer gets back at once ' +
      'whatever the trade cost less than their limit; what an order no longer needs returns to available when it is cancelled or stops.',
  },
  mark: {
    term: 'Mark',
    definition:
      "The price a holding is valued at: the latest trade, or the instrument's reference price, labelled as such, before its first trade.",
  },
  equity: {
    term: 'Equity',
    definition: 'Your cash plus every holding at its mark. Reserved amounts are part of it, counted once.',
  },
  averageCost: {
    term: 'Average cost',
    definition:
      "An estimate of what one share of a holding cost you: shares you started with at the instrument's reference price, and " +
      'each buy at its price. A sale leaves it unchanged; selling everything starts it afresh.',
  },
  unrealized: {
    term: 'Unrealized P/L',
    definition:
      'An estimate of the profit or loss on what you still hold: its value at the mark less what it cost at the average cost. ' +
      'It becomes real only when you sell.',
  },
  partial: {
    term: 'Partial fill',
    definition:
      'Only part of an order has traded so far. The rest of a limit order keeps waiting in the book, unless it stopped at your own ' +
      'resting order; the rest of a protected market order is cancelled.',
  },
  halted: {
    term: 'Halted',
    definition:
      'The operator has paused trading. New orders are refused, but open orders can still be cancelled. Trading goes on when the operator resumes the market.',
  },
} as const;

export type Term = keyof typeof GLOSSARY;
