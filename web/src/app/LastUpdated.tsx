import { utcTime } from '../format';

/**
 * "Last updated 12:00:05 UTC, retrying": said beside what a page still shows after a read of it failed, so the page
 * stays, and whatever is being typed on it, rather than giving way to an error. Times are in UTC, as everywhere else on
 * the screens; this one is the browser's own clock, as it is when the browser last heard back.
 */
export function lastUpdatedText(updatedAt: number, retrying: boolean): string {
  const time = utcTime(new Date(updatedAt).toISOString());
  return retrying ? `Last updated ${time}, retrying` : `Last updated ${time}`;
}

/** The same, as a quiet status beside the feed's: polite, so it is announced without taking the user's focus. */
export function LastUpdated({ updatedAt, retrying }: { updatedAt: number; retrying: boolean }) {
  return (
    <span className="feed last-updated" role="status">
      {lastUpdatedText(updatedAt, retrying)}
    </span>
  );
}
