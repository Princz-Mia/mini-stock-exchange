import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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
});
