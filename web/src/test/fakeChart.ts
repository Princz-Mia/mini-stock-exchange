import { vi } from 'vitest';

/**
 * Stands in for the chart library in jsdom, which cannot draw on a canvas. It records each series the page adds,
 * with its kind, pane, visibility and the data last set; how often the view was fitted; the chart's options; and the
 * hover handler, so tests can check what would be drawn and move the pointer.
 */
export interface FakeSeries {
  kind: string;
  pane: number;
  data: unknown[];
  visible: boolean;
  /** Every option set so far, the latest winning, as the library merges them. */
  options: Record<string, unknown>;
}

export const drawnSeries: FakeSeries[] = [];

export const chartState = {
  fits: 0,
  options: [] as unknown[],
  hover: null as ((event: { time?: unknown }) => void) | null,
};

/** The shown series of a kind, if one is shown. */
export function liveSeries(kind: string): FakeSeries | undefined {
  return drawnSeries.filter((series) => series.kind === kind && series.visible).at(-1);
}

/** Every series that is not shown. */
export function hiddenSeries(): FakeSeries[] {
  return drawnSeries.filter((series) => !series.visible);
}

export function resetChart(): void {
  drawnSeries.length = 0;
  chartState.fits = 0;
  chartState.options = [];
  chartState.hover = null;
}

export const LineSeries = { kind: 'Line' };
export const AreaSeries = { kind: 'Area' };
export const CandlestickSeries = { kind: 'Candlestick' };
export const HistogramSeries = { kind: 'Histogram' };

/** Like the library, data must be in strictly increasing time order. */
function checkOrder(data: unknown[]): void {
  const times = data.map((point) => (point as { time: number }).time);
  for (let index = 1; index < times.length; index++) {
    if (!(times[index]! > times[index - 1]!)) {
      throw new Error(`data must be asc ordered by time, index=${index}, time=${times[index]}`);
    }
  }
}

export const createChart = vi.fn(() => {
  const pane = { setStretchFactor: () => undefined };
  return {
    addSeries: (definition: { kind: string }, options?: { visible?: boolean }, paneIndex = 0) => {
      const series: FakeSeries = {
        kind: definition.kind,
        pane: paneIndex,
        data: [],
        visible: options?.visible ?? true,
        options: { ...options },
      };
      drawnSeries.push(series);
      return {
        setData: (data: unknown[]) => {
          checkOrder(data);
          series.data = data;
        },
        applyOptions: (update: { visible?: boolean }) => {
          series.visible = update.visible ?? series.visible;
          series.options = { ...series.options, ...update };
        },
      };
    },
    panes: () => [pane, pane],
    applyOptions: (options: unknown) => {
      chartState.options.push(options);
    },
    timeScale: () => ({
      fitContent: () => {
        chartState.fits += 1;
      },
    }),
    subscribeCrosshairMove: (handler: (event: { time?: unknown }) => void) => {
      chartState.hover = handler;
    },
    unsubscribeCrosshairMove: () => {
      chartState.hover = null;
    },
    remove: () => undefined,
  };
});
