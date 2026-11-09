import { useCallback, useEffect, useRef } from 'react';

function update(element: HTMLElement): void {
  if (element.scrollWidth > element.clientWidth) {
    element.tabIndex = 0;
  } else {
    element.removeAttribute('tabindex');
  }
}

/**
 * For a panel whose content can be wider than the panel, such as a table on a phone. While it scrolls sideways it
 * takes keyboard focus, so arrow keys reach the hidden columns; while everything fits it is no extra Tab stop.
 * Returns a callback ref, so it also works for a panel that mounts after the first render.
 */
export function useKeyboardScroll<T extends HTMLElement>(): (element: T | null) => void {
  const current = useRef<T | null>(null);
  const observer = useRef<ResizeObserver | null>(null);

  // After every render, since new rows can make the content wider.
  useEffect(() => {
    if (current.current !== null) {
      update(current.current);
    }
  });

  useEffect(() => () => observer.current?.disconnect(), []);

  // And whenever the panel itself changes size, such as on rotating a phone.
  return useCallback((element: T | null) => {
    observer.current?.disconnect();
    observer.current = null;
    current.current = element;
    if (element === null) {
      return;
    }
    update(element);
    if (typeof ResizeObserver !== 'undefined') {
      observer.current = new ResizeObserver(() => update(element));
      observer.current.observe(element);
    }
  }, []);
}
