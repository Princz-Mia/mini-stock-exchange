/**
 * EventSource's readyState once it has given up for good. The browser reconnects by itself only after a dropped
 * connection; an answer that is not a 200 event stream, such as the proxy's 502 while a service restarts, closes it
 * for good, and nothing opens it again unless the page does.
 */
export const CLOSED = 2;

/** The longest wait between two attempts to open a stream the browser gave up on. */
export const MAX_REOPEN_DELAY_MS = 30_000;

/**
 * How long to wait before the next attempt, after this many failed ones in a row: 1, 2, 4, 8 and 16 seconds, then 30
 * seconds for as long as it takes. A service that is restarting comes back within the first few; one that is down does
 * not get asked every second meanwhile.
 */
export function reopenDelay(failures: number): number {
  return Math.min(1_000 * 2 ** failures, MAX_REOPEN_DELAY_MS);
}
