package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.Posting;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan;

/**
 * A transaction's writes, collected while its commands run and sent by {@link TradingStore#flush} once, just before
 * the commit: one statement per kind of row, whatever the number of commands, fills or events (and one more for each
 * balance that has no row yet, rare after an account's first trades). Each kind keeps the
 * order its rows were added in, which is the order of the commands that added them; each row carries its own
 * command's sequence. Changes to one row by several commands are summed: a resting order's fills, a balance's
 * changes, an account's version and an instrument's trade counter.
 */
public final class WriteSet {

	/** A resting order's fills in this transaction: the quantity they took, and how many commands filled it. */
	public record MakerFill(UUID orderId, long quantity, int fills) {
	}

	/**
	 * A resting order cancelled, by its owner or by the market's close, with exactly {@code quantity} working when it
	 * was.
	 */
	public record Cancel(UUID orderId, UUID accountId, long quantity, String reason) {
	}

	/** An instrument's trade counter moved from {@code from} to {@code to}. */
	public record TradeSequence(UUID instrumentId, long from, long to) {
	}

	/** A trade, numbered by the writer. */
	public record NewTrade(UUID id, UUID instrumentId, long tradeSequence, UUID makerOrderId, UUID takerOrderId,
			UUID buyerAccountId, UUID sellerAccountId, long priceCents, long quantity, Instant executedAt,
			long commandSequence) {
	}

	/** A journal batch and its postings, which must balance; {@code tradeId} is null unless a trade caused it. */
	public record Batch(UUID id, SettlementPlan.Cause cause, UUID tradeId, long commandSequence,
			List<Posting> postings) {

		public Batch {
			postings = List.copyOf(postings);
		}

	}

	/** A command's first committed answer, stored so that a retry with the same key gets it back. */
	public record Result(CommandKey key, int httpStatus, Object result, long commandSequence) {
	}

	/** An event for the outbox. Events keep the order they were added in, which is the order they are published. */
	public sealed interface Event permits TradeEvent, Snapshot {

		long commandSequence();

	}

	/** A trade for the public feed, with its instrument's symbol. */
	public record TradeEvent(NewTrade trade, String symbol) implements Event {

		@Override
		public long commandSequence() {
			return trade.commandSequence();
		}

	}

	/** An instrument's public state once its command commits. */
	public record Snapshot(TradingStore.MarketView view) implements Event {

		@Override
		public long commandSequence() {
			return view.commandSequence();
		}

	}

	private final List<TradingStore.NewOrder> orders = new ArrayList<>();

	private final Map<UUID, MakerFill> makerFills = new LinkedHashMap<>();

	private final List<Cancel> cancels = new ArrayList<>();

	private final Map<UUID, TradeSequence> tradeSequences = new LinkedHashMap<>();

	private final List<NewTrade> trades = new ArrayList<>();

	private final List<Batch> batches = new ArrayList<>();

	private final Map<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange> balances = new LinkedHashMap<>();

	private final Map<UUID, Integer> accounts = new LinkedHashMap<>();

	private final List<Event> events = new ArrayList<>();

	private final List<Result> results = new ArrayList<>();

	public void order(TradingStore.NewOrder order) {
		orders.add(order);
	}

	/**
	 * Adds a fill to the order's fills in this transaction. A command fills a resting order at most once, so its
	 * version rises once for each command that fills it, as it did when each command wrote its own fills.
	 */
	public void fillMaker(UUID orderId, long quantity) {
		makerFills.merge(orderId, new MakerFill(orderId, quantity, 1),
				(earlier, later) -> new MakerFill(orderId, earlier.quantity() + later.quantity(), earlier.fills() + 1));
	}

	/** The order is cancelled after this transaction's fills of it, which can only come before the cancel. */
	public void cancel(UUID orderId, UUID accountId, long quantity, String reason) {
		cancels.add(new Cancel(orderId, accountId, quantity, reason));
	}

	/**
	 * Moves the instrument's trade counter on to {@code to}; the first move in the transaction says where it starts,
	 * and each later one must start where the one before ended.
	 */
	public void tradeSequence(UUID instrumentId, long from, long to) {
		tradeSequences.merge(instrumentId, new TradeSequence(instrumentId, from, to), (earlier, later) -> {
			if (later.from() != earlier.to()) {
				throw new IllegalStateException("Trades for " + instrumentId + " numbered from " + later.from()
						+ " after " + earlier.to());
			}
			return new TradeSequence(instrumentId, earlier.from(), later.to());
		});
	}

	public void trade(NewTrade trade) {
		trades.add(trade);
	}

	public void batch(SettlementPlan.Cause cause, UUID tradeId, long commandSequence, List<Posting> postings) {
		batches.add(new Batch(UUID.randomUUID(), cause, tradeId, commandSequence, postings));
	}

	/** Adds to the balance's change; changes to one balance become one. */
	public void balance(SettlementPlan.BalanceKey key, SettlementPlan.BalanceChange change) {
		balances.merge(key, change, (earlier, later) -> new SettlementPlan.BalanceChange(
				earlier.totalDelta() + later.totalDelta(), earlier.reservedDelta() + later.reservedDelta()));
	}

	/** The accounts one command changed: each one's version rises once for it. */
	public void touch(Collection<UUID> accountIds) {
		for (UUID account : new java.util.LinkedHashSet<>(accountIds)) {
			accounts.merge(account, 1, Integer::sum);
		}
	}

	public void tradeEvent(NewTrade trade, String symbol) {
		events.add(new TradeEvent(trade, symbol));
	}

	public void snapshot(TradingStore.MarketView view) {
		events.add(new Snapshot(view));
	}

	public void result(CommandKey key, int httpStatus, Object result, long commandSequence) {
		results.add(new Result(key, httpStatus, result, commandSequence));
	}

	/** How much more (or less) of the balance is available once this transaction's changes so far are applied. */
	public long availableChange(SettlementPlan.BalanceKey key) {
		SettlementPlan.BalanceChange change = balances.get(key);
		return change == null ? 0 : change.totalDelta() - change.reservedDelta();
	}

	public List<TradingStore.NewOrder> orders() {
		return Collections.unmodifiableList(orders);
	}

	public List<MakerFill> makerFills() {
		return List.copyOf(makerFills.values());
	}

	public List<Cancel> cancels() {
		return Collections.unmodifiableList(cancels);
	}

	public List<TradeSequence> tradeSequences() {
		return List.copyOf(tradeSequences.values());
	}

	public List<NewTrade> trades() {
		return Collections.unmodifiableList(trades);
	}

	public List<Batch> batches() {
		return Collections.unmodifiableList(batches);
	}

	public Map<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange> balances() {
		return Collections.unmodifiableMap(balances);
	}

	/** Each account changed, with how many commands changed it. */
	public Map<UUID, Integer> accounts() {
		return Collections.unmodifiableMap(accounts);
	}

	public List<Event> events() {
		return Collections.unmodifiableList(events);
	}

	public List<Result> results() {
		return Collections.unmodifiableList(results);
	}

	public boolean isEmpty() {
		return orders.isEmpty() && makerFills.isEmpty() && cancels.isEmpty() && tradeSequences.isEmpty()
				&& trades.isEmpty() && batches.isEmpty() && balances.isEmpty() && accounts.isEmpty()
				&& events.isEmpty() && results.isEmpty();
	}

}
