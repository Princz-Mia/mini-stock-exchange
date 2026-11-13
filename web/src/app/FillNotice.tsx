import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

import { api, isRefusal } from '../api/client';
import type { OrderDetail } from '../api/types';
import {
  EMPTY_LEDGER,
  type Fill,
  type Ledger,
  type Seen,
  afterCheck,
  describeFills,
  fillsSince,
  mergeFills,
  withPlacements,
} from './fills';

/** The first wait before a failed check is tried again; it doubles up to RETRY_MAX_MS. */
export const RETRY_MS = 1_000;
const RETRY_MAX_MS = 30_000;

/**
 * Notices for resting orders that filled. Each check reads the open orders, the same query the terminal shows, and
 * compares them with what it followed before: an order that filled more shares is a fill, and an order that is no
 * longer open is read on its own to see how it ended. An order a check sees for the first time is only followed from
 * then, so the first check after signing in says nothing.
 *
 * An order placed from the ticket is reported by `placed` with what filled on arrival, so shares a bot takes before a
 * check sees it are still a fill, counted once however late the report comes (see `withPlacements`), and a check runs
 * at once. Checks run one at a time, so two account changes in quick succession cannot compare against the same state.
 * A check that fails keeps what it followed and is tried again after a pause that doubles up to 30 seconds, so a fill
 * whose account change came during the failure is not lost.
 */
export function useFillNotices() {
  const client = useQueryClient();
  const ledger = useRef<Ledger>(EMPTY_LEDGER);
  const placedOrders = useRef(new Map<string, Seen>());
  const queue = useRef<Promise<void>>(Promise.resolve());
  const live = useRef(true);
  const retry = useRef<{ timer: ReturnType<typeof setTimeout> | undefined; wait: number }>({
    timer: undefined,
    wait: RETRY_MS,
  });
  const [fills, setFills] = useState<Fill[]>([]);

  const look = useCallback(async () => {
    const open = await client.fetchQuery({
      queryKey: ['orders', 'active'],
      queryFn: () => api.orders('active'),
      staleTime: 0,
      retry: false,
    });
    const placed = [...placedOrders.current.values()];
    const before = withPlacements(ledger.current, placed);
    const now = new Map<string, OrderDetail>(open.orders.map((order) => [order.orderId, order]));
    for (const orderId of before.keys()) {
      if (!now.has(orderId)) {
        try {
          now.set(orderId, await api.order(orderId));
        } catch (error) {
          // Gone for good, such as after the market was reset: nothing to say about it. Anything else is retried.
          if (!isRefusal(error)) {
            throw error;
          }
        }
      }
    }
    const fresh = fillsSince(
      [...before].map(([orderId, tracked]) => ({ orderId, filledQuantity: tracked.filled })),
      now,
    );
    ledger.current = afterCheck(ledger.current, before, now);
    for (const order of placed) {
      placedOrders.current.delete(order.orderId);
    }
    if (fresh.length > 0 && live.current) {
      setFills((shown) => mergeFills(shown, fresh));
    }
  }, [client]);

  // The check to try again, kept apart so the retry can call the latest one.
  const again = useRef<() => void>(() => undefined);
  const check = useCallback(() => {
    clearTimeout(retry.current.timer);
    retry.current.timer = undefined;
    queue.current = queue.current.then(look).then(
      () => {
        retry.current.wait = RETRY_MS;
      },
      () => {
        if (live.current && retry.current.timer === undefined) {
          const wait = retry.current.wait;
          retry.current.wait = Math.min(wait * 2, RETRY_MAX_MS);
          retry.current.timer = setTimeout(() => again.current(), wait);
        }
      },
    );
  }, [look]);

  useEffect(() => {
    again.current = check;
  }, [check]);

  useEffect(() => {
    live.current = true;
    const pending = retry.current;
    return () => {
      live.current = false;
      clearTimeout(pending.timer);
    };
  }, []);

  /** An order the ticket placed, with what filled on arrival; only a resting order can fill later. */
  const placed = useCallback(
    (order: Seen) => {
      placedOrders.current.set(order.orderId, order);
      check();
    },
    [check],
  );

  const dismiss = useCallback(() => setFills([]), []);
  return { fills, check, placed, dismiss };
}

/** How the ticket tells the notices about an order it placed; null where there are no notices. */
export const FillWatch = createContext<((order: Seen) => void) | null>(null);

export function useFillWatch() {
  return useContext(FillWatch);
}

/**
 * The notice itself, in the bottom-left corner above the page, away from the ticket and the Cancel and paging buttons
 * at the right. Its text is in a polite status region that is always there, so a screen reader hears each new fill
 * without losing its place; the link and the dismiss button sit outside that region. It stays until dismissed (or
 * Escape inside it), since it has a link to follow, and new fills join it. While it shows, the page gets room below
 * its content and focus scrolls clear of it, so it never hides what a trader needs. While `held` (a tour is open and
 * the rest of the page is inert), what shows stays as it was, and fills that come meanwhile are shown and announced
 * when it closes.
 */
export function FillNotice({
  fills,
  onDismiss,
  held = false,
}: {
  fills: readonly Fill[];
  onDismiss: () => void;
  held?: boolean;
}) {
  // What shows: the fills as they were when the tour opened, until it closes. Fills that came meanwhile then join, and
  // only they are news to a screen reader; the rest does not change, so it is not read out again.
  const [visible, setVisible] = useState(held ? [] : fills);
  if (!held && visible !== fills) {
    setVisible(fills);
  }
  const shown = visible.length > 0;
  const card = useRef<HTMLDivElement>(null);
  const { title, lines, more } = describeFills(visible);

  useLayoutEffect(() => {
    const element = card.current;
    if (!shown || element === null) {
      return undefined;
    }
    const root = document.documentElement;
    const room = () => root.style.setProperty('--fill-notice-room', `${element.offsetHeight + 16}px`);
    room();
    root.classList.add('fill-notice-open');
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(room);
    observer?.observe(element);
    return () => {
      observer?.disconnect();
      root.classList.remove('fill-notice-open');
      root.style.removeProperty('--fill-notice-room');
    };
  }, [shown]);

  // Focus inside the notice would be left on nothing once it goes, so it moves to the page, where it stays put. The page
  // takes focus only for this, so a click on blank space elsewhere keeps moving where Tab goes next.
  const close = () => {
    const hadFocus = card.current?.contains(document.activeElement) ?? false;
    onDismiss();
    const main = document.querySelector<HTMLElement>('main');
    if (hadFocus && main !== null) {
      main.tabIndex = -1;
      main.addEventListener('blur', () => main.removeAttribute('tabindex'), { once: true });
      main.focus({ preventScroll: true });
    }
  };

  return (
    <div
      ref={card}
      className={shown ? 'fill-notice' : 'visually-hidden'}
      role={shown ? 'region' : undefined}
      aria-label={shown ? 'Fills' : undefined}
      onKeyDown={(event) => {
        if (shown && event.key === 'Escape') {
          close();
        }
      }}
    >
      <div role="status" className="fill-notice-text">
        {shown && (
          <>
            <strong>{title}</strong>
            <ul>
              {lines.map((line) => (
                <li key={line.orderId}>{line.text}</li>
              ))}
            </ul>
            {more > 0 && <p className="muted">And {more} more.</p>}
          </>
        )}
      </div>
      {shown && (
        <div className="fill-notice-actions">
          <Link
            to="/portfolio"
            onClick={(event) => {
              // Opened in another tab or window, the notice stays here.
              if (event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
                close();
              }
            }}
          >
            View in Portfolio
          </Link>
          <button type="button" className="quiet" onClick={close}>
            Dismiss
          </button>
        </div>
      )}
    </div>
  );
}
