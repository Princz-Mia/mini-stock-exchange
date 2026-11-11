import { useQueries, useQuery } from '@tanstack/react-query';

import { api, pollEvery } from '../api/client';
import type { Candles, Feed, HealthSummary, Instrument } from '../api/types';
import { FEED_LABELS } from '../app/FeedStatus';
import { groupInteger } from '../format';

/** One line of the panel: what it measures, its value, and a warning in words when it needs attention. */
export interface Figure {
  label: string;
  value: string;
  warning: string | null;
}

/** Trades the analytics may lag before the panel calls it a problem; a few behind is normal while trading. */
export const ANALYTICS_BEHIND_LIMIT = 100;

/** The oldest unpublished event's age past which the publication pipeline counts as behind. */
export const OUTBOX_AGE_LIMIT_MS = 5_000;

/** The share of commands that must finish within the budget, as the load test measures it. */
export const WITHIN_BUDGET_TARGET = 0.95;

/** A reading that may still be on its way, or may have failed: a failure is a warning, a first load is not. */
export type Reading<T> = { kind: 'checking' } | { kind: 'unreachable' } | ({ kind: 'read' } & T);

export type AnalyticsReading = Reading<{ symbol: string; behind: number; feed: Feed['state'] }>;

export type FeedReading = Reading<{ feed: Feed }>;

/**
 * The worst analytics freshness across instruments. A query that failed counts as unread, even if it still holds an
 * older answer, so an outage is not hidden behind the last good reading.
 */
export function worstAnalytics(
  candles: readonly { symbol: string; candles: Candles | undefined; failed: boolean }[],
): AnalyticsReading {
  let worst: AnalyticsReading = { kind: 'checking' };
  let failures = 0;
  for (const each of candles) {
    if (each.failed) {
      failures += 1;
      continue;
    }
    if (each.candles === undefined) {
      continue;
    }
    const behind = each.candles.freshness.tradesBehind;
    if (worst.kind !== 'read' || behind > worst.behind) {
      worst = { kind: 'read', symbol: each.symbol, behind, feed: each.candles.freshness.feed.state };
    }
  }
  if (worst.kind !== 'read' && candles.length > 0 && failures === candles.length) {
    return { kind: 'unreachable' };
  }
  return worst;
}

/**
 * The panel's figures from Execution's summary, Market Data's feed and the analytics' freshness. Each says plainly
 * when it needs attention, so nothing depends on colour alone.
 */
export function healthFigures(summary: HealthSummary, feed: FeedReading, analytics: AnalyticsReading): Figure[] {
  const { writer, lastMinute, outbox } = summary;
  const finished = lastMinute.committed + lastMinute.failed;
  const share = finished === 0 ? null : lastMinute.withinBudget / finished;
  const figures: Figure[] = [
    {
      label: 'Writer',
      value: writer.available ? 'Taking commands' : 'Halted',
      warning: writer.available ? null : 'Trading stopped until Execution restarts',
    },
    {
      label: 'Queue',
      value: `${groupInteger(writer.queueDepth)} of ${groupInteger(writer.queueCapacity)}`,
      warning: writer.queueDepth * 2 > writer.queueCapacity ? 'More than half full' : null,
    },
    {
      label: 'Commands per second',
      value: (finished / 60).toFixed(2),
      warning: null,
    },
    {
      label: 'Within 150 ms',
      value: share === null ? '—' : `${(Math.floor(share * 1000) / 10).toFixed(1)}%`,
      warning: share !== null && share < WITHIN_BUDGET_TARGET ? 'Below 95%' : null,
    },
    {
      label: 'Refused',
      value: groupInteger(lastMinute.refused),
      warning: lastMinute.refused > 0 ? 'Commands turned away' : null,
    },
    {
      label: 'New orders',
      value: outbox.admitting ? 'Admitted' : 'Paused',
      warning: outbox.admitting ? null : 'Publication backlog is full',
    },
    {
      label: 'Outbox',
      value:
        outbox.pendingEvents === null || outbox.oldestPendingMillis === null
          ? 'Not running here'
          : `${groupInteger(outbox.pendingEvents)} waiting, oldest ${(outbox.oldestPendingMillis / 1000).toFixed(1)} s`,
      warning:
        outbox.oldestPendingMillis !== null && outbox.oldestPendingMillis > OUTBOX_AGE_LIMIT_MS ? 'Publishing is behind' : null,
    },
    {
      label: 'Market data',
      // The lag shows only when the feed is not live, as the feed status elsewhere shows it.
      value:
        feed.kind === 'checking'
          ? 'Checking…'
          : feed.kind === 'unreachable'
            ? 'Not reachable'
            : `${FEED_LABELS[feed.feed.state]}${feed.feed.state !== 'LIVE' && feed.feed.lag > 0 ? ` · ${groupInteger(feed.feed.lag)} behind` : ''}`,
      warning:
        feed.kind === 'unreachable' ? 'Not reachable' : feed.kind === 'read' && feed.feed.state !== 'LIVE' ? 'Not live' : null,
    },
    {
      label: 'Analytics',
      value:
        analytics.kind === 'checking'
          ? 'Checking…'
          : analytics.kind === 'unreachable'
            ? 'Not reachable'
            : analytics.behind === 0
              ? 'Up to date'
              : `${groupInteger(analytics.behind)} trades behind (${analytics.symbol})`,
      warning:
        analytics.kind === 'unreachable'
          ? 'Not reachable'
          : analytics.kind !== 'read'
            ? null
            : analytics.feed !== 'LIVE'
              ? 'Not live'
              : analytics.behind > ANALYTICS_BEHIND_LIMIT
                ? 'Falling behind'
                : null,
    },
  ];
  return figures;
}

/**
 * How trading is keeping up, beside the market controls. Execution's figures refresh every two seconds; the analytics'
 * freshness, read from one minute of each instrument's candles, every ten.
 */
export function HealthPanel({ instruments, feed }: { instruments: readonly Instrument[]; feed: FeedReading }) {
  const summary = useQuery({ queryKey: ['admin-health'], queryFn: api.health, refetchInterval: pollEvery(2_000) });
  const candles = useQueries({
    queries: instruments.map((instrument) => ({
      queryKey: ['health-candles', instrument.instrumentId],
      queryFn: () => {
        const now = Date.now();
        const to = new Date(Math.floor(now / 60_000) * 60_000 + 60_000);
        return api.candles(instrument.instrumentId, new Date(to.getTime() - 60_000).toISOString(), to.toISOString());
      },
      refetchInterval: pollEvery(10_000),
    })),
  });
  const analytics = worstAnalytics(
    instruments.map((instrument, index) => ({
      symbol: instrument.symbol,
      candles: candles[index]?.data,
      failed: candles[index]?.isError ?? false,
    })),
  );

  return (
    <section className="panel" aria-labelledby="health-title">
      <h2 id="health-title">Health</h2>
      {summary.isError && summary.data === undefined ? (
        <p role="alert" className="form-error">
          Trading health is not reachable: Execution may be down.
        </p>
      ) : summary.data === undefined ? (
        <p className="muted">Loading…</p>
      ) : (
        <dl className="health">
          {healthFigures(summary.data, feed, analytics).map((figure) => (
            <div key={figure.label} className={figure.warning === null ? 'health-row' : 'health-row health-warn'}>
              <dt>{figure.label}</dt>
              <dd>
                <span className="num">{figure.value}</span>
                {figure.warning !== null && <span className="tag health-tag">{figure.warning}</span>}
              </dd>
            </div>
          ))}
        </dl>
      )}
      {summary.isError && summary.data !== undefined && (
        <p role="alert" className="form-error">
          Could not refresh; showing the last reading.
        </p>
      )}
      <p className="muted health-note">Last minute of commands; analytics checked every ten seconds.</p>
    </section>
  );
}
