import { useEffect, useState } from 'react';

import type { Feed, Heartbeat, MarketFrame } from '../api/types';
import { CLOSED, reopenDelay } from '../app/reopen';

/**
 * The transport, separately from the data's own freshness: a live connection can still carry stale data. Reconnecting
 * is a stream that failed and is being opened again, by the browser or by this hook; what it showed stays meanwhile.
 */
export type Transport = 'connecting' | 'open' | 'reconnecting' | 'stale';

export interface MarketStream {
  frame: MarketFrame | null;
  feed: Feed | null;
  transport: Transport;
}

/** No event of any kind for this long marks the transport stale, per the 10-second heartbeat contract. */
export const STALE_AFTER_MS = 15_000;

export interface StreamSource {
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  close(): void;
  onerror: ((event: Event) => void) | null;
  /** 2 once the browser has given up on the stream for good; a dropped connection is reconnected by itself. */
  readonly readyState?: number;
}

export type StreamFactory = (url: string) => StreamSource;

const browserStream: StreamFactory = (url) => new EventSource(url) as unknown as StreamSource;

/**
 * Follows one instrument's live market. Each `market` frame replaces the whole view, so a missed or repeated frame
 * cannot leave it inconsistent. Heartbeats carry the feed's state; silence beyond the limit marks the transport stale.
 * The browser reconnects by itself after a dropped connection. A stream it gave up on, after an answer that was not a
 * 200 event stream, is opened again here after a pause that doubles with each failure in a row, up to 30 seconds, and
 * starts again from one second once a frame arrives. A new instrument opens a new stream.
 */
export function useMarketStream(
  instrumentId: string | null,
  options: { create?: StreamFactory; now?: () => number } = {},
): MarketStream {
  const create = options.create ?? browserStream;
  const now = options.now ?? Date.now;
  // Tagged with its instrument: state left over from the previous one reads as a fresh, connecting stream.
  const [state, setState] = useState<MarketStream & { instrumentId: string | null }>({
    instrumentId: null,
    ...CONNECTING,
  });

  useEffect(() => {
    if (instrumentId === null) {
      return;
    }
    const url = `/api/v1/market-events?instrumentId=${encodeURIComponent(instrumentId)}`;
    let lastEvent = now();
    // Attempts that failed since the last frame, and the next attempt while one is waiting.
    let failures = 0;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let source: StreamSource;
    const heard = () => {
      lastEvent = now();
      failures = 0;
    };
    const open = () => {
      retry = undefined;
      // A new attempt is not silence: the watchdog counts from it.
      lastEvent = now();
      const opened = create(url);
      source = opened;
      opened.addEventListener('market', (event) => {
        const frame = JSON.parse(event.data) as MarketFrame;
        heard();
        if (frame.instrumentId === instrumentId) {
          setState({ instrumentId, frame, feed: frame.feed, transport: 'open' });
        }
      });
      opened.addEventListener('heartbeat', (event) => {
        const heartbeat = JSON.parse(event.data) as Heartbeat;
        heard();
        setState((current) => ({ ...sameInstrument(current, instrumentId), feed: heartbeat.feed, transport: 'open' }));
      });
      opened.onerror = () => {
        if (opened.readyState === CLOSED) {
          opened.close();
          retry = setTimeout(open, reopenDelay(failures));
          failures += 1;
        }
        setState((current) => ({ ...sameInstrument(current, instrumentId), transport: 'reconnecting' }));
      };
    };
    open();
    // Marks a connected stream that went silent, or a first attempt that never answered. A stream being opened again
    // already says so, and says it more truly than "no updates".
    const watchdog = setInterval(() => {
      if (now() - lastEvent > STALE_AFTER_MS) {
        setState((current) =>
          current.transport === 'stale' || (current.instrumentId === instrumentId && current.transport === 'reconnecting')
            ? current
            : { ...sameInstrument(current, instrumentId), transport: 'stale' },
        );
      }
    }, 1_000);
    return () => {
      clearInterval(watchdog);
      clearTimeout(retry);
      source.close();
    };
  }, [instrumentId, create, now]);

  return state.instrumentId === instrumentId
    ? { frame: state.frame, feed: state.feed, transport: state.transport }
    : CONNECTING;
}

const CONNECTING: MarketStream = { frame: null, feed: null, transport: 'connecting' };

/** Keeps state that belongs to this instrument; anything else starts over. */
function sameInstrument(current: MarketStream & { instrumentId: string | null }, instrumentId: string) {
  return current.instrumentId === instrumentId ? current : { instrumentId, ...CONNECTING };
}
