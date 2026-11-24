import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { MarketStatus } from '../api/types';
import {
  CloseTime,
  closedPhrase,
  closeTimeText,
  countdownText,
  exchangeNow,
  heardFromExchange,
  scheduledCloseWords,
} from './closeTime';

const CLOSES_AT = '2026-11-05T11:00:00Z';

function market(state: MarketStatus['state'], closesAt: string | null = CLOSES_AT): MarketStatus {
  return {
    state,
    simulationId: 's',
    name: null,
    openedAt: state === 'SETUP' ? null : '2026-11-05T09:30:00Z',
    closesAt,
    closedAt: state === 'CLOSED' ? CLOSES_AT : null,
    closedOnSchedule: state === 'CLOSED',
    commandSequence: '7',
  };
}

describe('the market close time', () => {
  afterEach(() => {
    heardFromExchange(0, 0);
    vi.useRealTimers();
  });

  it('counts what is left in whole seconds, rounded up, in the two largest units', () => {
    expect(countdownText(0)).toBe('0 s');
    expect(countdownText(1)).toBe('1 s');
    expect(countdownText(59_001)).toBe('1 min 0 s');
    expect(countdownText(90_000)).toBe('1 min 30 s');
    expect(countdownText(3_600_000)).toBe('1 h 0 min');
    expect(countdownText(5_400_000 + 59_000)).toBe('1 h 30 min');
    expect(countdownText(30 * 86_400_000)).toBe('30 d 0 h');
    expect(countdownText(-5_000)).toBe('0 s');
  });

  it('says when the market closes and how long is left, and once the time has come that it is closing', () => {
    const at = Date.parse(CLOSES_AT);
    expect(closeTimeText(CLOSES_AT, at - 90_000)).toBe(
      'The market closes at its set time, 5 Nov 2026, 11:00:00 UTC, in 1 min 30 s.',
    );
    expect(closeTimeText(CLOSES_AT, at)).toBe(
      'Its set time, 5 Nov 2026, 11:00:00 UTC, has come: the exchange is closing the market, and new orders are refused.',
    );
  });

  it('says a close at the set time was the exchange’s own', () => {
    expect(closedPhrase(CLOSES_AT, true)).toBe('closed at its set time on 5 Nov 2026, 11:00:00 UTC');
    expect(closedPhrase(CLOSES_AT, false)).toBe('closed on 5 Nov 2026, 11:00:00 UTC');
    expect(closedPhrase(null, false)).toBe('closed');
  });

  it('does not pass off a close well after the set time as one at it', () => {
    const minuteLater = '2026-11-05T11:01:00Z';
    const later = '2026-11-05T11:01:01Z';
    expect(closedPhrase(minuteLater, true, CLOSES_AT)).toBe('closed at its set time on 5 Nov 2026, 11:01:00 UTC');
    expect(closedPhrase(later, true, CLOSES_AT)).toBe(
      'closed on 5 Nov 2026, 11:01:01 UTC (its set time, 5 Nov 2026, 11:00:00 UTC, had passed)',
    );
    expect(closedPhrase(later, false, CLOSES_AT)).toBe('closed on 5 Nov 2026, 11:01:01 UTC');
    const closed = market('CLOSED');
    expect(scheduledCloseWords(closed)).toBe('at its set time');
    expect(scheduledCloseWords({ ...closed, closedAt: minuteLater })).toBe('at its set time');
    expect(scheduledCloseWords({ ...closed, closedAt: later })).toBe('after its set time had passed');
    expect(scheduledCloseWords({ ...closed, closedOnSchedule: false })).toBeNull();
  });

  it('keeps the exchange’s clock from its answers, not the browser’s', () => {
    vi.useFakeTimers({ now: Date.parse('2026-11-05T10:00:00Z') });
    heardFromExchange(Date.parse('2026-11-05T10:03:00Z'));
    expect(exchangeNow()).toBe(Date.parse('2026-11-05T10:03:00Z'));
    vi.advanceTimersByTime(10_000);
    expect(exchangeNow()).toBe(Date.parse('2026-11-05T10:03:10Z'));
  });

  it('counts down each second on the exchange’s clock, while the market trades or is halted', () => {
    // The browser is three minutes behind the exchange, which is 1 min 30 s before the close.
    vi.useFakeTimers({ now: Date.parse('2026-11-05T10:55:30Z') });
    heardFromExchange(Date.parse('2026-11-05T10:58:30Z'));
    const { rerender } = render(<CloseTime market={market('OPEN')} />);
    expect(screen.getByText(/in 1 min 30 s\.$/)).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1_000));
    expect(screen.getByText(/in 1 min 29 s\.$/)).toBeInTheDocument();
    rerender(<CloseTime market={market('HALTED')} />);
    expect(screen.getByText(/in 1 min 29 s\.$/)).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(89_000));
    expect(screen.getByText(/has come: the exchange is closing the market/)).toBeInTheDocument();
  });

  it('shows nothing for a market with no close time, one not open yet, or one that has closed', () => {
    for (const status of [market('OPEN', null), market('SETUP', null), market('CLOSED')]) {
      const { container, unmount } = render(<CloseTime market={status} />);
      expect(container).toBeEmptyDOMElement();
      unmount();
    }
  });
});
