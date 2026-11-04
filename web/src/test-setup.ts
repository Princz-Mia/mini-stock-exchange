import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';

// Canvas rendering is out of reach in jsdom; the production build bundles the real chart library.
vi.mock('lightweight-charts', () => ({
  LineSeries: {},
  createChart: () => ({ addSeries: () => ({ setData: () => undefined }), remove: () => undefined }),
}));

// jsdom has no EventSource. Pages that stream get a silent one unless a test provides its own.
class SilentEventSource {
  onerror: ((event: Event) => void) | null = null;
  addEventListener() {}
  close() {}
}
vi.stubGlobal('EventSource', SilentEventSource);
