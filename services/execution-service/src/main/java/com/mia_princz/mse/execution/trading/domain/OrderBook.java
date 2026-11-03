package com.mia_princz.mse.execution.trading.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Price-time priority book for one instrument. Not thread-safe: the single writer is its only user.
 *
 * <p>
 * Each side is a sorted map of price levels; each level is a FIFO doubly linked list of orders with an aggregate
 * quantity, and every order is also indexed by ID. Inserting is O(log P), cancelling a known order O(1) plus O(log P)
 * when its level empties, and matching O(F + E log P) for F fills and E emptied levels.
 */
public final class OrderBook {

	private final int maxFillsPerCommand;

	private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());

	private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();

	private final Map<UUID, OrderNode> orders = new HashMap<>();

	private long revision;

	private long lastAcceptedSequence = Long.MIN_VALUE;

	public OrderBook(int maxFillsPerCommand) {
		if (maxFillsPerCommand < 1) {
			throw new IllegalArgumentException("maxFillsPerCommand must be positive");
		}
		this.maxFillsPerCommand = maxFillsPerCommand;
	}

	/** Computes what the order would do. The book is not changed. */
	public MatchPlan plan(OrderRequest taker) {
		if (orders.containsKey(taker.orderId())) {
			throw new IllegalArgumentException("Order is already in the book: " + taker.orderId());
		}
		if (taker.acceptedSequence() <= lastAcceptedSequence) {
			throw new IllegalArgumentException("Accepted sequence must increase");
		}
		List<Fill> fills = new ArrayList<>();
		long remaining = taker.quantity().shares();
		MatchPlan.Outcome stopped = null;
		levels: for (PriceLevel level : levels(taker.side().opposite()).values()) {
			if (!taker.crosses(level.priceCents)) {
				break;
			}
			for (OrderNode maker = level.head; maker != null; maker = maker.next) {
				if (maker.accountId.equals(taker.accountId())) {
					stopped = MatchPlan.Outcome.SELF_TRADE_PREVENTED;
					break levels;
				}
				if (fills.size() == maxFillsPerCommand) {
					return new MatchPlan(taker, revision, List.of(), MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED);
				}
				long quantity = Math.min(remaining, maker.remaining);
				fills.add(new Fill(maker.orderId, maker.accountId, maker.price, quantity, maker.remaining - quantity));
				remaining -= quantity;
				if (remaining == 0) {
					break levels;
				}
			}
		}
		return new MatchPlan(taker, revision, fills, outcome(taker, remaining, stopped));
	}

	private static MatchPlan.Outcome outcome(OrderRequest taker, long remaining, MatchPlan.Outcome stopped) {
		if (remaining == 0) {
			return MatchPlan.Outcome.FILLED;
		}
		if (stopped != null) {
			return stopped;
		}
		return taker.type() == OrderType.LIMIT ? MatchPlan.Outcome.RESTS : MatchPlan.Outcome.IOC_REMAINDER_CANCELED;
	}

	/** Applies a plan computed from this exact book state. */
	public void apply(MatchPlan plan) {
		if (plan.bookRevision() != revision) {
			throw new IllegalStateException("Plan was computed from book revision " + plan.bookRevision()
					+ " but the book is at " + revision);
		}
		OrderRequest taker = plan.taker();
		for (Fill fill : plan.fills()) {
			OrderNode maker = orders.get(fill.makerOrderId());
			maker.remaining -= fill.quantity();
			maker.level.quantity -= fill.quantity();
			if (maker.remaining != fill.makerRemainingAfter()) {
				throw new IllegalStateException("Plan does not match the book for order " + fill.makerOrderId());
			}
			if (maker.remaining == 0) {
				remove(maker);
			}
		}
		if (plan.rests()) {
			insert(new OrderNode(taker.orderId(), taker.accountId(), taker.side(), taker.bound(),
					plan.remainingQuantity(), taker.acceptedSequence()));
		}
		if (plan.outcome() != MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED) {
			lastAcceptedSequence = taker.acceptedSequence();
		}
		revision++;
	}

	/**
	 * Puts back a resting order loaded from the database, without matching. Orders must arrive in accepted-sequence
	 * order, which rebuilds each price level's queue exactly as it was.
	 */
	public void restore(BookOrder order) {
		if (orders.containsKey(order.orderId())) {
			throw new IllegalArgumentException("Order is already in the book: " + order.orderId());
		}
		if (order.acceptedSequence() <= lastAcceptedSequence) {
			throw new IllegalArgumentException("Orders must be restored in accepted-sequence order");
		}
		if (order.remainingQuantity() <= 0) {
			throw new IllegalArgumentException("A resting order needs a positive remaining quantity");
		}
		insert(new OrderNode(order.orderId(), order.accountId(), order.side(), order.price(), order.remainingQuantity(),
				order.acceptedSequence()));
		lastAcceptedSequence = order.acceptedSequence();
		revision++;
	}

	/** A crossed book has a bid at or above an ask, which matching never leaves behind. */
	public boolean isCrossed() {
		return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey();
	}

	/** Removes a resting order, returning what it was. Empty if the order is not resting. */
	public Optional<BookOrder> cancel(UUID orderId) {
		OrderNode node = orders.get(orderId);
		if (node == null) {
			return Optional.empty();
		}
		remove(node);
		revision++;
		return Optional.of(node.snapshot());
	}

	public Optional<BookOrder> find(UUID orderId) {
		return Optional.ofNullable(orders.get(orderId)).map(OrderNode::snapshot);
	}

	/** Aggregated levels in priority order, at most {@code maxLevels} of them. */
	public List<DepthLevel> depth(Side side, int maxLevels) {
		List<DepthLevel> depth = new ArrayList<>(Math.min(maxLevels, levels(side).size()));
		for (PriceLevel level : levels(side).values()) {
			if (depth.size() == maxLevels) {
				break;
			}
			depth.add(new DepthLevel(new Price(level.priceCents), level.quantity, level.orderCount));
		}
		return Collections.unmodifiableList(depth);
	}

	/**
	 * Depth of one side as it will be once {@code delta} is applied, without applying it. Walks the book's levels
	 * and the delta's prices together in priority order, so it costs O(D + R + C) for D levels returned, R levels
	 * the delta empties, and C changed prices.
	 *
	 * @throws IllegalStateException if the delta does not fit the book, such as removing more than a level holds
	 */
	public List<DepthLevel> depthAfter(BookDelta delta, Side side, int maxLevels) {
		NavigableMap<Long, PriceLevel> levels = levels(side);
		// Bids sort best (highest) first; asks use natural order, for which comparator() would be null.
		Comparator<Long> order = side == Side.BUY ? Comparator.reverseOrder() : Comparator.naturalOrder();
		Iterator<PriceLevel> resting = levels.values().iterator();
		Iterator<Map.Entry<Long, BookDelta.Change>> changes = delta.changes(side).entrySet().iterator();
		PriceLevel level = resting.hasNext() ? resting.next() : null;
		Map.Entry<Long, BookDelta.Change> change = changes.hasNext() ? changes.next() : null;
		List<DepthLevel> depth = new ArrayList<>(maxLevels);
		while (depth.size() < maxLevels && (level != null || change != null)) {
			int position = level == null ? 1 : change == null ? -1 : order.compare(level.priceCents, change.getKey());
			long price = position <= 0 ? level.priceCents : change.getKey();
			long quantity = position <= 0 ? level.quantity : 0;
			int count = position <= 0 ? level.orderCount : 0;
			if (position >= 0) {
				quantity = Math.addExact(quantity, change.getValue().quantity());
				count += change.getValue().orders();
				change = changes.hasNext() ? changes.next() : null;
			}
			if (position <= 0) {
				level = resting.hasNext() ? resting.next() : null;
			}
			if (quantity < 0 || count < 0 || (quantity == 0) != (count == 0)) {
				throw new IllegalStateException("Delta does not fit the book at " + price + ": " + quantity
						+ " shares in " + count + " orders");
			}
			if (quantity > 0) {
				depth.add(new DepthLevel(new Price(price), quantity, count));
			}
		}
		return Collections.unmodifiableList(depth);
	}

	/** The resting orders of one side in priority order. Linear in the book size; for tests and diagnostics. */
	public List<BookOrder> orders(Side side) {
		List<BookOrder> result = new ArrayList<>();
		for (PriceLevel level : levels(side).values()) {
			for (OrderNode node = level.head; node != null; node = node.next) {
				result.add(node.snapshot());
			}
		}
		return result;
	}

	public int size() {
		return orders.size();
	}

	public long revision() {
		return revision;
	}

	private NavigableMap<Long, PriceLevel> levels(Side side) {
		return side == Side.BUY ? bids : asks;
	}

	private void insert(OrderNode node) {
		PriceLevel level = levels(node.side).computeIfAbsent(node.price.cents(), PriceLevel::new);
		node.level = level;
		node.prev = level.tail;
		if (level.tail == null) {
			level.head = node;
		}
		else {
			level.tail.next = node;
		}
		level.tail = node;
		level.quantity += node.remaining;
		level.orderCount++;
		orders.put(node.orderId, node);
	}

	private void remove(OrderNode node) {
		PriceLevel level = node.level;
		if (node.prev == null) {
			level.head = node.next;
		}
		else {
			node.prev.next = node.next;
		}
		if (node.next == null) {
			level.tail = node.prev;
		}
		else {
			node.next.prev = node.prev;
		}
		level.quantity -= node.remaining;
		level.orderCount--;
		if (level.head == null) {
			levels(node.side).remove(level.priceCents);
		}
		orders.remove(node.orderId);
	}

	private static final class PriceLevel {

		private final long priceCents;

		private OrderNode head;

		private OrderNode tail;

		private long quantity;

		private int orderCount;

		private PriceLevel(long priceCents) {
			this.priceCents = priceCents;
		}

	}

	private static final class OrderNode {

		private final UUID orderId;

		private final UUID accountId;

		private final Side side;

		private final Price price;

		private final long acceptedSequence;

		private long remaining;

		private PriceLevel level;

		private OrderNode prev;

		private OrderNode next;

		private OrderNode(UUID orderId, UUID accountId, Side side, Price price, long remaining, long acceptedSequence) {
			this.orderId = Objects.requireNonNull(orderId);
			this.accountId = Objects.requireNonNull(accountId);
			this.side = side;
			this.price = price;
			this.remaining = remaining;
			this.acceptedSequence = acceptedSequence;
		}

		private BookOrder snapshot() {
			return new BookOrder(orderId, accountId, side, price, remaining, acceptedSequence);
		}

	}

}
