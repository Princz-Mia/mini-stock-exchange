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

// jsdom has no popovers. These keep the open state and send the toggle event a browser sends, with its newState.
if (typeof HTMLElement.prototype.showPopover !== 'function') {
  const opened = new WeakSet<HTMLElement>();
  const toggle = (element: HTMLElement, open: boolean) => {
    if (opened.has(element) === open) {
      return;
    }
    if (open) {
      opened.add(element);
    } else {
      opened.delete(element);
    }
    element.dispatchEvent(Object.assign(new Event('toggle'), { newState: open ? 'open' : 'closed' }));
  };
  HTMLElement.prototype.showPopover = function showPopover(this: HTMLElement) {
    toggle(this, true);
  };
  HTMLElement.prototype.hidePopover = function hidePopover(this: HTMLElement) {
    toggle(this, false);
  };
  HTMLElement.prototype.togglePopover = function togglePopover(this: HTMLElement) {
    toggle(this, !opened.has(this));
    return opened.has(this);
  };
}
