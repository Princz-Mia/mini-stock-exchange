import type { MarketFrame } from '../api/types';
import type { StreamFactory, StreamSource } from '../terminal/useMarketStream';

/** A controllable stand-in for EventSource: tests see which URLs were opened and push events into them. */
export class FakeStream implements StreamSource {
  readonly url: string;
  closed = false;
  onerror: ((event: Event) => void) | null = null;
  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(url: string) {
    this.url = url;
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  emit(type: string, data: unknown) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener(new MessageEvent(type, { data: JSON.stringify(data) }));
    }
  }

  close() {
    this.closed = true;
  }
}

export function fakeStreams() {
  const opened: FakeStream[] = [];
  const create: StreamFactory = (url) => {
    const stream = new FakeStream(url);
    opened.push(stream);
    return stream;
  };
  return { opened, create, latest: () => opened[opened.length - 1] };
}

/** A market frame for tests, with a one-level book on each side; override what a test cares about. */
export function frame(instrumentId: string, revision: string, overrides: Partial<MarketFrame> = {}): MarketFrame {
  return {
    bootId: '00000000-0000-0000-0000-0000000000b0',
    instrumentId,
    symbol: 'NOVA',
    venueState: 'OPEN',
    sourceCommandSequence: revision,
    bids: [{ price: '99.50', quantity: 10, orderCount: 1 }],
    asks: [{ price: '100.50', quantity: 5, orderCount: 2 }],
    lastPrice: '100.00',
    recentTrades: [],
    lastTradeSequence: '1',
    generatedAt: '2026-11-04T12:00:00Z',
    feed: { state: 'LIVE', lag: 0 },
    ...overrides,
  };
}
