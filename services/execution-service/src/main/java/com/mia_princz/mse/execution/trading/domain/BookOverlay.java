package com.mia_princz.mse.execution.trading.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The changes a run of commands will make to one book, held apart from it until they commit. Each command is planned
 * against the book as the earlier commands of the run leave it: makers they filled have less left or are gone,
 * orders they rested wait behind the book's own orders at the same price, and orders they cancelled are gone. The
 * book itself does not change, so a run that is rolled back is forgotten by dropping its overlay, and once the run
 * commits, its plans and cancels are applied to the book in the same order, each at the revision it was planned at.
 *
 * <p>
 * An overlay belongs to one book at one revision. It refuses to be used once the book has changed under it.
 */
public final class BookOverlay {

	private final OrderBook book;

	private final long baseRevision;

	/** The book's own orders this run changed: what is left of each, zero once filled or cancelled. */
	private final Map<UUID, Long> left = new HashMap<>();

	/** Orders this run rested, by side and price, each price's in the order they rested. */
	private final NavigableMap<Long, List<Pending>> bids = new TreeMap<>(Comparator.reverseOrder());

	private final NavigableMap<Long, List<Pending>> asks = new TreeMap<>();

	private final Map<UUID, Pending> pending = new HashMap<>();

	/** Resting orders gained or lost per account, and in all. */
	private final Map<UUID, Integer> restingChange = new HashMap<>();

	private int sizeChange;

	/** Plans and cancels recorded, each of which will raise the book's revision by one. */
	private int changes;

	private long lastAcceptedSequence = Long.MIN_VALUE;

	/** The run's depth changes so far; kept to this overlay and added to in place. */
	private final BookDelta delta = BookDelta.none();

	BookOverlay(OrderBook book) {
		this.book = book;
		this.baseRevision = book.revision();
	}

	/** Computes what the order would do once the run so far is applied. Neither the book nor the overlay changes. */
	public MatchPlan plan(OrderRequest taker) {
		checkBook();
		return book.plan(taker, this);
	}

	/**
	 * Records a plan this overlay computed, as applying it will change the book. Plans must be recorded in the order
	 * they were planned, each before the next is planned, and exactly as they will be applied.
	 *
	 * @throws IllegalStateException if the plan was not computed from this overlay as it stands
	 */
	public void record(MatchPlan plan) {
		checkBook();
		if (plan.bookRevision() != revision()) {
			throw new IllegalStateException("Plan was computed at revision " + plan.bookRevision()
					+ " but the overlay is at " + revision());
		}
		OrderRequest taker = plan.taker();
		for (Fill fill : plan.fills()) {
			Pending rested = pending.get(fill.makerOrderId());
			long after;
			if (rested != null) {
				rested.remaining -= fill.quantity();
				after = rested.remaining;
				if (after == 0) {
					leave(rested);
				}
			}
			else {
				BookOrder order = book.find(fill.makerOrderId())
					.orElseThrow(() -> new IllegalStateException("No such maker: " + fill.makerOrderId()));
				after = left.getOrDefault(order.orderId(), order.remainingQuantity()) - fill.quantity();
				left.put(order.orderId(), after);
				if (after == 0) {
					counted(order.accountId(), -1);
				}
			}
			if (after != fill.makerRemainingAfter()) {
				throw new IllegalStateException("Plan does not match the overlay for order " + fill.makerOrderId());
			}
		}
		if (plan.rests()) {
			Pending rested = new Pending(taker.orderId(), taker.accountId(), taker.side(), taker.bound(),
					taker.acceptedSequence(), plan.remainingQuantity());
			pending.put(rested.orderId, rested);
			levels(rested.side).computeIfAbsent(rested.price.cents(), (price) -> new ArrayList<>()).add(rested);
			counted(rested.accountId, 1);
		}
		if (plan.outcome() != MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED) {
			lastAcceptedSequence = taker.acceptedSequence();
		}
		delta.accumulate(BookDelta.of(plan));
		changes++;
	}

	/**
	 * Cancels an order resting once the run so far is applied, recording it as cancelling it in the book will change
	 * the book. Returns what the order was; empty, and nothing recorded, if it is not resting.
	 */
	public Optional<BookOrder> cancel(UUID orderId) {
		Optional<BookOrder> order = find(orderId);
		order.ifPresent((resting) -> {
			Pending rested = pending.get(orderId);
			if (rested != null) {
				rested.remaining = 0;
				leave(rested);
			}
			else {
				left.put(orderId, 0L);
				counted(resting.accountId(), -1);
			}
			delta.accumulate(BookDelta.cancel(resting));
			changes++;
		});
		return order;
	}

	/** The order as it rests once the run so far is applied; empty if it is not resting then. */
	public Optional<BookOrder> find(UUID orderId) {
		checkBook();
		Pending rested = pending.get(orderId);
		if (rested != null) {
			return Optional.of(rested.snapshot());
		}
		Long remaining = left.get(orderId);
		if (remaining != null && remaining == 0) {
			return Optional.empty();
		}
		return book.find(orderId)
			.map((order) -> remaining == null ? order
					: new BookOrder(order.orderId(), order.accountId(), order.side(), order.price(), remaining,
							order.acceptedSequence()));
	}

	/** Depth of one side once the run so far and then {@code next} are applied. */
	public List<DepthLevel> depthAfter(BookDelta next, Side side, int maxLevels) {
		checkBook();
		return book.depthAfter(delta.plus(next), side, maxLevels);
	}

	/** How many more orders (or fewer, if negative) the account will have resting in this book. */
	public int restingChange(UUID accountId) {
		return restingChange.getOrDefault(accountId, 0);
	}

	/** How many more orders (or fewer) this book will hold. */
	public int sizeChange() {
		return sizeChange;
	}

	/** The revision the book will be at once the run so far is applied: the revision the next plan is pinned to. */
	public long revision() {
		return baseRevision + changes;
	}

	/** Changes recorded so far, plans and cancels together. */
	public int changes() {
		return changes;
	}

	long lastAcceptedSequence() {
		return lastAcceptedSequence;
	}

	boolean rests(UUID orderId) {
		return pending.containsKey(orderId);
	}

	/** What is left of one of the book's own orders, given what it has now. */
	long left(UUID orderId, long remaining) {
		return left.getOrDefault(orderId, remaining);
	}

	/** Orders this run rested on one side, best price first. */
	NavigableMap<Long, List<Pending>> levels(Side side) {
		return side == Side.BUY ? bids : asks;
	}

	boolean belongsTo(OrderBook other) {
		return book == other;
	}

	private void leave(Pending rested) {
		pending.remove(rested.orderId);
		List<Pending> level = levels(rested.side).get(rested.price.cents());
		level.remove(rested);
		if (level.isEmpty()) {
			levels(rested.side).remove(rested.price.cents());
		}
		counted(rested.accountId, -1);
	}

	private void counted(UUID accountId, int change) {
		restingChange.merge(accountId, change, Integer::sum);
		sizeChange += change;
	}

	private void checkBook() {
		if (book.revision() != baseRevision) {
			throw new IllegalStateException("The book changed under its overlay: it was at revision " + baseRevision
					+ " and is at " + book.revision());
		}
	}

	/** An order this run rested. */
	static final class Pending {

		final UUID orderId;

		final UUID accountId;

		final Side side;

		final Price price;

		final long acceptedSequence;

		long remaining;

		private Pending(UUID orderId, UUID accountId, Side side, Price price, long acceptedSequence, long remaining) {
			this.orderId = orderId;
			this.accountId = accountId;
			this.side = side;
			this.price = price;
			this.acceptedSequence = acceptedSequence;
			this.remaining = remaining;
		}

		BookOrder snapshot() {
			return new BookOrder(orderId, accountId, side, price, remaining, acceptedSequence);
		}

	}

}
