import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import type { BotRun } from '../api/types';
import { botRun } from '../test/botRun';
import { RunProgress } from './RunProgress';

afterEach(cleanup);

const START = Date.parse('2026-11-06T10:00:00Z');
const NO_WORKER = { workerId: null, heartbeatAt: null, leaseExpiresAt: null };

function loading(botsLoaded: number, botsTotal = 20_000, overrides: Partial<BotRun> = {}): BotRun {
  return botRun({
    activity: { ...botRun().activity, placed: 0, canceled: 0, rejected: 0, errors: 0, inFlight: 0 },
    progress: { phase: 'LOADING', botsLoaded, botsTotal },
    ...overrides,
  });
}

/** A trading run at a heartbeat `seconds` after its start, with these counts. */
function beat(seconds: number, activity: Partial<BotRun['activity']>, overrides: Partial<BotRun> = {}): BotRun {
  const at = new Date(START + seconds * 1000).toISOString().replace('.000', '');
  return botRun({
    worker: { workerId: 'sim-1', heartbeatAt: at, leaseExpiresAt: at },
    activity: { ...botRun().activity, ...activity },
    ...overrides,
  });
}

const bar = () => screen.getByRole('progressbar');
const fill = () => bar().firstElementChild as HTMLElement;

describe('a bot run on its way', () => {
  it('waits for a worker with a bar that only says so, and counts the wait', () => {
    render(<RunProgress run={botRun({ status: 'STARTING', worker: NO_WORKER, progress: null })} now={START + 7_000} />);

    expect(screen.getByRole('heading', { name: 'Waiting for a simulator worker' })).toBeInTheDocument();
    const progress = screen.getByRole('progressbar', { name: 'Waiting for a simulator worker' });
    // No amount to show: a bar without a value is read as under way.
    expect(progress).not.toHaveAttribute('aria-valuenow');
    expect(progress).not.toHaveAttribute('aria-valuemax');
    expect(progress).toHaveAttribute('aria-valuetext', 'Waiting for a simulator worker');
    expect(progress).toHaveClass('job-bar-waiting');
    expect(screen.getByText('Step 1 of 3 · waiting 7 s')).toBeInTheDocument();
    expect(screen.getByText('Waiting for a simulator worker to take the run.')).toBeInTheDocument();
  });

  it('loads its bots n of N with the percentage, rounded down', () => {
    render(<RunProgress run={loading(4_999)} now={START + 9_000} />);

    const progress = screen.getByRole('progressbar', { name: 'Loading bots' });
    expect(progress).toHaveAttribute('aria-valuemin', '0');
    expect(progress).toHaveAttribute('aria-valuemax', '20000');
    expect(progress).toHaveAttribute('aria-valuenow', '4999');
    expect(progress).toHaveAttribute('aria-valuetext', '4,999 of 20,000 bots loaded (24%)');
    expect(progress).not.toHaveClass('job-bar-waiting');
    expect(fill()).toHaveStyle({ width: '24%' });
    expect(screen.getByText('Step 2 of 3 · 4,999 of 20,000 bots loaded (24%)')).toBeInTheDocument();
    expect(
      screen.getByText("sim-1 is reading the bots' token files and building them; nothing trades until every bot is loaded."),
    ).toBeInTheDocument();
  });

  it('starts loading at none of its bots, with an empty bar', () => {
    render(<RunProgress run={loading(0, 3)} now={START + 3_000} />);

    expect(bar()).toHaveAttribute('aria-valuetext', '0 of 3 bots loaded (0%)');
    expect(fill()).toHaveStyle({ width: '0%' });
  });

  it('trades with its counts, and a timed run shows its time gone of its length', () => {
    const run = botRun({ endsAt: '2026-11-06T11:00:00Z' });
    render(<RunProgress run={run} now={START + 15 * 60_000} />);

    expect(screen.getByRole('heading', { name: 'Trading' })).toBeInTheDocument();
    expect(screen.getByText('Step 3 of 3 · 150 placed · 40 canceled · 6 rejected')).toBeInTheDocument();
    const progress = screen.getByRole('progressbar', { name: 'Trading' });
    expect(progress).toHaveAttribute('aria-valuemax', '3600');
    expect(progress).toHaveAttribute('aria-valuenow', '900');
    expect(progress).toHaveAttribute('aria-valuetext', '15 min of 1 h 0 min');
    expect(fill()).toHaveStyle({ width: '25%' });
    expect(screen.getByText('sim-1 holds the run.')).toBeInTheDocument();
  });

  it('trades without a bar when it lasts until stopped: there is nothing to measure it against', () => {
    render(<RunProgress run={botRun()} now={START + 80_000} />);

    expect(screen.getByRole('heading', { name: 'Trading' })).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).toBeNull();
    expect(screen.getByText('Step 3 of 3 · 150 placed · 40 canceled · 6 rejected')).toBeInTheDocument();
  });

  it('shows a run held by a worker that reports no phase as running, with its counts and no step', () => {
    render(<RunProgress run={botRun({ progress: null })} now={START + 80_000} />);

    expect(screen.getByRole('heading', { name: 'Running' })).toBeInTheDocument();
    expect(screen.getByText('150 placed · 40 canceled · 6 rejected')).toBeInTheDocument();
    expect(screen.queryByText(/Step/)).toBeNull();
  });

  it('says when the heartbeats stopped during loading, keeping the bar where it was', () => {
    render(<RunProgress run={loading(10_000, 20_000, { status: 'STALE' })} now={START + 120_000} />);

    expect(screen.getByRole('heading', { name: 'Heartbeats stopped' })).toBeInTheDocument();
    const progress = screen.getByRole('progressbar', { name: 'Heartbeats stopped' });
    expect(progress).toHaveAttribute('aria-valuenow', '10000');
    expect(progress).toHaveAttribute('aria-valuetext', '10,000 of 20,000 bots loaded (50%)');
    expect(screen.getByText('Last reported: 10,000 of 20,000 bots loaded (50%)')).toBeInTheDocument();
    expect(screen.getByText(/No heartbeat from sim-1 since 10:01:20 UTC/)).toHaveClass('form-error');
  });

  it('says when the heartbeats stopped during trading, with the last counts and no bar', () => {
    render(<RunProgress run={botRun({ status: 'STALE' })} now={START + 120_000} />);

    expect(screen.getByRole('heading', { name: 'Heartbeats stopped' })).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).toBeNull();
    expect(screen.getByText('Last reported: 150 placed · 40 canceled · 6 rejected')).toBeInTheDocument();
    expect(screen.getByText(/Its bots place nothing until a worker takes over/)).toHaveClass('form-error');
  });

  it('stops with a bar that only says so while the worker settles', () => {
    const run = botRun({ status: 'STOPPING', desiredState: 'STOPPED', stopRequestedAt: '2026-11-06T10:01:00Z' });
    render(<RunProgress run={run} now={START + 70_000} />);

    const progress = screen.getByRole('progressbar', { name: 'Stopping' });
    expect(progress).not.toHaveAttribute('aria-valuenow');
    expect(screen.getByText(/sim-1 is settling/)).toBeInTheDocument();
  });
});

describe('a bot run that places nothing', () => {
  const note = () => screen.queryByRole('status');

  it.each([
    ['pausedStaleData', 'Paused: market data is stale.'],
    ['pausedMarketNotOpen', 'Paused: the market is not open.'],
    ['pausedOther', "Paused: the bots' steps place nothing new"],
  ] as const)('says why when it has placed nothing at all: %s', (reason, words) => {
    render(<RunProgress run={beat(80, { placed: 0, canceled: 0, rejected: 0, errors: 0, [reason]: 30 })} now={START + 80_000} />);

    expect(note()).toHaveTextContent(words);
    expect(note()).toHaveClass('chart-note');
  });

  it('names the reason with the most paused steps', () => {
    render(
      <RunProgress
        run={beat(80, { placed: 0, pausedStaleData: 5, pausedMarketNotOpen: 40, pausedOther: 2 })}
        now={START + 80_000}
      />,
    );

    expect(note()).toHaveTextContent('Paused: the market is not open.');
  });

  it('says nothing when it has placed orders and only one heartbeat has been seen', () => {
    render(<RunProgress run={beat(80, { placed: 150, pausedStaleData: 300 })} now={START + 80_000} />);

    expect(note()).toBeNull();
  });

  it('says why once a run that traded stops placing between heartbeats, and lets go when it places again', () => {
    const view = render(<RunProgress run={beat(80, { placed: 150, pausedStaleData: 10 })} now={START + 80_000} />);
    expect(note()).toBeNull();

    view.rerender(<RunProgress run={beat(85, { placed: 150, pausedStaleData: 60 })} now={START + 85_000} />);
    expect(note()).toHaveTextContent('Paused: market data is stale.');

    // A poll that brings no new heartbeat keeps what the last two said.
    view.rerender(<RunProgress run={beat(85, { placed: 150, pausedStaleData: 60 })} now={START + 87_000} />);
    expect(note()).toHaveTextContent('Paused: market data is stale.');

    view.rerender(<RunProgress run={beat(90, { placed: 170, pausedStaleData: 61 })} now={START + 90_000} />);
    expect(note()).toBeNull();
  });

  it('judges by what grew between heartbeats, not by the totals', () => {
    const view = render(
      <RunProgress run={beat(80, { placed: 150, pausedStaleData: 500, pausedMarketNotOpen: 0 })} now={START + 80_000} />,
    );
    view.rerender(
      <RunProgress run={beat(85, { placed: 150, pausedStaleData: 501, pausedMarketNotOpen: 20 })} now={START + 85_000} />,
    );

    expect(note()).toHaveTextContent('Paused: the market is not open.');
  });

  it('says nothing between heartbeats in which nothing was placed and nothing paused', () => {
    const view = render(<RunProgress run={beat(80, { placed: 150, pausedStaleData: 10 })} now={START + 80_000} />);
    view.rerender(<RunProgress run={beat(85, { placed: 150, pausedStaleData: 10 })} now={START + 85_000} />);

    expect(note()).toBeNull();
  });

  it('does not carry one run’s heartbeats over to the next', () => {
    const view = render(<RunProgress run={beat(80, { placed: 150, pausedStaleData: 10 })} now={START + 80_000} />);
    const next = beat(85, { placed: 150, pausedStaleData: 60 }, { runId: '20000000-0000-0000-0000-000000000002' });
    view.rerender(<RunProgress run={next} now={START + 85_000} />);

    // A new run seen once, which has placed orders: nothing to judge by yet.
    expect(note()).toBeNull();
  });

  it('leaves loading and a lost worker to say for themselves why nothing trades', () => {
    const view = render(<RunProgress run={loading(100, 200, { activity: { ...botRun().activity, placed: 0, pausedOther: 9 } })} now={START} />);
    expect(note()).toBeNull();

    view.rerender(<RunProgress run={beat(80, { placed: 0, pausedStaleData: 9 }, { status: 'STALE' })} now={START + 99_000} />);
    expect(note()).toBeNull();
  });
});
