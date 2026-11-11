import { type RefObject, useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router';

/**
 * Help in the top bar: a button that shows a short list of what help there is. A disclosure rather than an ARIA menu,
 * so it needs no special keys: Tab moves through it, and Escape, a click elsewhere, focus moving away or a new page
 * closes it. The button is the caller's, which gives focus back to it when the tour ends.
 */
export function HelpMenu({ onTour, button }: { onTour: () => void; button: RefObject<HTMLButtonElement | null> }) {
  const [open, setOpen] = useState(false);
  const area = useRef<HTMLDivElement>(null);
  const id = useId();
  const { pathname } = useLocation();
  const [shownOn, setShownOn] = useState(pathname);
  if (shownOn !== pathname) {
    setShownOn(pathname);
    setOpen(false);
  }

  useEffect(() => {
    if (!open) {
      return;
    }
    const outside = (event: MouseEvent) => {
      if (area.current !== null && !area.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', outside);
    return () => document.removeEventListener('mousedown', outside);
  }, [open]);

  return (
    <div
      className="help"
      ref={area}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && open) {
          setOpen(false);
          button.current?.focus();
        }
      }}
      onBlur={(event) => {
        if (open && !area.current?.contains(event.relatedTarget as Node | null)) {
          setOpen(false);
        }
      }}
    >
      <button
        ref={button}
        type="button"
        className="quiet"
        aria-expanded={open}
        aria-controls={open ? `${id}-list` : undefined}
        onClick={() => setOpen(!open)}
      >
        Help
      </button>
      {open && (
        <ul id={`${id}-list`} className="help-list">
          <li>
            <button
              type="button"
              className="quiet"
              onClick={() => {
                setOpen(false);
                // Focus waits on Help, so it comes back there when the tour ends.
                button.current?.focus();
                onTour();
              }}
            >
              Take the tour
            </button>
          </li>
          <li>
            <Link to="/help" onClick={() => setOpen(false)}>
              How this exchange works
            </Link>
          </li>
        </ul>
      )}
    </div>
  );
}
