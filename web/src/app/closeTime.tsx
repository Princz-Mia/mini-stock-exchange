import { useEffect, useState } from 'react';

import type { MarketStatus } from '../api/types';
import { utcDateTime } from '../format';

/**
 * How far the exchange's clock is ahead of this browser's, in milliseconds, as the market status last showed: the Date
 * header of its answer against when it arrived. A browser's clock can be minutes away from the exchange's, and a
 * market closes on the exchange's, so a countdown to its close time is counted on that. The header is to the whole
 * second, so this is too.
 */
let ahead = 0;

/** Records the exchange's clock from an answer it sent at `sentAt`, which arrived at `receivedAt` here. */
export function heardFromExchange(sentAt: number, receivedAt: number = Date.now()): void {
  ahead = sentAt - receivedAt;
}

/** The exchange's time now, as far as this browser can tell, in epoch milliseconds. */
export function exchangeNow(): number {
  return Date.now() + ahead;
}

/** The exchange's time, read again every second while a page shows it. */
export function useExchangeNow(): number {
  const [now, setNow] = useState(exchangeNow);
  useEffect(() => {
    const timer = setInterval(() => setNow(exchangeNow()), 1_000);
    return () => clearInterval(timer);
  }, []);
  return now;
}

/** "2 d 3 h", "1 h 5 min", "4 min 20 s", "20 s": what is left, in whole seconds, rounded up. */
export function countdownText(milliseconds: number): string {
  const whole = Math.max(0, Math.ceil(milliseconds / 1000));
  const days = Math.floor(whole / 86_400);
  const hours = Math.floor((whole % 86_400) / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const seconds = whole % 60;
  if (days > 0) return `${days} d ${hours} h`;
  if (hours > 0) return `${hours} h ${minutes} min`;
  if (minutes > 0) return `${minutes} min ${seconds} s`;
  return `${seconds} s`;
}

/** The market is trading, or halted, and has a close time: a countdown applies. */
export function closesWhileTrading(market: MarketStatus | undefined): market is MarketStatus & { closesAt: string } {
  return (
    market !== undefined &&
    (market.state === 'OPEN' || market.state === 'HALTED') &&
    typeof market.closesAt === 'string'
  );
}

/**
 * When the market closes, from the status and the exchange's time: still to come, with how long is left; or reached,
 * while the status still says it is trading because the close has not run yet. The exchange refuses orders from that
 * time on, so it is said so, not counted below zero.
 */
export function closeTimeText(closesAt: string, now: number): string {
  const left = Date.parse(closesAt) - now;
  if (left <= 0) {
    return `Its set time, ${utcDateTime(closesAt)}, has come: the exchange is closing the market, and new orders are refused.`;
  }
  return `The market closes at its set time, ${utcDateTime(closesAt)}, in ${countdownText(left)}.`;
}

/**
 * How a closed market is said to have closed: at its set time when the exchange closed it then, and when, if known.
 * Read as "The market closed …".
 */
export function closedPhrase(closedAt: string | null, onSchedule: boolean): string {
  return `closed${onSchedule ? ' at its set time' : ''}${closedAt === null ? '' : ` on ${utcDateTime(closedAt)}`}`;
}

/**
 * The market's close time, with a countdown on the exchange's clock, while it trades or is halted; nothing for a
 * market with no close time, or one that has closed. The countdown is not announced each second: it is plain text, and
 * the status around it says when the market closes.
 */
export function CloseTime({ market, className = 'muted close-time' }: { market: MarketStatus | undefined; className?: string }) {
  const now = useExchangeNow();
  if (!closesWhileTrading(market)) {
    return null;
  }
  return <p className={className}>{closeTimeText(market.closesAt, now)}</p>;
}
