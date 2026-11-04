import type { Feed } from '../api/types';

const LABELS: Record<Feed['state'], string> = {
  LIVE: 'Live',
  CATCHING_UP: 'Catching up',
  STARTING: 'Connecting',
  STALE: 'Stale',
  PAUSED: 'Paused',
  DISABLED: 'Offline',
};

/** How current public data is. Anything but live is shown plainly, with text, not colour alone. */
export function FeedStatus({ feed }: { feed: Feed }) {
  const behind = feed.state !== 'LIVE' && feed.lag > 0 ? ` · ${feed.lag} behind` : '';
  return (
    <span className={`feed feed-${feed.state.toLowerCase()}`} role="status">
      Market data: {LABELS[feed.state]}
      {behind}
    </span>
  );
}
