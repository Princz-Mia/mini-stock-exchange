package com.mia_princz.mse.execution.trading.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * A recorded, seeded stream of commands for one book, replayable as often as needed with identical results. It is
 * recorded against a real book so every cancel names an order that is resting at that point, and replaying it does
 * the same in-memory work the writer does for each command: plan, delta, both sides' snapshot depth, apply.
 *
 * <p>
 * The book starts with resting orders on both sides around a mid of 100.00. Then: limit orders a few ticks either
 * side of the mid (some cross and fill), cancels of resting orders, and protected market orders, from a few hundred
 * accounts so self-trade prevention happens now and then.
 */
final class MatcherWorkload {

	static final int SNAPSHOT_LEVELS = 20;

	static final int MAX_FILLS = 1_000;

	private static final long MID_CENTS = 10_000;

	sealed interface Command permits Place, Cancel {
	}

	record Place(OrderRequest request) implements Command {
	}

	record Cancel(UUID orderId) implements Command {
	}

	private final List<BookOrder> initial;

	private final List<Command> commands;

	private MatcherWorkload(List<BookOrder> initial, List<Command> commands) {
		this.initial = List.copyOf(initial);
		this.commands = List.copyOf(commands);
	}

	/** Records {@code size} commands after {@code resting} initial orders; the same seed gives the same workload. */
	static MatcherWorkload record(long seed, int resting, int size) {
		Random random = new Random(seed);
		List<UUID> accounts = new ArrayList<>();
		for (int i = 0; i < 500; i++) {
			accounts.add(new UUID(seed, i));
		}
		long sequence = 0;
		List<BookOrder> initial = new ArrayList<>();
		OrderBook book = new OrderBook(MAX_FILLS);
		for (int i = 0; i < resting; i++) {
			Side side = i % 2 == 0 ? Side.BUY : Side.SELL;
			long offset = 1 + random.nextInt(100);
			long price = side == Side.BUY ? MID_CENTS - offset : MID_CENTS + offset;
			BookOrder order = new BookOrder(new UUID(~seed, i), accounts.get(random.nextInt(accounts.size())), side,
					new Price(price), 1 + random.nextInt(50), ++sequence);
			initial.add(order);
			book.restore(order);
		}
		List<Command> commands = new ArrayList<>(size);
		List<UUID> live = new ArrayList<>(initial.stream().map(BookOrder::orderId).toList());
		for (int i = 0; i < size; i++) {
			int kind = random.nextInt(100);
			if (kind < 30 && !live.isEmpty()) {
				UUID target = live.remove(random.nextInt(live.size()));
				if (book.find(target).isPresent()) {
					commands.add(new Cancel(target));
					book.cancel(target);
					continue;
				}
			}
			Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
			OrderType type = kind >= 90 ? OrderType.MARKET_PROTECTED : OrderType.LIMIT;
			long ticks = type == OrderType.MARKET_PROTECTED ? 20 : random.nextInt(21) - 10;
			long bound = side == Side.BUY ? MID_CENTS + ticks : MID_CENTS - ticks;
			OrderRequest request = new OrderRequest(new UUID(seed ^ 0x5DEECE66DL, i),
					accounts.get(random.nextInt(accounts.size())), side, type, new Price(bound),
					new Quantity(1 + random.nextInt(50)), ++sequence);
			commands.add(new Place(request));
			MatchPlan plan = book.plan(request);
			book.apply(plan);
			if (plan.rests()) {
				live.add(request.orderId());
			}
		}
		return new MatcherWorkload(initial, commands);
	}

	/** A fresh book holding the initial orders. */
	OrderBook book() {
		OrderBook book = new OrderBook(MAX_FILLS);
		initial.forEach(book::restore);
		return book;
	}

	List<Command> commands() {
		return commands;
	}

	/**
	 * Replays every command on the book, as the writer does in memory, and returns a checksum of what it saw: the
	 * filled quantity and the snapshot depth, so the work can't be optimised away and two replays can be compared.
	 */
	long replay(OrderBook book) {
		long checksum = 0;
		for (Command command : commands) {
			BookDelta delta;
			MatchPlan plan = null;
			if (command instanceof Place place) {
				plan = book.plan(place.request());
				delta = BookDelta.of(plan);
				checksum += plan.filledQuantity();
			}
			else {
				BookOrder order = book.find(((Cancel) command).orderId()).orElseThrow();
				delta = BookDelta.cancel(order);
			}
			List<DepthLevel> bids = book.depthAfter(delta, Side.BUY, SNAPSHOT_LEVELS);
			List<DepthLevel> asks = book.depthAfter(delta, Side.SELL, SNAPSHOT_LEVELS);
			checksum = 31 * checksum + bids.size() * 7L + asks.size();
			if (!bids.isEmpty()) {
				checksum += bids.getFirst().quantity();
			}
			if (plan != null) {
				book.apply(plan);
			}
			else {
				book.cancel(((Cancel) command).orderId());
			}
		}
		return checksum;
	}

}
