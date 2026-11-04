import { useEffect, useState } from 'react';

import type { Feed, Heartbeat, MarketFrame } from '../api/types';

/** The transport, separately from the data's own freshness: a live connection can still carry stale data. */
export type Transport = 'connecting' | 'open' | 'stale';

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
}

export type StreamFactory = (url: string) => StreamSource;

const browserStream: StreamFactory = (url) => new EventSource(url) as unknown as StreamSource;

/**
 * Follows one instrument's live market. Each `market` frame replaces the whole view, so a missed or repeated frame
 * cannot leave it inconsistent. Heartbeats carry the feed's state; silence beyond the limit marks the transport stale.
 * EventSource reconnects by itself after an error, and a new instrument opens a new stream.
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
    let lastEvent = now();
    const source = create(`/api/v1/market-events?instrumentId=${encodeURIComponent(instrumentId)}`);
    source.addEventListener('market', (event) => {
      const frame = JSON.parse(event.data) as MarketFrame;
      lastEvent = now();
      if (frame.instrumentId === instrumentId) {
        setState({ instrumentId, frame, feed: frame.feed, transport: 'open' });
      }
    });
    source.addEventListener('heartbeat', (event) => {
      const heartbeat = JSON.parse(event.data) as Heartbeat;
      lastEvent = now();
      setState((current) => ({ ...sameInstrument(current, instrumentId), feed: heartbeat.feed, transport: 'open' }));
    });
    source.onerror = () =>
      setState((current) => ({ ...sameInstrument(current, instrumentId), transport: 'connecting' }));
    const watchdog = setInterval(() => {
      if (now() - lastEvent > STALE_AFTER_MS) {
        setState((current) =>
          current.transport === 'stale' ? current : { ...sameInstrument(current, instrumentId), transport: 'stale' },
        );
      }
    }, 1_000);
    return () => {
      clearInterval(watchdog);
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
