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
        queryFn: () => api.marketQuality(instrument!, window!.startSequence, window!.endSequence!),
        enabled: ready === true,
        // A finished window's minutes don't change, unless Market Data was still catching up when asked.
        staleTime: Infinity,
        refetchInterval: (query: { state: { data: MarketQuality | undefined; error: unknown } }) =>
          !isRefusal(query.state.error) && query.state.data !== undefined && query.state.data.feed.state !== 'LIVE'
            ? BEHIND_POLL
            : false,
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
  const behind = shown.some((each) => each.quality !== null && each.quality.feed.state !== 'LIVE');
  const panel = useKeyboardScroll<HTMLElement>();

  return (
    <section ref={panel} className="panel" aria-labelledby="compare-market-title">
      <h2 id="compare-market-title">The market over each run</h2>
      {choices.length === 0 ? (
        <p className="muted">No finished run to chart yet.</p>
      ) : (
        <>
          <div className="chart-controls" role="group" aria-label="Instrument">
            {choices.map(([id, symbol]) => (
              <button
                key={id}
                type="button"
                className="chart-choice"
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
          {behind && (
            <p className="chart-note" role="status">
              Market data is catching up, so a run's last minutes may be missing for now.
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
      value: (quality: MarketQuality) => (quality.volatility === null ? 'Too few minutes' : percent(quality.volatility)),
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

/** One row of small charts for a metric, one per run, on one scale, and the same minutes as a table. */
function Multiples({ metric, runs, shown }: { metric: Metric; runs: readonly ChartedRun[]; shown: readonly Shown[] }) {
  const [open, setOpen] = useState(false);
  const series: Point[][] = shown.map((each, index) => {
    const startedAt = runs[index]!.detail?.run.startedAt;
    if (each.quality === null || startedAt === undefined) return [];
    return each.quality.minutes.map((minute) => ({
      index: minuteIndex(minute.minute, startedAt),
      value: drawnCents(metric.value(minute)),
    }));
  });
  const indices = series.flatMap((points) => points.map((point) => point.index));
  const first = Math.min(0, ...indices);
  const last = Math.max(first, ...indices);
  const domain = yDomain(series);
  const id = `compare-${metric.key}`;
  return (
    <>
      <h3 id={`${id}-title`}>{metric.title}</h3>
      <div className="compare-grid">
        {runs.map((run, index) => {
          const each = shown[index]!;
          const values = each.quality?.minutes.flatMap((minute) => {
            const value = metric.value(minute);
            return value === null ? [] : [value];
          });
          return (
            <figure key={run.id} className="quality-figure">
              <figcaption>
                Run {index + 1}
                <span className="muted">
                  {each.quality === null
                    ? each.note
                    : values === undefined || values.length === 0
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
                {domain !== null && series[index]!.length > 0 && (
                  <path d={linePath(series[index]!, first, last, domain)} className="quality-line" />
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
                  <th scope="row">+{minute} min</th>
                  {series.map((points, index) => {
                    const point = points.find((each) => each.index === minute);
                    const lastOfRun = points.length === 0 ? null : Math.max(...points.map((each) => each.index));
                    const quality = shown[index]!.quality;
                    const text =
                      point === undefined
                        ? lastOfRun !== null && minute > lastOfRun
                          ? 'Ended'
                          : '—'
                        : (() => {
                            const value = quality?.minutes.find(
                              (each) => minuteIndex(each.minute, runs[index]!.detail!.run.startedAt) === minute,
                            );
                            const raw = value === undefined ? null : metric.value(value);
                            return raw === null ? '—' : groupDigits(raw);
                          })();
                    return (
                      <td key={runs[index]!.id} className="num">
                        {text}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </details>
    </>
  );
}

function lowest(prices: readonly string[]): string {
  return prices.reduce((low, price) => (drawnCents(price)! < drawnCents(low)! ? price : low));
}

function highest(prices: readonly string[]): string {
  return prices.reduce((high, price) => (drawnCents(price)! > drawnCents(high)! ? price : high));
}
