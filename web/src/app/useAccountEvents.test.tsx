import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { StreamSource } from '../terminal/useMarketStream';
import { DEBOUNCE_MS, useAccountEvents } from './useAccountEvents';

/** A stream the test drives: it records what was opened and lets the test send events. */
function fakeStreams() {
  const opened: { url: string; closed: boolean; listeners: Map<string, (event: MessageEvent<string>) => void> }[] = [];
  const create = (url: string): StreamSource => {
    const stream = { url, closed: false, listeners: new Map<string, (event: MessageEvent<string>) => void>() };
    opened.push(stream);
    return {
      addEventListener: (type, listener) => stream.listeners.set(type, listener),
      close: () => {
        stream.closed = true;
      },
      onerror: null,
    };
  };
  const send = (type: string, data: string) =>
    act(() => opened.at(-1)?.listeners.get(type)?.(new MessageEvent(type, { data })));
  return { opened, create, send };
}

function Probe({
  enabled,
  create,
  onChange,
}: {
  enabled: boolean;
  create: (url: string) => StreamSource;
  onChange?: () => void;
}) {
  useAccountEvents(enabled, create, onChange);
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
