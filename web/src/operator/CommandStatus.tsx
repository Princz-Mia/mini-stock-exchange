import type { CommandOutcome } from './useCommand';

/**
 * What happened to the last command, in words, with a retry when its outcome is unknown. Retrying resends the same
 * request under the same key, so it never applies twice.
 */
export function CommandStatus<T>({
  outcome,
  done,
  onRetry,
}: {
  outcome: CommandOutcome<T>;
  done: (result: T) => string;
  onRetry: () => void;
}) {
  switch (outcome.kind) {
    case 'idle':
      return null;
    case 'sending':
      return (
        <p role="status" className="outcome outcome-pending">
          Sending…
        </p>
      );
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
          <p>Not confirmed: the exchange was busy or did not answer. Retrying will not apply it twice.</p>
          <button type="button" className="quiet" onClick={onRetry}>
            Retry
          </button>
        </div>
      );
  }
}
