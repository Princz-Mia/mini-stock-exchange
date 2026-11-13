import type { OrderDetail } from '../api/types';
import { groupDigits, groupInteger } from '../format';

/** An order and how much of it had filled, as the ticket reports it or a check compares from. */
export type Seen = Pick<OrderDetail, 'orderId' | 'filledQuantity'>;

/**
 * How far an order's fills are accounted for: reported, or not news. `firstSeen` is how much had filled when a check
 * first saw the order on its own; what filled between its arrival and then was never seen, until the ticket says what
 * filled on arrival. Null once the ticket has, or when the ticket reported the order before any check saw it.
 */
export interface Tracked {
  filled: number;
  firstSeen: number | null;
}

/** The orders a check follows: those still resting, and those that ended lately, kept in case the ticket reports one late. */
export interface Ledger {
  open: ReadonlyMap<string, Tracked>;
  gone: ReadonlyMap<string, Tracked>;
}

export const EMPTY_LEDGER: Ledger = { open: new Map(), gone: new Map() };

/**
 * How many ended orders are kept. Those still waiting for the ticket's report go last, so a late report finds its order
 * unless a thousand others have ended since.
 */
export const GONE_KEPT = 1_000;

const resting = (order: OrderDetail) => order.state === 'OPEN' || order.state === 'PARTIALLY_FILLED';

/**
 * The orders to compare in this check: every one still resting, with the ticket's reports applied. A report for an
 * order no check has seen starts it from what filled on arrival. A report for one a check saw first on its own adds
 * back the shares that filled before that, which were never reported, exactly once: whatever a check reported since
 * is not counted again. A report for an order whose arrival is already accounted for changes nothing.
 */
export function withPlacements(ledger: Ledger, placed: readonly Seen[]): Map<string, Tracked> {
  const before = new Map(ledger.open);
  for (const order of placed) {
    const known = before.get(order.orderId) ?? ledger.gone.get(order.orderId);
    if (known === undefined) {
      before.set(order.orderId, { filled: order.filledQuantity, firstSeen: null });
    } else if (known.firstSeen !== null) {
      const unseen = Math.max(0, known.firstSeen - order.filledQuantity);
      before.set(order.orderId, { filled: known.filled - unseen, firstSeen: null });
    }
  }
  return before;
}

/**
 * What to follow after a check: every order now resting, including one read on its own because it was missing from
 * the open list, and those that ended, most recent last, up to GONE_KEPT. An order first seen here starts provisional.
 *
 * Counts only move forward, and an order that ended stays ended: the open list a check reads can be a request that
 * was already on its way, older than an order read on its own by the check before.
 */
export function afterCheck(ledger: Ledger, before: ReadonlyMap<string, Tracked>, now: ReadonlyMap<string, OrderDetail>): Ledger {
  const open = new Map<string, Tracked>();
  for (const order of now.values()) {
    const was = before.get(order.orderId);
    if (resting(order) && (was !== undefined || !ledger.gone.has(order.orderId))) {
      open.set(order.orderId, {
        filled: Math.max(order.filledQuantity, was?.filled ?? 0),
        firstSeen: was === undefined ? order.filledQuantity : was.firstSeen,
      });
    }
  }
  const gone = new Map(ledger.gone);
  for (const [orderId, was] of before) {
    if (!open.has(orderId)) {
      gone.delete(orderId);
      gone.set(orderId, {
        filled: Math.max(now.get(orderId)?.filledQuantity ?? 0, was.filled),
        firstSeen: was.firstSeen,
      });
    }
  }
  // The oldest first, keeping those still waiting for the ticket's report as long as there is room.
  for (const waiting of [false, true]) {
    for (const [orderId, tracked] of gone) {
      if (gone.size <= GONE_KEPT) {
        break;
      }
      if ((tracked.firstSeen !== null) === waiting) {
        gone.delete(orderId);
      }
    }
  }
  return { open, gone };
}

/** Shares of one resting order that filled since it was last seen, at its limit price: a resting order is the maker. */
export interface Fill {
  orderId: string;
  symbol: string;
  side: OrderDetail['side'];
  price: string;
  shares: number;
  filled: number;
  requested: number;
  /** How the order ended, if it has: filled, or cancelled after these fills. */
  ended: 'FILLED' | 'CANCELED' | null;
}

/**
 * The fills of the orders seen resting before, from what each of them is now. An order not known now, or one that
 * filled nothing more, gives none. Fills an order took on arrival are its ticket's to report: an order placed from
 * the ticket is seen first with what filled on arrival, and one placed elsewhere is first seen as it is.
 */
export function fillsSince(before: readonly Seen[], now: ReadonlyMap<string, OrderDetail>): Fill[] {
  const fills: Fill[] = [];
  for (const order of before) {
    const current = now.get(order.orderId);
    const shares = current === undefined ? 0 : current.filledQuantity - order.filledQuantity;
    if (current === undefined || shares <= 0 || current.limitPrice === null) {
      continue;
    }
    fills.push({
      orderId: order.orderId,
      symbol: current.symbol,
      side: current.side,
      price: current.limitPrice,
      shares,
      filled: current.filledQuantity,
      requested: current.requestedQuantity,
      ended: current.state === 'FILLED' || current.state === 'CANCELED' ? current.state : null,
    });
  }
  return fills;
}

/** New fills added to a notice already showing: one line per order, its shares added up, in the order first seen. */
export function mergeFills(shown: readonly Fill[], fresh: readonly Fill[]): Fill[] {
  const merged = shown.map((fill) => ({ ...fill }));
  for (const fill of fresh) {
    const same = merged.find((line) => line.orderId === fill.orderId);
    if (same === undefined) {
      merged.push({ ...fill });
    } else {
      same.shares += fill.shares;
      same.filled = fill.filled;
      same.ended = fill.ended;
    }
  }
  return merged;
}

/** At most this many orders are listed; the rest are counted. */
export const LISTED = 3;

/** What the notice says: a title, one line per order up to LISTED, and how many more. */
export function describeFills(fills: readonly Fill[]): {
  title: string;
  lines: { orderId: string; text: string }[];
  more: number;
} {
  const [only] = fills;
  const title =
    only !== undefined && fills.length === 1
      ? only.ended === 'FILLED'
        ? 'Order filled'
        : 'Order partly filled'
      : fills.every((fill) => fill.ended === 'FILLED')
        ? `${fills.length} orders filled`
        : `Fills on ${fills.length} orders`;
  const lines = fills.slice(0, LISTED).map((fill) => {
    const what = `${fill.side === 'BUY' ? 'Bought' : 'Sold'} ${groupInteger(fill.shares)} ${fill.symbol} at ${groupDigits(fill.price)}`;
    const part = `${groupInteger(fill.filled)} of ${groupInteger(fill.requested)} filled`;
    const text =
      fill.ended === 'FILLED'
        ? `${what}, the order is filled.`
        : fill.ended === 'CANCELED'
          ? `${what}, ${part}; the rest was cancelled.`
          : `${what}, ${part}.`;
    return { orderId: fill.orderId, text };
  });
  return { title, lines, more: Math.max(0, fills.length - LISTED) };
}
