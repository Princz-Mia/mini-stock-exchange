import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';

// Canvas rendering is out of reach in jsdom; the production build bundles the real chart library.
vi.mock('lightweight-charts', () => import('./test/fakeChart'));

// jsdom has no EventSource. Pages that stream get a silent one unless a test provides its own.
class SilentEventSource {
  onerror: ((event: Event) => void) | null = null;
  addEventListener() {}
  close() {}
}
vi.stubGlobal('EventSource', SilentEventSource);

// jsdom has the dialog element but not its methods. These do what the tests need: open and close, with the events.
if (typeof HTMLDialogElement.prototype.showModal !== 'function') {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.open = false;
    this.dispatchEvent(new Event('close'));
  };
}
