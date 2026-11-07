import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';

import type { Instrument, Order, OrderRequest, Portfolio } from '../api/types';
import { groupDigits, groupInteger } from '../format';
import { isPrice, maximumSpend } from './money';
import { isUnresolved, type Outcome, useOrderIntent } from './orderIntent';

const Ticket = z.object({
  side: z.enum(['BUY', 'SELL']),
  type: z.enum(['LIMIT', 'MARKET_PROTECTED']),
  quantity: z.coerce.number().int('Whole shares only').min(1, 'At least 1 share').max(10_000, 'At most 10,000 shares'),
  price: z.string().refine(isPrice, 'Use a price with two decimals, such as 100.25'),
});
type TicketInput = z.input<typeof Ticket>;
type TicketValues = z.output<typeof Ticket>;

const REJECTIONS: Record<string, string> = {
  INSUFFICIENT_CASH: 'Not enough available cash for this order at its price limit.',
  INSUFFICIENT_SHARES: 'Not enough available shares to sell.',
  MARKET_NOT_OPEN: 'The market is not open.',
  ORDER_LIMIT_EXCEEDED: 'You already have the maximum number of open orders.',
  EXECUTION_LIMIT_EXCEEDED: 'This order would trade against too many orders at once.',
};

function toRequest(instrumentId: string, values: TicketValues): OrderRequest {
  return values.type === 'LIMIT'
    ? { instrumentId, side: values.side, type: 'LIMIT', timeInForce: 'GTC', quantity: values.quantity, limitPrice: values.price }
    : {
        instrumentId,
        side: values.side,
        type: 'MARKET_PROTECTED',
        timeInForce: 'IOC',
        quantity: values.quantity,
        protectionPrice: values.price,
      };
}

/** What the exchange did with the order, in words: execution is not the same as submission. */
function describeAccepted(order: Order): string {
  if (order.state === 'FILLED') {
    return `Filled: ${groupInteger(order.filledQuantity)} shares.`;
  }
  if (order.state === 'OPEN') {
    return `Accepted and resting: ${groupInteger(order.workingQuantity)} shares working, none filled yet.`;
  }
  if (order.state === 'PARTIALLY_FILLED') {
    return `Partly filled: ${groupInteger(order.filledQuantity)} filled, ${groupInteger(order.workingQuantity)} still working.`;
  }
  return `Filled ${groupInteger(order.filledQuantity)}; the remaining ${groupInteger(order.canceledQuantity)} were canceled (${order.reason ?? 'canceled'}).`;
}

function OutcomeMessage({ outcome }: { outcome: Outcome }) {
  switch (outcome.kind) {
    case 'accepted':
      return (
        <p role="status" className="outcome outcome-ok">
          {describeAccepted(outcome.order)}
        </p>
      );
    case 'rejected':
      return (
        <p role="alert" className="outcome outcome-rejected">
          Rejected: {REJECTIONS[outcome.code] ?? outcome.code}
        </p>
      );
    case 'invalid':
      return (
        <p role="alert" className="outcome outcome-rejected">
          {outcome.code === 'UNKNOWN_INSTRUMENT'
            ? 'This instrument is not known to the exchange.'
            : `Not accepted: ${outcome.errors.map((error) => error.message).join(' ') || outcome.code}`}
        </p>
      );
    case 'busy':
      return (
        <p role="alert" className="outcome outcome-pending">
          The exchange is busy and did not take the order. Retry sends the same order once.
        </p>
      );
    case 'limited':
      return (
        <p role="alert" className="outcome outcome-pending">
          Too many orders in a moment, so this one was not placed. Wait a second; Retry sends the same order once.
        </p>
      );
    case 'unknown':
      return (
        <p role="alert" className="outcome outcome-pending">
          The result is not known yet. Retry checks it first, so the order is never placed twice.
        </p>
      );
    case 'notCommitted':
      return (
        <p role="alert" className="outcome outcome-pending">
          The order was not placed. Retry sends it again safely.
        </p>
      );
    default:
      return null;
  }
}

export function OrderTicket({
  instrument,
  portfolio,
  paused = null,
}: {
  instrument: Instrument;
  portfolio: Portfolio | undefined;
  /** Why new orders cannot be placed now, or null while the market is open. An unresolved order can still be retried. */
  paused?: string | null;
}) {
  const client = useQueryClient();
  const intent = useOrderIntent();
  const unresolved = isUnresolved(intent.outcome);
  const sending = intent.outcome.kind === 'sending';
  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors },
  } = useForm<TicketInput, unknown, TicketValues>({
    resolver: zodResolver(Ticket),
    defaultValues: { side: 'BUY', type: 'LIMIT', quantity: 1, price: instrument.lastPrice ?? instrument.referencePrice },
  });
  const side = useWatch({ control, name: 'side' });
  const type = useWatch({ control, name: 'type' });
  const quantity = Number(useWatch({ control, name: 'quantity' }));
  const price = useWatch({ control, name: 'price' });

  useEffect(() => {
    const kind = intent.outcome.kind;
    if (kind === 'accepted' || kind === 'rejected') {
      void client.invalidateQueries({ queryKey: ['portfolio'] });
      void client.invalidateQueries({ queryKey: ['orders'] });
    }
  }, [intent.outcome, client]);

  const holding = portfolio?.holdings.find((entry) => entry.instrumentId === instrument.instrumentId);
  const estimate =
    side === 'BUY' && isPrice(price) && Number.isInteger(quantity) && quantity > 0 ? maximumSpend(price, quantity) : null;
  const submit = handleSubmit((values) => void intent.submit(toRequest(instrument.instrumentId, values)));
  const retry = () => {
    const request = intent.pendingRequest();
    if (request !== null) {
      void intent.submit(request);
    }
  };

  return (
    <section className="panel ticket" aria-labelledby="ticket-title">
      <h2 id="ticket-title">Order · {instrument.symbol}</h2>
      <div className="segmented" role="group" aria-label="Side">
        {(['BUY', 'SELL'] as const).map((choice) => (
          <button
            key={choice}
            type="button"
            aria-pressed={side === choice}
            className={side === choice ? `segment selected-${choice.toLowerCase()}` : 'segment'}
            onClick={() => setValue('side', choice)}
            disabled={unresolved || sending}
          >
            {choice === 'BUY' ? 'Buy' : 'Sell'}
          </button>
        ))}
      </div>
      <dl className="balances">
        <dt>Available cash</dt>
        <dd className="num">{portfolio === undefined ? '—' : groupDigits(portfolio.cash.available)}</dd>
        <dt>Available shares</dt>
        <dd className="num">{holding === undefined ? '0' : groupInteger(holding.available)}</dd>
      </dl>
      <form onSubmit={(event) => void submit(event)} noValidate className="ticket-form">
        <fieldset disabled={unresolved || sending}>
          <label htmlFor="ticket-type">Order type</label>
          <select id="ticket-type" {...register('type')}>
            <option value="LIMIT">Limit · good till canceled</option>
            <option value="MARKET_PROTECTED">Protected market · immediate or cancel</option>
          </select>
          <label htmlFor="ticket-quantity">Shares</label>
          <input id="ticket-quantity" inputMode="numeric" aria-invalid={errors.quantity !== undefined} {...register('quantity')} />
          {errors.quantity !== undefined && <p className="field-error">{errors.quantity.message}</p>}
          <label htmlFor="ticket-price">{type === 'LIMIT' ? 'Limit price' : 'Protection price'}</label>
          <input id="ticket-price" inputMode="decimal" aria-invalid={errors.price !== undefined} {...register('price')} />
          <p className="muted hint">
            {type === 'LIMIT'
              ? 'Trades at this price or better; the rest waits in the book.'
              : 'Trades now, never past this price; the rest is canceled.'}
          </p>
          {errors.price !== undefined && <p className="field-error">{errors.price.message}</p>}
        </fieldset>
        {estimate !== null && (
          <p className="estimate">
            Maximum spend <strong className="num">{groupDigits(estimate)}</strong>
            <span className="muted"> · reserved until the order finishes</span>
          </p>
        )}
        {unresolved ? (
          <button type="button" className="primary" onClick={retry} disabled={sending}>
            Retry the same order
          </button>
        ) : (
          <>
            {paused !== null && (
              <p id="ticket-paused" className="outcome outcome-pending">
                {paused}
              </p>
            )}
            <button
              type="submit"
              className={side === 'BUY' ? 'primary buy' : 'primary sell'}
              disabled={sending || paused !== null}
              aria-describedby={paused === null ? undefined : 'ticket-paused'}
            >
              {sending ? 'Sending…' : `${side === 'BUY' ? 'Buy' : 'Sell'} ${instrument.symbol}`}
            </button>
          </>
        )}
      </form>
      <OutcomeMessage outcome={intent.outcome} />
    </section>
  );
}
