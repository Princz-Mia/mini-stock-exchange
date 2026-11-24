import { useEffect, useState } from 'react';

import type { CommandOutcome } from './useCommand';

/** Whole seconds since a moment on the browser's clock, counted again every second while it shows. */
function useSecondsSince(since: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(timer);
  }, []);
  return Math.max(0, Math.floor((now - since) / 1_000));
}

/**
 * A command on its way: what it does and how long it has taken so far, so a slow exchange never looks like a page
 * that has stopped. The seconds are hidden from screen readers, which would otherwise read the line out again every
 * second; the action is said once.
 */
export function Waiting({ action, since }: { action: string; since: number }) {
  const seconds = useSecondsSince(since);
  return (
    <p role="status" className="outcome outcome-pending">
      {action}…{' '}
      <span className="num" aria-hidden="true">
        {seconds} s
      </span>
    </p>
  );
}

/** Why the outcome is not known, in words: no answer at all before the browser stopped waiting, or a busy exchange. */
export function unresolvedText(outcome: Extract<CommandOutcome<unknown>, { kind: 'unresolved' }>): string {
  if (outcome.waited === undefined) {
    return 'Not confirmed: the exchange was busy or did not answer.';
  }
  return `Not confirmed: no answer came within ${Math.round(outcome.waited / 1_000)} s, so it may or may not have applied.`;
}

/**
 * What happened to the last command, in words, with a retry when its outcome is unknown. Retrying resends the same
 * request under the same key, so it never applies twice. `action` names the command while it is on its way, as
 * "Starting the run".
 */
export function CommandStatus<T>({
  outcome,
  action,
  done,
  onRetry,
}: {
  outcome: CommandOutcome<T>;
  action: string;
  done: (result: T) => string;
  onRetry: () => void;
}) {
  switch (outcome.kind) {
    case 'idle':
      return null;
    case 'sending':
      return <Waiting action={action} since={outcome.since} />;
    case 'done':
      return (
        <p role="status" className="outcome outcome-ok">
          {done(outcome.result)}
        </p>
      );
    case 'refused':
      return (
        <p role="alert" className="outcome outcome-rejected">
          {outcome.message}
        </p>
      );
    case 'unresolved':
      return (
        <div role="alert" className="outcome outcome-pending">
          <p>{unresolvedText(outcome)} Retrying will not apply it twice.</p>
          <button type="button" className="quiet" onClick={onRetry}>
            Retry
          </button>
        </div>
      );
  }
}
