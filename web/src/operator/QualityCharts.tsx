import { useQueries } from '@tanstack/react-query';
import { useState } from 'react';
import { useSearchParams } from 'react-router';

import { ApiError, api, isRefusal } from '../api/client';
import type { BotRunDetail, MarketQuality, QualityMinute } from '../api/types';
import { useKeyboardScroll } from '../app/useKeyboardScroll';
import { groupDigits, groupInteger } from '../format';
import { TermTip } from '../help/TermTip';
import { comparability } from './compare';
import {
  CHART_HEIGHT,
  CHART_WIDTH,
  drawnCents,
  linePath,
  minuteIndex,
  percent,
  type Point,
  yDomain,
} from './qualityChart';

/** While Market Data is behind, a window's last minutes may be missing: asked again this often until it is live. */
const BEHIND_POLL = 10_000;

/** How long after a window ended its last snapshots may still be on their way to Market Data. */
const FRESH_WINDOW = 60_000;

/** The feed states that end by themselves, so asking again helps; an offline feed never catches up. */
const WAITING = new Set<MarketQuality['feed']['state']>(['STARTING', 'CATCHING_UP', 'STALE', 'PAUSED']);

/** The worst state first: the note says the worst of the runs' answers. */
const FEED_ORDER: readonly MarketQuality['feed']['state'][] = [
  'DISABLED',
  'PAUSED',
  'STALE',
  'STARTING',
  'CATCHING_UP',
  'LIVE',
];

const BEHIND_TEXT: Record<MarketQuality['feed']['state'], string> = {
  LIVE: '',
  CATCHING_UP: "Market data is catching up, so a run's last minutes may be missing for now.",
  STARTING: "Market data is connecting, so a run's last minutes may be missing for now.",
  STALE: "Market data has gone quiet, so a run's last minutes may be missing.",
  PAUSED: "Market data has paused on a record it can't use, so a run's last minutes may be missing until it is fixed.",
  DISABLED: "Market data's feed is off here, so a run's minutes may be incomplete.",
};

/** A run as the comparison has it: its detail, or why it has none. */
export type ChartedRun = { id: string; detail: BotRunDetail | undefined; missing: string | null };

/** What a run's chart shows in place of its minutes, or null when it has them. */
type Shown = { quality: MarketQuality; note: null } | { quality: null; note: string };

const METRICS = [
  { key: 'spread', title: 'Spread', unit: 'price', value: (minute: QualityMinute) => minute.spread },
  { key: 'price', title: 'Last price', unit: 'price', value: (minute: QualityMinute) => minute.lastPrice },
] as const;

/**
 * The market over each run, from Market Data's samples of the book: for one instrument at a time, each run's average
 * spread and volatility, and its spread and last price minute by minute in small charts lined up from each run's start
 * and drawn on one scale per row, each with the same minutes as a table. Public market facts only, measured by each
 * run's window of command sequences.
 */
export function QualityCharts({ runs }: { runs: readonly ChartedRun[] }) {
  const [params, setParams] = useSearchParams();
  const finished = runs.flatMap((run) =>
    run.detail !== undefined && comparability(run.detail.run) === 'FINISHED' ? [run.detail] : [],
  );
  const instruments = new Map<string, string>();
  for (const detail of finished) {
    for (const each of detail.run.config.instruments) instruments.set(each.instrumentId, each.symbol);
  }
  const choices = [...instruments].sort((a, b) => a[1].localeCompare(b[1]));
  // The address's instrument, or the first every finished run traded, or the first any did.
  const shared = choices.find(([id]) => finished.every((detail) => detail.run.config.instruments.some((each) => each.instrumentId === id)));
  const asked = params.get('instrument');
  const instrument = choices.find(([id]) => id === asked)?.[0] ?? shared?.[0] ?? choices[0]?.[0] ?? null;
  const choose = (id: string) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        next.set('instrument', id);
        return next;
      },
      { replace: true },
    );

  const trades = (detail: BotRunDetail | undefined) =>
    instrument !== null && detail?.run.config.instruments.some((each) => each.instrumentId === instrument);
  const answers = useQueries({
    queries: runs.map((run) => {
      const detail = run.detail;
      const window = detail?.run.window;
      const ready = detail !== undefined && comparability(detail.run) === 'FINISHED' && trades(detail);
      return {
        queryKey: ['market-quality', instrument, window?.startSequence, window?.endSequence],
        queryFn: ({ signal }: { signal: AbortSignal }) =>
          api.marketQuality(instrument!, window!.startSequence, window!.endSequence!, { signal }),
        enabled: ready === true,
        // A finished window's minutes don't change once Market Data has them all: asked again while its feed is
        // behind, and for a minute after the window ended, as its last snapshots may still be on their way.
        staleTime: Infinity,
        refetchInterval: (query: { state: { data: MarketQuality | undefined; error: unknown } }) => {
          const data = query.state.data;
          if (isRefusal(query.state.error) || data === undefined) return false;
          const endedAt = window?.endedAt;
          const fresh = endedAt !== null && endedAt !== undefined && Date.now() - Date.parse(endedAt) < FRESH_WINDOW;
          return WAITING.has(data.feed.state) || fresh ? BEHIND_POLL : false;
        },
      };
    }),
  });

  const shown: Shown[] = runs.map((run, index) => {
    const answer = answers[index];
    if (run.detail === undefined) return { quality: null, note: run.missing ?? '' };
    if (comparability(run.detail.run) !== 'FINISHED') return { quality: null, note: 'Not finished' };
    if (!trades(run.detail)) return { quality: null, note: 'Not in this run' };
    if (answer?.data !== undefined) return { quality: answer.data, note: null };
    if (answer?.isError) {
      return {
        quality: null,
        note:
          answer.error instanceof ApiError && answer.error.problem?.errors?.some((each) => each.code === 'RANGE_TOO_LARGE')
            ? 'Too long to chart: over a day of minutes'
            : 'Market data is not reachable',
      };
    }
    return { quality: null, note: 'Loading…' };
  });
  const feeds = shown.flatMap((each) => (each.quality === null ? [] : [each.quality.feed.state]));
  const behind = FEED_ORDER.find((state) => state !== 'LIVE' && feeds.includes(state));
  const panel = useKeyboardScroll<HTMLElement>();

  return (
    <section ref={panel} className="panel" aria-labelledby="compare-market-title">
      <h2 id="compare-market-title">The market over each run</h2>
      {choices.length === 0 ? (
        <p className="muted">No finished run to chart yet.</p>
      ) : (
        <>
          <div className="chart-choice" role="group" aria-label="Instrument">
            {choices.map(([id, symbol]) => (
              <button
                key={id}
                type="button"
                className="chip"
                aria-pressed={id === instrument}
                onClick={() => choose(id)}
              >
                {symbol}
              </button>
            ))}
          </div>
          <p className="muted">
            Each run's minutes are counted from the minute it started, so the charts line up. A minute's book is its
            last; a minute at either end of a window may also hold moments just outside it.
          </p>
          {behind !== undefined && (
            <p className="chart-note" role="status">
              {BEHIND_TEXT[behind]}
            </p>
          )}
          <Summary runs={runs} shown={shown} />
          {METRICS.map((metric) => (
            <Multiples key={metric.key} metric={metric} runs={runs} shown={shown} />
          ))}
        </>
      )}
    </section>
  );
}

function Summary({ runs, shown }: { runs: readonly ChartedRun[]; shown: readonly Shown[] }) {
  const rows = [
    {
      label: 'Average spread',
      tip: 'spread' as const,
      value: (quality: MarketQuality) =>
        quality.averageSpread === null ? 'No two-sided minute' : groupDigits(quality.averageSpread),
    },
    {
      label: 'Volatility',
      tip: 'volatility' as const,
      value: (quality: MarketQuality) => (quality.volatility === null ? 'Too few price changes' : percent(quality.volatility)),
    },
    { label: 'Minutes with both sides', tip: null, value: (quality: MarketQuality) => groupInteger(quality.twoSidedMinutes) },
    { label: 'One-minute changes', tip: null, value: (quality: MarketQuality) => groupInteger(quality.returns) },
  ];
  return (
    <table aria-label="Market quality">
      <thead>
        <tr>
          <th scope="col">
            <span className="visually-hidden">Measure</span>
          </th>
          {runs.map((run, index) => (
            <th key={run.id} scope="col">
              Run {index + 1}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.label}>
            <th scope="row">
              {row.label}
              {row.tip !== null && <TermTip terms={[row.tip]} />}
            </th>
            {shown.map((each, index) => (
              <td key={runs[index]!.id} className={each.quality === null ? undefined : 'num'}>
                {each.quality === null ? each.note : row.value(each.quality)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

type Metric = (typeof METRICS)[number];

/** A run's minutes for one metric: drawn points, each minute's value as served, and the minute its window ended. */
type Series = { points: Point[]; values: Map<number, string | null>; endIndex: number | null };

/** One row of small charts for a metric, one per run, on one scale, and the same minutes as a table. */
function Multiples({ metric, runs, shown }: { metric: Metric; runs: readonly ChartedRun[]; shown: readonly Shown[] }) {
  const [open, setOpen] = useState(false);
  // One pass over each run's minutes, so the table reads each cell directly: linear in the minutes, however many.
  const series: Series[] = shown.map((each, index) => {
    const run = runs[index]!.detail?.run;
    if (each.quality === null || run === undefined) return { points: [], values: new Map(), endIndex: null };
    const values = new Map<number, string | null>();
    const points = each.quality.minutes.map((minute) => {
      const at = minuteIndex(minute.minute, run.startedAt);
      values.set(at, metric.value(minute));
      return { index: at, value: drawnCents(metric.value(minute)) };
    });
    const endedAt = run.window.endedAt;
    return { points, values, endIndex: endedAt === null ? null : minuteIndex(endedAt, run.startedAt) };
  });
  const indices = series.flatMap((each) => each.points.map((point) => point.index));
  const first = Math.min(0, ...indices);
  const last = Math.max(first, ...indices);
  const domain = yDomain(series.map((each) => each.points));
  const id = `compare-${metric.key}`;
  return (
    <>
      <h3 id={`${id}-title`}>{metric.title}</h3>
      <div className="compare-grid">
        {runs.map((run, index) => {
          const each = shown[index]!;
          const values = [...series[index]!.values.values()].filter((value) => value !== null);
          return (
            <figure key={run.id} className="quality-figure">
              <figcaption>
                Run {index + 1}
                <span className="muted">
                  {each.quality === null
                    ? each.note
                    : values.length === 0
                      ? 'No values'
                      : `${groupDigits(lowest(values))} to ${groupDigits(highest(values))}`}
                </span>
              </figcaption>
              <svg
                viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
                preserveAspectRatio="none"
                className="quality-chart"
                aria-hidden="true"
                focusable="false"
              >
                <line x1="0" y1={CHART_HEIGHT} x2={CHART_WIDTH} y2={CHART_HEIGHT} className="quality-axis" />
                {domain !== null && series[index]!.points.length > 0 && (
                  <path d={linePath(series[index]!.points, first, last, domain)} className="quality-line" />
                )}
              </svg>
            </figure>
          );
        })}
      </div>
      <details onToggle={(event) => setOpen(event.currentTarget.open)}>
        <summary>{metric.title} by minute, as a table</summary>
        {open && (
          <table aria-labelledby={`${id}-title`}>
            <thead>
              <tr>
                <th scope="col">Minute</th>
                {runs.map((run, index) => (
                  <th key={run.id} scope="col">
                    Run {index + 1}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {Array.from({ length: last - first + 1 }, (_, offset) => first + offset).map((minute) => (
                <tr key={minute}>
                  <th scope="row">{minute < 0 ? `${minute} min` : `+${minute} min`}</th>
                  {series.map((each, index) => (
                    <td key={runs[index]!.id} className="num">
                      {cell(each, minute)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </details>
    </>
  );
}

/** A minute's value; a dash where there is none; "Ended" only past the end of the run's window, not of its data. */
function cell(series: Series, minute: number): string {
  if (series.values.has(minute)) {
    const value = series.values.get(minute);
    return value === null || value === undefined ? '—' : groupDigits(value);
  }
  return series.endIndex !== null && minute > series.endIndex ? 'Ended' : '—';
}

function lowest(prices: readonly string[]): string {
  return prices.reduce((low, price) => (drawnCents(price)! < drawnCents(low)! ? price : low));
}

function highest(prices: readonly string[]): string {
  return prices.reduce((high, price) => (drawnCents(price)! > drawnCents(high)! ? price : high));
}
