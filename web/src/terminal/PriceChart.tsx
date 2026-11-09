import { LineSeries, createChart, type IChartApi, type ISeriesApi, type UTCTimestamp } from 'lightweight-charts';
import { useEffect, useRef } from 'react';

import type { TapeTrade } from '../api/types';

/**
 * The chart wants one point per second, oldest first; the tape is newest first and can hold several trades in a
 * second. The last trade of each second wins. Prices become numbers here only to be drawn, never to be counted.
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

/** Recent trades as a line. One-minute candles replace it once the analytics service publishes them. */
export function PriceChart({ trades }: { trades: readonly TapeTrade[] }) {
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const series = useRef<ISeriesApi<'Line'> | null>(null);

  useEffect(() => {
    if (host.current === null) {
      return;
    }
    // The chart draws on a canvas, so it takes its colours from the page's tokens rather than its own defaults.
    const token = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    chart.current = createChart(host.current, {
      autoSize: true,
      layout: { background: { color: token('--surface') }, textColor: token('--muted'), attributionLogo: false },
      grid: { vertLines: { color: token('--line') }, horzLines: { color: token('--line') } },
      timeScale: { timeVisible: true, secondsVisible: true },
    });
    series.current = chart.current.addSeries(LineSeries, { lineWidth: 2, color: token('--secondary') });
    return () => {
      chart.current?.remove();
      chart.current = null;
      series.current = null;
    };
  }, []);

  useEffect(() => {
    series.current?.setData(chartPoints(trades));
  }, [trades]);

  return (
    <section className="panel" aria-labelledby="chart-title">
      <div className="panel-header">
        <h2 id="chart-title">Price</h2>
        <span className="muted">Recent trades · candles arrive with analytics</span>
      </div>
      {/* A picture of the tape below, so hidden from screen readers; nothing in it may take focus. The library's
          attribution is a plain link here instead of its logo, which is a link inside the hidden canvas. */}
      <div ref={host} className="chart" aria-hidden="true" />
      <p className="chart-credit muted">
        Chart:{' '}
        <a href="https://www.tradingview.com/lightweight-charts/" target="_blank" rel="noopener noreferrer">
          TradingView Lightweight Charts
        </a>
      </p>
    </section>
  );
}
