import { useId, useLayoutEffect, useRef } from 'react';

/**
 * Asks before the market is closed, saying what a close does and that it cannot be undone. A modal dialog: the
 * keyboard stays in it, Escape leaves the market as it is, and the browser hands focus back to the button it was
 * opened from. Focus starts on the button that changes nothing, so Enter or Space pressed too soon does not close a
 * market.
 */
export function CloseMarketDialog({ onClose, onKeep }: { onClose: () => void; onKeep: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const keep = useRef<HTMLButtonElement>(null);
  const id = useId();

  // A layout effect, so the dialog closes before React removes it and the browser can hand focus back.
  useLayoutEffect(() => {
    const element = dialog.current;
    if (element !== null && !element.open) {
      element.showModal();
    }
    keep.current?.focus();
    return () => {
      if (element?.open) {
        element.close();
      }
    };
  }, []);

  return (
    <dialog
      ref={dialog}
      className="confirm"
      role="alertdialog"
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-body`}
      onCancel={(event) => {
        // Escape: the same as the button that keeps the market.
        event.preventDefault();
        onKeep();
      }}
    >
      <h2 id={`${id}-title`}>Close the market for good?</h2>
      <div id={`${id}-body`} className="confirm-body">
        <p>
          <strong>This cannot be undone.</strong> A closed market never opens again.
        </p>
        <ul>
          <li>Trading ends for good: no order is taken after the close.</li>
          <li>Every resting order is cancelled, and what it had reserved is released.</li>
          <li>Every bot run ends.</li>
          <li>Every bot&apos;s token stops working.</li>
        </ul>
        <p>
          Nothing is deleted: portfolios, orders, trades, accounts and bot runs stay readable. On a full market the close
          can take up to about half a minute.
        </p>
      </div>
      <div className="confirm-actions">
        <button ref={keep} type="button" onClick={onKeep}>
          Do not close
        </button>
        <button type="button" className="primary danger" onClick={onClose}>
          Close the market for good
        </button>
      </div>
    </dialog>
  );
}
