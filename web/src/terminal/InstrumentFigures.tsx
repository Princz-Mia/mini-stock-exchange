import type { ReactNode } from 'react';

import type { SessionFigures } from '../api/types';
import { FEED_LABELS } from '../app/FeedStatus';
import { groupDigits, groupInteger } from '../format';
import { Moved } from './Moved';
import { direction, signed, spread } from './session';

/**
 * The selected instrument's figures since trading began, above the chart: the change against the reference price with
 * a sign and an arrow, the best bid and ask and the spread between them, the high and low, the volume and the VWAP.
 * A dash stands for what is not known yet: no trade, or no order on one side. The figures come from the trade feed,
 * so while it is not live they say so, even when the book is current.
 */
export function InstrumentFigures({
  session,
  bid,
  ask,
}: {
  session: SessionFigures;
  bid: string | null;
  ask: string | null;
}) {
  const way = direction(session);
  const price = (value: string | null) => (value === null ? '—' : groupDigits(value));
  const figures: [string, ReactNode][] = [
    [
      'Change',
      session.change === null || session.changePercent === null || way === null ? (
        '—'
      ) : (
        <Moved way={way}>
          {signed(session.change)} ({signed(session.changePercent)}%)
        </Moved>
      ),
    ],
    ['Bid', price(bid)],
    ['Ask', price(ask)],
    ['Spread', price(spread(bid, ask))],
    ['High', price(session.high)],
    ['Low', price(session.low)],
    ['Volume', groupInteger(session.volume)],
    ['VWAP', price(session.vwap)],
    ['Reference', groupDigits(session.open)],
  ];
  return (
    <div className="instrument-figures-wrap">
      <dl className="instrument-figures">
        {figures.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd className="num">{value}</dd>
          </div>
        ))}
      </dl>
      {session.feed.state !== 'LIVE' && (
        <p className="feed instrument-figures-feed" role="status">
          Figures: {FEED_LABELS[session.feed.state]}
          {session.feed.lag > 0 ? ` · ${groupInteger(session.feed.lag)} behind` : ''}
        </p>
      )}
    </div>
  );
}
