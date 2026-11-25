import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render } from '@testing-library/react';
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
  useAccountEvents(enabled, create, onChange, moment);
  return null;
}

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
});
