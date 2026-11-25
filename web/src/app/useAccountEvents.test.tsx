import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { StreamSource } from '../terminal/useMarketStream';
import { DEBOUNCE_MS, useAccountEvents } from './useAccountEvents';

/** A stream the test drives: it records what was opened and lets the test send events, or fail it. */
function fakeStreams() {
  const opened: {
    url: string;
    closed: boolean;
    listeners: Map<string, (event: MessageEvent<string>) => void>;
    source: { readyState: number; onerror: ((event: Event) => void) | null };
  }[] = [];
  const create = (url: string): StreamSource => {
    const source = {
      readyState: 0,
      addEventListener: (type: string, listener: (event: MessageEvent<string>) => void) => stream.listeners.set(type, listener),
      close: () => {
        stream.closed = true;
      },
      onerror: null as ((event: Event) => void) | null,
    };
    const stream = { url, closed: false, listeners: new Map<string, (event: MessageEvent<string>) => void>(), source };
    opened.push(stream);
    return source;
  };
  const send = (type: string, data: string) =>
    act(() => opened.at(-1)?.listeners.get(type)?.(new MessageEvent(type, { data })));
  /** The latest stream fails: dropped, which the browser reconnects by itself, or refused, which it gives up on. */
  const fail = (way: 'dropped' | 'refused') =>
    act(() => {
      const source = opened.at(-1)?.source;
      if (source !== undefined) {
        source.readyState = way === 'refused' ? 2 : 0;
        source.onerror?.(new Event('error'));
      }
    });
  return { opened, create, send, fail };
}

function Probe({
  enabled,
  create,
  onChange,
  moment,
}: {
  enabled: boolean;
  create: (url: string) => StreamSource;
  onChange?: () => void;
  moment?: string;
}) {
  const { reconnecting } = useAccountEvents(enabled, create, onChange, moment);
  return <output data-testid="reconnecting">{String(reconnecting)}</output>;
}

const reconnecting = () => screen.getByTestId('reconnecting').textContent;

describe('useAccountEvents', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('refetches the private queries once after a burst of changes', () => {
    const client = new QueryClient();
    const invalidate = vi.spyOn(client, 'invalidateQueries').mockResolvedValue();
    const streams = fakeStreams();
    render(
      <QueryClientProvider client={client}>
        <Probe enabled create={streams.create} />
      </QueryClientProvider>,
    );

    expect(streams.opened.map((stream) => stream.url)).toEqual(['/api/v1/account-events']);
    streams.send('account', '{"accountVersion":"7"}');
    act(() => vi.advanceTimersByTime(DEBOUNCE_MS - 50));
    streams.send('account', '{"accountVersion":"8"}');
    streams.send('heartbeat', '{"at":"2026-11-09T12:00:00Z"}');
    act(() => vi.advanceTimersByTime(DEBOUNCE_MS - 50));
    expect(invalidate, 'still within the pause after the latest frame').not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(50));

    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ['portfolio'],
      ['orders'],
      ['own-trades'],
    ]);
  });

  it('calls back once after a burst of changes, after the refetch, and with the latest callback', () => {
    const client = new QueryClient();
    const steps: string[] = [];
    vi.spyOn(client, 'invalidateQueries').mockImplementation(() => {
      steps.push('refetch');
      return Promise.resolve();
    });
    const streams = fakeStreams();
    const first = vi.fn();
    const latest = vi.fn(() => {
      steps.push('callback');
    });
    const view = render(
      <QueryClientProvider client={client}>
        <Probe enabled create={streams.create} onChange={first} />
      </QueryClientProvider>,
    );
    view.rerender(
      <QueryClientProvider client={client}>
        <Probe enabled create={streams.create} onChange={latest} />
      </QueryClientProvider>,
    );
    expect(streams.opened, 'a new callback does not reconnect').toHaveLength(1);
    streams.send('account', '{"accountVersion":"7"}');
    streams.send('account', '{"accountVersion":"8"}');
    act(() => vi.advanceTimersByTime(DEBOUNCE_MS - 1));
    expect(latest).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(latest).toHaveBeenCalledTimes(1);
    expect(first).not.toHaveBeenCalled();
    expect(steps).toEqual(['refetch', 'refetch', 'refetch', 'callback']);
  });

  it('opens a refused stream again once an account may have been created, and only a refused one', () => {
    const client = new QueryClient();
    const invalidate = vi.spyOn(client, 'invalidateQueries').mockResolvedValue();
    const streams = fakeStreams();
    const at = (moment: string) => (
      <QueryClientProvider client={client}>
        <Probe enabled create={streams.create} moment={moment} />
      </QueryClientProvider>
    );
    const page = render(at('s|OPEN|'));

    // A dropped connection is the browser's to reconnect: a new moment opens nothing.
    streams.fail('dropped');
    page.rerender(at('t|SETUP|1'));
    expect(streams.opened).toHaveLength(1);

    // Refused, as every stream is once a new simulation has started and the trader has no account in it: the browser
    // gives up, and nothing is opened until an account may have been created.
    streams.fail('refused');
    page.rerender(at('t|SETUP|1'));
    expect(streams.opened).toHaveLength(1);
    page.rerender(at('t|SETUP|2'));
    expect(streams.opened).toHaveLength(2);
    expect(streams.opened[0]?.closed).toBe(true);

    // Refused again, then funded: the stream opened after that is the trader's, and refetches as any does.
    streams.fail('refused');
    page.rerender(at('t|SETUP|3'));
    expect(streams.opened).toHaveLength(3);
    page.rerender(at('t|SETUP|4'));
    expect(streams.opened, 'a stream that was not refused stays as it is').toHaveLength(3);
    streams.send('account', '{"accountVersion":"1"}');
    act(() => vi.advanceTimersByTime(DEBOUNCE_MS));
    expect(invalidate).toHaveBeenCalledTimes(3);
    page.unmount();
    expect(streams.opened.every((stream) => stream.closed)).toBe(true);
  });

  it('opens nothing for someone who is not a trader, and closes on leaving', () => {
    const client = new QueryClient();
    const streams = fakeStreams();
    const page = render(
      <QueryClientProvider client={client}>
        <Probe enabled={false} create={streams.create} />
      </QueryClientProvider>,
    );
    expect(streams.opened).toHaveLength(0);

    page.rerender(
      <QueryClientProvider client={client}>
        <Probe enabled create={streams.create} />
      </QueryClientProvider>,
    );
    page.unmount();

    expect(streams.opened).toHaveLength(1);
    expect(streams.opened[0]?.closed).toBe(true);
  });

  it('opens a stream given up on again while the market trades, waiting longer each time, and from 1 s after a frame', () => {
    const client = new QueryClient();
    vi.spyOn(client, 'invalidateQueries').mockResolvedValue();
    const streams = fakeStreams();
    render(
      <QueryClientProvider client={client}>
        <Probe enabled create={streams.create} moment="s|OPEN|" />
      </QueryClientProvider>,
    );

    // The proxy answers 502 while Execution restarts, again and again: the moment never changes in an open market.
    for (const [attempt, delay] of [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000].entries()) {
      streams.fail('refused');
      expect(streams.opened.at(-1)?.closed).toBe(true);
      act(() => vi.advanceTimersByTime(delay - 1));
      expect(streams.opened, `attempt ${attempt + 1} not before ${delay} ms`).toHaveLength(attempt + 1);
      act(() => vi.advanceTimersByTime(1));
      expect(streams.opened, `attempt ${attempt + 1} after ${delay} ms`).toHaveLength(attempt + 2);
    }

    // Back: its first frame starts the pauses again from one second.
    streams.send('account', '{"accountVersion":"9"}');
    streams.fail('refused');
    const before = streams.opened.length;
    act(() => vi.advanceTimersByTime(1_000));
    expect(streams.opened).toHaveLength(before + 1);
    expect(streams.opened.every((stream) => stream.url === '/api/v1/account-events')).toBe(true);
  });

  it('says it is reconnecting only for a stream that had delivered, until a frame comes again', () => {
    const client = new QueryClient();
    vi.spyOn(client, 'invalidateQueries').mockResolvedValue();
    const streams = fakeStreams();
    const at = (enabled: boolean) => (
      <QueryClientProvider client={client}>
        <Probe enabled={enabled} create={streams.create} moment="s|OPEN|" />
      </QueryClientProvider>
    );
    const page = render(at(true));

    // Refused before anything came, as for a trader with no account: nothing was lost.
    streams.fail('refused');
    expect(reconnecting()).toBe('false');
    act(() => vi.advanceTimersByTime(1_000));
    streams.send('heartbeat', '{"at":"2026-11-09T12:00:00Z"}');
    expect(reconnecting()).toBe('false');

    // Delivering, then dropped: the browser reconnects by itself, and the page says so meanwhile.
    streams.fail('dropped');
    expect(reconnecting()).toBe('true');
    streams.send('account', '{"accountVersion":"2"}');
    expect(reconnecting()).toBe('false');

    // Refused after delivering: still reconnecting while the next attempt waits and after it opens, until a frame.
    streams.fail('refused');
    expect(reconnecting()).toBe('true');
    act(() => vi.advanceTimersByTime(1_000));
    expect(reconnecting()).toBe('true');
    streams.fail('refused');
    page.rerender(at(false));
    expect(reconnecting(), 'not trading any more').toBe('false');
  });

  it('opens a waiting stream at once when an account may have been created, and tries no more after leaving', () => {
    const client = new QueryClient();
    const streams = fakeStreams();
    const at = (moment: string) => (
      <QueryClientProvider client={client}>
        <Probe enabled create={streams.create} moment={moment} />
      </QueryClientProvider>
    );
    const page = render(at('t|SETUP|1'));
    streams.fail('refused');
    streams.fail('refused');
    expect(streams.opened).toHaveLength(1);

    page.rerender(at('t|SETUP|2'));
    expect(streams.opened, 'without waiting out the pause').toHaveLength(2);
    act(() => vi.advanceTimersByTime(30_000));
    expect(streams.opened, 'the pause that was waiting is gone').toHaveLength(2);

    streams.fail('refused');
    page.unmount();
    act(() => vi.advanceTimersByTime(60_000));
    expect(streams.opened).toHaveLength(2);
    expect(streams.opened.every((stream) => stream.closed)).toBe(true);
  });
});
