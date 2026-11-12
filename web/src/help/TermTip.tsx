import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';

import { GLOSSARY, type Term } from './glossary';

const GAP = 6;
const EDGE = 16;

/**
 * Where the tip goes: under its button, or above it when there is no room below, and never closer than 16 pixels to
 * an edge of the screen.
 */
export function placeTip(
  anchor: { top: number; bottom: number; left: number },
  tip: { width: number; height: number },
  screen: { width: number; height: number },
): { top: number; left: number } {
  const below = anchor.bottom + GAP;
  const top = below + tip.height <= screen.height - EDGE ? below : Math.max(EDGE, anchor.top - GAP - tip.height);
  const left = Math.max(EDGE, Math.min(anchor.left, screen.width - tip.width - EDGE));
  return { top, left };
}

/**
 * A small "?" beside a term that shows what it means, from the one glossary. The text is a native popover: it sits
 * above the page, so a panel that scrolls cannot cut it off, and the browser closes it on Escape or a click elsewhere.
 * A press on the button while it is open closes it: light dismiss has already closed it by the time the click
 * arrives, so the click must not open it again. It is placed once its text is in, kept on screen, and closed by
 * scrolling or resizing rather than left behind. The "?" is drawn by the styles and the text is there only while
 * shown, so neither adds to the text of what it sits beside; a polite live region says the text when it opens, which
 * a description added to a focused button would not.
 */
export function TermTip({ terms }: { terms: readonly Term[] }) {
  const button = useRef<HTMLButtonElement>(null);
  const tip = useRef<HTMLSpanElement>(null);
  const [open, setOpen] = useState(false);
  // The popover's own state as its toggle events report it, and whether it was open when the pointer went down on the
  // button, before light dismiss could close it.
  const shown = useRef(false);
  const openAtPress = useRef(false);
  const id = useId();
  const names = terms.map((term) => GLOSSARY[term].term.toLowerCase()).join(' and ');
  const text = terms.map((term) => `${GLOSSARY[term].term}: ${GLOSSARY[term].definition}`).join(' ');

  useEffect(() => {
    const element = tip.current;
    if (element === null) {
      return;
    }
    const toggled = (event: Event) => {
      shown.current = (event as ToggleEvent).newState === 'open';
      setOpen(shown.current);
    };
    element.addEventListener('toggle', toggled);
    return () => element.removeEventListener('toggle', toggled);
  }, []);

  // After the text is in, so its height is known, and before the browser paints it.
  useLayoutEffect(() => {
    const element = tip.current;
    if (!open || element === null || button.current === null) {
      return;
    }
    const place = placeTip(
      button.current.getBoundingClientRect(),
      { width: element.offsetWidth, height: element.offsetHeight },
      { width: window.innerWidth, height: window.innerHeight },
    );
    element.style.top = `${place.top}px`;
    element.style.left = `${place.left}px`;
  }, [open]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const close = () => tip.current?.hidePopover();
    window.addEventListener('scroll', close, { capture: true, passive: true });
    window.addEventListener('resize', close);
    return () => {
      window.removeEventListener('scroll', close, { capture: true });
      window.removeEventListener('resize', close);
    };
  }, [open]);

  return (
    <span className="term-tip">
      <button
        ref={button}
        type="button"
        className="term-tip-button"
        aria-label={`What ${terms.length > 1 ? 'are' : 'is'} ${names}?`}
        aria-expanded={open}
        aria-controls={id}
        onPointerDown={() => {
          openAtPress.current = shown.current;
        }}
        onClick={() => {
          if (openAtPress.current) {
            openAtPress.current = false;
            tip.current?.hidePopover();
          } else {
            tip.current?.togglePopover();
          }
        }}
      />
      <span ref={tip} id={id} popover="auto" className="term-tip-text">
        {open &&
          terms.map((term) => (
            <span key={term} className="term-tip-entry">
              <strong>{GLOSSARY[term].term}:</strong> {GLOSSARY[term].definition}
            </span>
          ))}
      </span>
      <span className="visually-hidden" aria-live="polite">
        {open ? text : ''}
      </span>
    </span>
  );
}
