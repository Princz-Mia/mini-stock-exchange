import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { reopenDelay } from '../app/reopen';
import { fakeStreams, frame } from '../test/fakeStream';
import { STALE_AFTER_MS, useMarketStream } from './useMarketStream';

const NOVA = '00000000-0000-0000-0000-000000000001';
const ORBIT = '00000000-0000-0000-0000-000000000002';

describe('market stream', () => {
  let clock = 0;
  const now = () => clock;

  beforeEach(() => {
    vi.useFakeTimers();
    clock = 0;
  });

  afterEach(() => vi.useRealTimers());

  it('opens the instrument stream and replaces the view with each frame', () => {
    const streams = fakeStreams();
    const { result } = renderHook(() => useMarketStream(NOVA, { create: streams.create, now }));

    expect(streams.latest()?.url).toBe(`/api/v1/market-events?instrumentId=${NOVA}`);
    expect(result.current.transport).toBe('connecting');
    act(() => streams.latest()?.emit('market', frame(NOVA, '3')));
    act(() => streams.latest()?.emit('market', frame(NOVA, '4', { bids: [] })));

    expect(result.current.transport).toBe('open');
    expect(result.current.frame?.sourceCommandSequence).toBe('4');
    expect(result.current.frame?.bids).toEqual([]);
  });

  it('takes the feed state from heartbeats, and ignores frames for another instrument', () => {
    const streams = fakeStreams();
    const { result } = renderHook(() => useMarketStream(NOVA, { create: streams.create, now }));

    act(() => streams.latest()?.emit('market', frame(NOVA, '3')));
    act(() => streams.latest()?.emit('market', frame(ORBIT, '9')));
    act(() =>
      streams.latest()?.emit('heartbeat', { bootId: 'b', feed: { state: 'STALE', lag: 12 }, at: '2026-11-04T12:00:10Z' }),
    );

    expect(result.current.frame?.sourceCommandSequence).toBe('3');
    expect(result.current.feed).toEqual({ state: 'STALE', lag: 12 });
  });

  it('marks the transport stale after 15 seconds without any event, and live again on the next one', () => {
    const streams = fakeStreams();
    const { result } = renderHook(() => useMarketStream(NOVA, { create: streams.create, now }));
    act(() => streams.latest()?.emit('market', frame(NOVA, '3')));

    clock = STALE_AFTER_MS;
    act(() => vi.advanceTimersByTime(1_000));
    expect(result.current.transport).toBe('open');
    clock = STALE_AFTER_MS + 1;
    act(() => vi.advanceTimersByTime(1_000));
    expect(result.current.transport).toBe('stale');

    act(() => streams.latest()?.emit('heartbeat', { bootId: 'b', feed: { state: 'LIVE', lag: 0 }, at: 'x' }));
    expect(result.current.transport).toBe('open');
  });

  it('closes the old stream and starts clean on another instrument', () => {
    const streams = fakeStreams();
    const { result, rerender } = renderHook(({ id }) => useMarketStream(id, { create: streams.create, now }), {
      initialProps: { id: NOVA },
    });
    act(() => streams.latest()?.emit('market', frame(NOVA, '3')));

    rerender({ id: ORBIT });

    expect(streams.opened[0]?.closed).toBe(true);
    expect(streams.latest()?.url).toContain(ORBIT);
    expect(result.current.frame).toBeNull();
    expect(result.current.transport).toBe('connecting');
  });

  it('waits 1, 2, 4, 8 and 16 seconds between attempts, then 30 for as long as it takes', () => {
    expect([0, 1, 2, 3, 4, 5, 6, 40, 2_000].map(reopenDelay)).toEqual([
      1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000, 30_000,
    ]);
  });

  it('opens a stream the browser gave up on again, waiting longer after each failure, and keeps what it showed', () => {
    const streams = fakeStreams();
    const { result } = renderHook(() => useMarketStream(NOVA, { create: streams.create, now }));
    act(() => streams.latest()?.emit('market', frame(NOVA, '3')));

    // The proxy answers 502 while market data restarts: the browser closes the stream for good.
    act(() => streams.latest()?.fail('refused'));
    expect(result.current.transport).toBe('reconnecting');
    expect(result.current.frame?.sourceCommandSequence, 'the last frame stays on screen').toBe('3');
    expect(streams.opened[0]?.closed).toBe(true);

    for (const [attempt, delay] of [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000].entries()) {
      act(() => vi.advanceTimersByTime(delay - 1));
      expect(streams.opened, `attempt ${attempt + 1} not before ${delay} ms`).toHaveLength(attempt + 1);
      act(() => vi.advanceTimersByTime(1));
      expect(streams.opened, `attempt ${attempt + 1} after ${delay} ms`).toHaveLength(attempt + 2);
      expect(streams.latest()?.url).toBe(`/api/v1/market-events?instrumentId=${NOVA}`);
      // Still not answering: refused again, and still saying so, never connected without a frame.
      expect(result.current.transport).toBe('reconnecting');
      act(() => streams.latest()?.fail('refused'));
    }
    expect(streams.opened.slice(0, -1).every((stream) => stream.closed)).toBe(true);

    // Market data is back: the next attempt answers, and a failure after that waits one second again.
    act(() => vi.advanceTimersByTime(30_000));
    const back = streams.latest();
    expect(result.current.transport).toBe('reconnecting');
    act(() => back?.emit('heartbeat', { bootId: 'b', feed: { state: 'LIVE', lag: 0 }, at: 'x' }));
    expect(result.current.transport).toBe('open');
    act(() => back?.fail('refused'));
    const before = streams.opened.length;
    act(() => vi.advanceTimersByTime(1_000));
    expect(streams.opened).toHaveLength(before + 1);
  });

  it('leaves a dropped connection to the browser, and says it is reconnecting rather than that nothing comes', () => {
    const streams = fakeStreams();
    const { result } = renderHook(() => useMarketStream(NOVA, { create: streams.create, now }));
    act(() => streams.latest()?.emit('market', frame(NOVA, '3')));

    act(() => streams.latest()?.fail('dropped'));
    clock = STALE_AFTER_MS * 4;
    act(() => vi.advanceTimersByTime(60_000));

    expect(streams.opened, 'the browser reconnects by itself').toHaveLength(1);
    expect(streams.opened[0]?.closed).toBe(false);
    expect(result.current.transport).toBe('reconnecting');
    act(() => streams.latest()?.emit('market', frame(NOVA, '4')));
    expect(result.current.transport).toBe('open');
  });

  it('stops trying once the page is left or another instrument is chosen', () => {
    const streams = fakeStreams();
    const { rerender, unmount } = renderHook(({ id }) => useMarketStream(id, { create: streams.create, now }), {
      initialProps: { id: NOVA },
    });
    act(() => streams.latest()?.fail('refused'));
    rerender({ id: ORBIT });
    act(() => vi.advanceTimersByTime(60_000));
    expect(streams.opened.map((stream) => stream.url.slice(-1))).toEqual(['1', '2']);

    act(() => streams.latest()?.fail('refused'));
    unmount();
    act(() => vi.advanceTimersByTime(60_000));
    expect(streams.opened).toHaveLength(2);
  });
});
