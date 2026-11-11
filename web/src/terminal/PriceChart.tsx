import { useQuery } from '@tanstack/react-query';
import {
  AreaSeries,
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type MouseEventParams,
  type SeriesType,
  type UTCTimestamp,
} from 'lightweight-charts';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';

import { api, pollEvery } from '../api/client';
import { useScheme } from '../app/theme';
import type { Candles, TapeTrade } from '../api/types';
import { groupDigits, utcTime } from '../format';
import {
  BAR_SIZES,
  type Bar,
  type BarSize,
  CHART_TYPES,
  type ChartType,
  RANGES,
  type Range,
  aggregate,
  drawn,
  fits,
  fittedBarSize,
  complete,
  hasGap,
  remember,
  throughOf,
  vwap,
  windowFor,
  withTrades,
} from './candles';
import { fromCents } from './money';

/**
 * The tape's trades as one point per second, oldest first: the fallback when there are no bars to draw. The tape is
 * newest first and can hold several trades in a second; the last trade of each second wins.
 */
export function chartPoints(trades: readonly TapeTrade[]): { time: UTCTimestamp; value: number }[] {
  const bySecond = new Map<number, number>();
  for (const trade of [...trades].reverse()) {
    bySecond.set(Math.floor(Date.parse(trade.executedAt) / 1000), Number(trade.price));
  }
  return [...bySecond.entries()]
    .sort(([a], [b]) => a - b)
    .map(([time, value]) => ({ time: time as UTCTimestamp, value }));
}

type Kind = ChartType;

/** Shares traded in each second of the tape, oldest first, for the volume pane under the fallback line. */
export function tapeVolume(trades: readonly TapeTrade[]): { time: UTCTimestamp; value: number }[] {
  const bySecond = new Map<number, number>();
  for (const trade of trades) {
    const second = Math.floor(Date.parse(trade.executedAt) / 1000);
    bySecond.set(second, (bySecond.get(second) ?? 0) + trade.quantity);
  }
  return [...bySecond.entries()]
    .sort(([a], [b]) => a - b)
    .map(([time, value]) => ({ time: time as UTCTimestamp, value }));
}

export interface ChartSettings {
  type: ChartType;
  range: Range;
  bars: BarSize;
}

const DEFAULTS: ChartSettings = { type: 'candle', range: '2h', bars: '1m' };

function pick<T extends string>(value: string | null, allowed: readonly T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback;
}

/** The chart's settings live in the address, so a reload or a shared link keeps them; anything unknown is ignored. */
export function readSettings(params: URLSearchParams): ChartSettings {
  const range = pick(params.get('range'), Object.keys(RANGES) as Range[], DEFAULTS.range);
  const bars = pick(params.get('bars'), Object.keys(BAR_SIZES) as BarSize[], DEFAULTS.bars);
  return { type: pick(params.get('chart'), CHART_TYPES, DEFAULTS.type), range, bars: fittedBarSize(bars, range) };
}

function useChartSettings(): [ChartSettings, (change: Partial<ChartSettings>) => void] {
  const [params, setParams] = useSearchParams();
  const settings = readSettings(params);
  const change = (update: Partial<ChartSettings>) => {
    const next = { ...settings, ...update };
    setParams(
      (current) => {
        const params = new URLSearchParams(current);
        params.set('chart', next.type);
        params.set('range', next.range);
        params.set('bars', fittedBarSize(next.bars, next.range));
        return params;
      },
      { replace: true },
    );
  };
  return [settings, change];
}

const TYPE_LABELS: Record<ChartType, string> = { candle: 'Candles', line: 'Line', area: 'Area' };
const RANGE_LABELS: Record<Range, string> = { '30m': '30 min', '2h': '2 hours', '1d': '1 day' };
const BAR_LABELS: Record<BarSize, string> = { '1m': '1 min', '5m': '5 min', '15m': '15 min', '1h': '1 hour' };

function Choice<T extends string>({
  label,
  options,
  labels,
  value,
  onChange,
  allowed = () => true,
}: {
  label: string;
  options: readonly T[];
  labels: Record<T, string>;
  value: T;
  onChange: (value: T) => void;
  allowed?: (value: T) => boolean;
}) {
  return (
    <div className="chart-choice" role="group" aria-label={label}>
      {options.map((option) => (
        <button
          key={option}
          type="button"
          className="chip"
          aria-pressed={value === option}
          disabled={!allowed(option)}
          onClick={() => onChange(option)}
        >
          {labels[option]}
        </button>
      ))}
    </div>
  );
}

/**
 * What the chart shows: bars; the tape as a line when there are no bars to show; nothing, with the reason; or that
 * nothing has traded.
 */
type View =
  | { kind: 'loading' }
  | { kind: 'empty' }
  | { kind: 'bars'; bars: Bar[]; behind: number | null }
  | { kind: 'tape'; reason: string }
  | { kind: 'unavailable'; reason: string };

/**
 * `seen` is every trade the tape has shown that the bars do not include yet, or null before the first market frame,
 * when the tape is not known. `sized` is the candles already in bars of the chosen size.
 */
function view(
  data: Candles | undefined,
  failed: boolean,
  seen: readonly TapeTrade[] | null,
  sized: Bar[],
  minutes: number,
): View {
  if (data === undefined) {
    if (!failed) {
      return { kind: 'loading' };
    }
    return seen !== null && seen.length > 0
      ? { kind: 'tape', reason: 'Bars unavailable' }
      : { kind: 'unavailable', reason: 'Bars unavailable' };
  }
  const { freshness, from } = data;
  const through = throughOf(data.candles, freshness.candlesThroughTradeSequence);
  if (seen === null) {
    // Without the tape, the bars are drawn as they are. With no bars either, nothing has traded in the range if the
    // bars are up to date with the newest trade; otherwise the tape is still to come.
    if (data.candles.length > 0) {
      return { kind: 'bars', bars: complete(sized, from), behind: null };
    }
    return hasGap([], through, freshness.latestTradeSequence) ? { kind: 'loading' } : { kind: 'empty' };
  }
  const gap = hasGap(seen, through, freshness.latestTradeSequence);
  if (data.candles.length === 0 && gap) {
    const reason = `Analytics behind by ${groupDigits(freshness.tradesBehind.toString())} trades`;
    return seen.length > 0 ? { kind: 'tape', reason } : { kind: 'unavailable', reason };
  }
  const bars = complete(withTrades(sized, seen, through, minutes, from), from);
  if (bars.length === 0) {
    return { kind: 'empty' };
  }
  return { kind: 'bars', bars, behind: gap ? freshness.tradesBehind : null };
}

/**
 * The trades the tape has shown for this instrument that the bars do not include yet, kept across frames (see
 * `remember`). Null while no frame has arrived and nothing is kept. Updated during render, as React allows for state
 * that follows its inputs, so every frame is counted exactly once and without an extra render.
 */
function useSeenTrades(
  instrumentId: string,
  trades: readonly TapeTrade[] | null,
  through: string | null,
): readonly TapeTrade[] | null {
  const [state, setState] = useState(() => ({
    instrumentId,
    trades,
    through,
    kept: remember([], trades ?? [], through),
  }));
  let kept = state.kept;
  if (state.instrumentId !== instrumentId || state.trades !== trades || state.through !== through) {
    kept = remember(state.instrumentId === instrumentId ? state.kept : [], trades ?? [], through);
    setState({ instrumentId, trades, through, kept });
  }
  return trades === null && kept.length === 0 ? null : kept;
}

function useCandles(instrumentId: string, range: Range) {
  return useQuery({
    queryKey: ['candles', instrumentId, range],
    queryFn: () => {
      const window = windowFor(range, Date.now());
      return api.candles(instrumentId, window.from, window.to);
    },
    refetchInterval: pollEvery(10_000),
  });
}

/** One line of text for the bar under the pointer, or the newest one: the canvas itself is hidden from readers. */
function BarLegend({ bar, bars }: { bar: Bar; bars: BarSize }) {
  const price = (cents: bigint) => groupDigits(fromCents(cents));
  return (
    <p className="chart-legend num">
      <span>{utcTime(new Date(bar.time * 1000).toISOString())}</span>
      <span>{BAR_LABELS[bars]}</span>
      <span>O {price(bar.open)}</span>
      <span>H {price(bar.high)}</span>
      <span>L {price(bar.low)}</span>
      <span>C {price(bar.close)}</span>
      <span>Vol {groupDigits(bar.volume.toString())}</span>
      <span>VWAP {groupDigits(vwap(bar))}</span>
    </p>
  );
}

/**
 * The selected instrument's price: bars from the analytics service's one-minute candles, topped up with live trades
 * they do not include yet, with volume underneath. While the analytics are behind further than the tape reaches, it
 * says so; with no bars to show, it draws the recent trades as a line instead.
 */
export function PriceChart({
  instrumentId,
  trades,
}: {
  instrumentId: string;
  /** The live tape, newest first; null until the first market frame. */
  trades: readonly TapeTrade[] | null;
}) {
  const [settings, change] = useChartSettings();
  const minutes = BAR_SIZES[settings.bars];
  const candles = useCandles(instrumentId, settings.range);
  const through =
    candles.data === undefined
      ? null
      : throughOf(candles.data.candles, candles.data.freshness.candlesThroughTradeSequence);
  const seen = useSeenTrades(instrumentId, trades, through);
  // Parsing and sizing the candles waits for new candles; each market frame only adds its trades on top.
  const sized = useMemo(() => aggregate(candles.data?.candles ?? [], minutes), [candles.data, minutes]);
  // A failed refetch keeps the last bars it had; only with none at all does the chart fall back to the tape.
  const shown = useMemo(
    () => view(candles.data, candles.isError, seen, sized, minutes),
    [candles.data, candles.isError, seen, sized, minutes],
  );
  const [hovered, setHovered] = useState<number | null>(null);

  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const series = useRef<Record<Kind, ISeriesApi<SeriesType>> | null>(null);
  const volume = useRef<ISeriesApi<'Histogram'> | null>(null);
  const colours = useRef({ up: '', down: '', neutral: '' });
  const scheme = useScheme();

  useEffect(() => {
    if (host.current === null) {
      return;
    }
    const created = createChart(host.current, {
      autoSize: true,
      layout: { attributionLogo: false },
      timeScale: { timeVisible: true, secondsVisible: false },
    });
    // All three kinds stay in the top pane and only the chosen one is shown. Removing a series instead would leave
    // the top pane empty for a moment, and the library drops empty panes, which moves the volume up into it.
    series.current = {
      candle: created.addSeries(CandlestickSeries),
      line: created.addSeries(LineSeries, { lineWidth: 2, visible: false }),
      area: created.addSeries(AreaSeries, { lineWidth: 2, visible: false }),
    };
    volume.current = created.addSeries(HistogramSeries, { priceFormat: { type: 'volume' }, priceLineVisible: false }, 1);
    const [main, lower] = created.panes();
    main?.setStretchFactor(3);
    lower?.setStretchFactor(1);
    const onMove = (event: MouseEventParams) => setHovered(typeof event.time === 'number' ? event.time : null);
    created.subscribeCrosshairMove(onMove);
    chart.current = created;
    return () => {
      created.unsubscribeCrosshairMove(onMove);
      created.remove();
      chart.current = null;
      series.current = null;
      volume.current = null;
    };
  }, []);

  // The chart draws on a canvas, so it takes its colours from the page's tokens rather than its own defaults, and
  // takes them again when the scheme changes, without being created anew. It runs before the data below, which
  // colours the volume bars from these.
  useEffect(() => {
    if (chart.current === null || series.current === null) {
      return;
    }
    const token = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    const [up, down, line] = [token('--buy'), token('--sell'), token('--secondary')];
    colours.current = { up, down, neutral: `${line}80` };
    chart.current.applyOptions({
      layout: { background: { color: token('--surface') }, textColor: token('--muted') },
      grid: { vertLines: { color: token('--line') }, horzLines: { color: token('--line') } },
    });
    series.current.candle.applyOptions({
      upColor: up,
      downColor: down,
      borderUpColor: up,
      borderDownColor: down,
      wickUpColor: up,
      wickDownColor: down,
    });
    series.current.line.applyOptions({ color: line });
    series.current.area.applyOptions({ lineColor: line, topColor: `${line}66`, bottomColor: `${line}0a` });
  }, [scheme]);

  // Candles, a line or an area for bars; the tape is always a line.
  const kind: Kind = shown.kind === 'tape' ? 'line' : settings.type;
  const bars = shown.kind === 'bars' ? shown.bars : null;
  useEffect(() => {
    if (series.current === null || volume.current === null) {
      return;
    }
    chart.current?.applyOptions({ timeScale: { secondsVisible: shown.kind === 'tape' } });
    for (const [name, each] of Object.entries(series.current) as [Kind, ISeriesApi<SeriesType>][]) {
      each.applyOptions({ visible: name === kind });
      if (name !== kind) {
        each.setData([]);
      }
    }
    const active = series.current[kind];
    if (bars === null) {
      const tape = shown.kind === 'tape' ? (seen ?? []) : null;
      active.setData(tape === null ? [] : chartPoints(tape));
      volume.current.setData(
        tape === null ? [] : tapeVolume(tape).map((point) => ({ ...point, color: colours.current.neutral })),
      );
      return;
    }
    const time = (bar: Bar) => bar.time as UTCTimestamp;
    active.setData(
      kind === 'candle'
        ? bars.map((bar) => ({
            time: time(bar),
            open: drawn(bar.open),
            high: drawn(bar.high),
            low: drawn(bar.low),
            close: drawn(bar.close),
          }))
        : bars.map((bar) => ({ time: time(bar), value: drawn(bar.close) })),
    );
    const { up, down } = colours.current;
    volume.current.setData(
      bars.map((bar) => ({
        time: time(bar),
        value: Number(bar.volume),
        color: `${bar.close >= bar.open ? up : down}80`,
      })),
    );
  }, [bars, kind, shown.kind, seen, scheme]);

  // A new instrument, range or bar size starts with everything in view; live updates leave the view alone.
  useEffect(() => {
    chart.current?.timeScale().fitContent();
  }, [instrumentId, settings.range, settings.bars, shown.kind]);

  const legendBar = bars === null ? null : (bars.find((bar) => bar.time === hovered) ?? bars.at(-1) ?? null);
  const subtitle =
    shown.kind === 'tape'
      ? `${shown.reason} · recent trades`
      : `${BAR_LABELS[settings.bars]} bars · last ${RANGE_LABELS[settings.range]}`;

  return (
    <section className="panel" aria-labelledby="chart-title">
      <div className="panel-header">
        <h2 id="chart-title">Price</h2>
        <span className="muted">{subtitle}</span>
      </div>
      <div className="chart-controls">
        <Choice
          label="Chart type"
          options={CHART_TYPES}
          labels={TYPE_LABELS}
          value={settings.type}
          onChange={(type) => change({ type })}
        />
        <Choice
          label="Range"
          options={Object.keys(RANGES) as Range[]}
          labels={RANGE_LABELS}
          value={settings.range}
          onChange={(range) => change({ range })}
        />
        <Choice
          label="Bar size"
          options={Object.keys(BAR_SIZES) as BarSize[]}
          labels={BAR_LABELS}
          value={settings.bars}
          onChange={(bars) => change({ bars })}
          allowed={(bars) => fits(bars, settings.range)}
        />
      </div>
      {shown.kind === 'bars' && shown.behind !== null && (
        <p className="chart-note" role="status">
          Analytics behind by {groupDigits(shown.behind.toString())} trades
        </p>
      )}
      {shown.kind === 'tape' && (
        <p className="chart-note" role="status">
          {shown.reason}. Showing recent trades as a line until bars catch up.
        </p>
      )}
      {shown.kind === 'unavailable' && (
        <p className="chart-note" role="status">
          {shown.reason}. Nothing to draw until trades or bars arrive.
        </p>
      )}
      {legendBar !== null && <BarLegend bar={legendBar} bars={settings.bars} />}
      <div className="chart-frame">
        {/* A picture of the bars and the tape, so hidden from screen readers; nothing in it may take focus. The
            library's attribution is a plain link here instead of its logo, which is a link inside the hidden canvas. */}
        <div ref={host} className="chart" aria-hidden="true" />
        {(shown.kind === 'loading' || shown.kind === 'empty') && (
          <p className="chart-overlay muted">{shown.kind === 'loading' ? 'Loading bars…' : 'No trades in this range yet.'}</p>
        )}
      </div>
      <p className="chart-credit muted">
        Chart:{' '}
        <a href="https://www.tradingview.com/lightweight-charts/" target="_blank" rel="noopener noreferrer">
          TradingView Lightweight Charts
        </a>
      </p>
    </section>
  );
}
