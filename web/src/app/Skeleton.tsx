import type { ReactNode } from 'react';

/**
 * A bar standing in for words or a figure that has not been read yet. It is decoration only: assistive technology is
 * told instead that the panel around it is busy, and the page says once, politely, what it is loading.
 */
export function SkeletonLine({
  width = '100%',
  kind,
}: {
  width?: string | undefined;
  kind?: 'title' | 'figure' | 'row' | undefined;
}) {
  return <span className={kind === undefined ? 'skeleton' : `skeleton skeleton-${kind}`} aria-hidden="true" style={{ width }} />;
}

/** The widths the lines of a panel take in turn, so a panel of bars reads as text rather than as a grid. */
const WIDTHS = ['92%', '78%', '85%', '64%', '88%', '71%'];

/**
 * A panel drawn where a panel of the page will be, while what fills it is read: a bar for its heading and one for each
 * line or row, at the size the real one will take, so nothing moves when it comes. It has no name, so it is never
 * mistaken for the panel it stands for.
 */
export function SkeletonPanel({
  lines = 3,
  kind,
  className,
}: {
  lines?: number;
  kind?: 'row';
  className?: string;
}) {
  return (
    <div className={className === undefined ? 'panel skeleton-panel' : `panel skeleton-panel ${className}`} aria-busy="true">
      <SkeletonLine kind="title" width="40%" />
      {Array.from({ length: lines }, (_, index) => (
        <SkeletonLine key={index} kind={kind} width={WIDTHS[index % WIDTHS.length]} />
      ))}
    </div>
  );
}

/**
 * Rows of a table inside a panel that is already drawn, while they are read. Busy, with "Loading…" for assistive
 * technology in place of the bars.
 */
export function SkeletonRows({ rows = 3 }: { rows?: number }) {
  return (
    <div className="skeleton-rows" aria-busy="true">
      <span className="visually-hidden">Loading…</span>
      {Array.from({ length: rows }, (_, index) => (
        <SkeletonLine key={index} kind="row" width={WIDTHS[index % WIDTHS.length]} />
      ))}
    </div>
  );
}

/** Said once for the whole page, out of sight: the bars themselves are hidden from assistive technology. */
export function LoadingStatus({ what }: { what: string }) {
  return (
    <p className="visually-hidden" role="status">
      {what}
    </p>
  );
}

/**
 * The terminal's layout before its instrument list has come: the watchlist, the market and the ticket as bars, in their
 * places. The trader's own orders are read separately, so the page passes that panel in and it fills as they come.
 */
export function TerminalSkeleton({ orders }: { orders?: ReactNode }) {
  return (
    <div className="terminal">
      <MarketSkeleton />
      <div className="terminal-orders">{orders ?? <SkeletonPanel lines={3} kind="row" />}</div>
    </div>
  );
}

/** The parts of the terminal that wait for the instrument list, for the page to draw in its own grid. */
export function MarketSkeleton() {
  return (
    <>
      <SkeletonPanel className="watchlist" lines={5} kind="row" />
      <div className="terminal-market" aria-busy="true">
        <div className="market-header">
          <SkeletonLine kind="figure" width="5em" />
          <SkeletonLine kind="figure" width="7em" />
        </div>
        <div className="panel skeleton-panel skeleton-chart" aria-busy="true">
          <SkeletonLine kind="title" width="20%" />
        </div>
        <div className="market-split">
          <SkeletonPanel lines={5} kind="row" />
          <SkeletonPanel lines={5} kind="row" />
        </div>
      </div>
      <SkeletonPanel className="ticket" lines={7} kind="row" />
      <LoadingStatus what="Loading markets…" />
    </>
  );
}

const FIGURES = 5;

/** The portfolio's summary figures, as bars under bars for their labels. */
export function SummarySkeleton() {
  return (
    <div className="summary" aria-busy="true">
      {Array.from({ length: FIGURES }, (_, index) => (
        <div className="figure" key={index}>
          <SkeletonLine width="60%" />
          <SkeletonLine kind="figure" width="45%" />
        </div>
      ))}
    </div>
  );
}

/**
 * The portfolio's layout before its balances have come: the summary's figures and the holdings as bars. The histories
 * are read separately, so the page passes them in and each fills as its own page of rows comes.
 */
export function PortfolioSkeleton({ histories }: { histories?: ReactNode }) {
  return (
    <div className="stack">
      <SummarySkeleton />
      <SkeletonPanel lines={3} kind="row" />
      {histories ?? (
        <>
          <SkeletonPanel lines={3} kind="row" />
          <SkeletonPanel lines={3} kind="row" />
        </>
      )}
      <LoadingStatus what="Loading portfolio…" />
    </div>
  );
}

/** The operator page's layout before the market's status has come: the market card beside the setup panels. */
export function OperatorSkeleton() {
  return (
    <div className="operator">
      <div className="operator-side">
        <SkeletonPanel lines={5} />
      </div>
      <div className="operator-setup">
        <SkeletonPanel lines={4} kind="row" />
        <SkeletonPanel lines={4} kind="row" />
        <SkeletonPanel lines={3} kind="row" />
      </div>
      <LoadingStatus what="Loading the market…" />
    </div>
  );
}

/** The page a path will show, before anything is known of who is signed in; a page with no layout of its own gets panels. */
export function PageSkeleton({ path }: { path: string }) {
  if (path.startsWith('/terminal')) {
    return <TerminalSkeleton />;
  }
  if (path.startsWith('/portfolio')) {
    return <PortfolioSkeleton />;
  }
  if (path === '/operator') {
    return <OperatorSkeleton />;
  }
  return (
    <div className="stack">
      <SkeletonPanel lines={4} />
      <SkeletonPanel lines={3} />
      <LoadingStatus what="Loading…" />
    </div>
  );
}
