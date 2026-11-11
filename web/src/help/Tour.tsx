import { useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';

import type { TourScript } from './tours';

export type TourEnd = 'finished' | 'skipped';

const HIGHLIGHT = 'tour-target';

/** On <html> while the tour is open: the page keeps room below its content, so the card never hides a part. */
const OPEN = 'tour-open';

function prefersLessMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function onPageChange(changed: () => void): () => void {
  const observer = new MutationObserver(changed);
  observer.observe(document.body, { childList: true, subtree: true });
  return () => observer.disconnect();
}

/**
 * The element a step points at, followed as the page changes: a part that loads after the step opened is outlined
 * when it arrives, and one React replaces is outlined again.
 */
function useAnchor(selector: string | null): HTMLElement | null {
  return useSyncExternalStore(
    onPageChange,
    () => (selector === null ? null : document.querySelector<HTMLElement>(selector)),
    () => null,
  );
}

/**
 * A welcome, then one pointer per step: the part of the page is outlined and scrolled into view, and a card at the
 * bottom of the screen says what it is. A modal dialog, so the keyboard stays in it and Escape skips. Each step's
 * title takes focus and carries the step number and text, so a screen reader reads the whole step. Skip is offered at
 * every step.
 */
export function Tour({ script, onEnd }: { script: TourScript; onEnd: (end: TourEnd) => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const ended = useRef(false);
  const [index, setIndex] = useState(-1);
  const id = useId();
  const step = index >= 0 ? script.steps[index] : undefined;
  const target = useAnchor(step?.anchor ?? null);

  const end = (how: TourEnd) => {
    if (!ended.current) {
      ended.current = true;
      onEnd(how);
    }
  };

  // A layout effect, so the dialog closes before React removes it and the browser can hand focus back.
  useLayoutEffect(() => {
    const element = dialog.current;
    const root = document.documentElement;
    root.classList.add(OPEN);
    if (element !== null && !element.open) {
      element.showModal();
    }
    return () => {
      root.classList.remove(OPEN);
      if (element?.open) {
        element.close();
      }
    };
  }, []);

  // Outline this step's part of the page and bring it into view, above the card.
  useEffect(() => {
    if (target === null) {
      return;
    }
    target.classList.add(HIGHLIGHT);
    target.scrollIntoView?.({ block: 'center', behavior: prefersLessMotion() ? 'auto' : 'smooth' });
    return () => target.classList.remove(HIGHLIGHT);
  }, [target]);

  // Each step's title is a new element, so focus has to move to it, and a screen reader reads the step.
  useEffect(() => {
    heading.current?.focus();
  }, [index]);

  const last = index === script.steps.length - 1;
  const title = step?.title ?? script.welcome.title;
  const body =
    step === undefined ? script.welcome.body : target !== null ? step.body : `${step.body} ${step.absent ?? ''}`.trim();
  const progress = step === undefined ? null : `Step ${index + 1} of ${script.steps.length}`;

  return (
    <dialog
      ref={dialog}
      className="tour"
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-body`}
      onCancel={(event) => {
        // Escape: the tour ends as skipped, through the same path as the button.
        event.preventDefault();
        end('skipped');
      }}
    >
      {progress !== null && (
        <p className="tour-progress muted" aria-hidden="true">
          {progress}
        </p>
      )}
      <h2 key={index} id={`${id}-title`} ref={heading} tabIndex={-1} aria-describedby={`${id}-body`}>
        {title}
        {progress !== null && <span className="visually-hidden">, {progress.toLowerCase()}</span>}
      </h2>
      <p id={`${id}-body`}>{body}</p>
      <div className="tour-actions">
        <button type="button" className="quiet" onClick={() => end('skipped')}>
          Skip tour
        </button>
        {index >= 0 && (
          <button type="button" onClick={() => setIndex(index - 1)}>
            Back
          </button>
        )}
        <button type="button" className="primary" onClick={() => (last ? end('finished') : setIndex(index + 1))}>
          {index < 0 ? 'Start the tour' : last ? 'Done' : 'Next'}
        </button>
      </div>
    </dialog>
  );
}
