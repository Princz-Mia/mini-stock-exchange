package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.Posting;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan;

/**
 * One command's writes, collected while it runs and sent by {@link TradingStore#flush} just before its answer is
 * stored, one statement per kind of row. A command's statement count then no longer grows with its fills. Each kind
 * keeps the order its rows were added in; balance changes to the same balance are summed.
 */
public final class WriteSet {

	/** A resting order's fill. */
	public record MakerFill(UUID orderId, long quantity) {
	}

	/** A trade, numbered by the writer. */
	public record NewTrade(UUID id, UUID instrumentId, long tradeSequence, UUID makerOrderId, UUID takerOrderId,
			UUID buyerAccountId, UUID sellerAccountId, long priceCents, long quantity, Instant executedAt) {
	}

	/** A journal batch and its postings, which must balance; {@code tradeId} is null unless a trade caused it. */
	public record Batch(UUID id, SettlementPlan.Cause cause, UUID tradeId, List<Posting> postings) {

		public Batch {
			postings = List.copyOf(postings);
		}

	}

	/** An event for the outbox. Events keep the order they were added in, which is the order they are published. */
	public sealed interface Event permits TradeEvent, Snapshot {
	}

	/** A trade for the public feed, with its instrument's symbol. */
	public record TradeEvent(NewTrade trade, String symbol) implements Event {
	}

	/** An instrument's public state once the command commits. */
	public record Snapshot(TradingStore.MarketView view) implements Event {
	}

	private final List<MakerFill> makerFills = new ArrayList<>();

	private final List<NewTrade> trades = new ArrayList<>();

	private final List<Batch> batches = new ArrayList<>();

	private final Map<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange> balances = new LinkedHashMap<>();

	private final Set<UUID> accounts = new LinkedHashSet<>();

	private final List<Event> events = new ArrayList<>();

	public void fillMaker(UUID orderId, long quantity) {
		makerFills.add(new MakerFill(orderId, quantity));
	}

	public void trade(NewTrade trade) {
		trades.add(trade);
	}

	public void batch(SettlementPlan.Cause cause, UUID tradeId, List<Posting> postings) {
		batches.add(new Batch(UUID.randomUUID(), cause, tradeId, postings));
	}

	/** Adds to the balance's change; two changes to one balance become one. */
	public void balance(SettlementPlan.BalanceKey key, SettlementPlan.BalanceChange change) {
		balances.merge(key, change, (earlier, later) -> new SettlementPlan.BalanceChange(
				earlier.totalDelta() + later.totalDelta(), earlier.reservedDelta() + later.reservedDelta()));
	}

	/** The accounts whose version rises once for this command. */
	public void touch(Collection<UUID> accountIds) {
		accounts.addAll(accountIds);
	}

	public void tradeEvent(NewTrade trade, String symbol) {
		events.add(new TradeEvent(trade, symbol));
	}

	public void snapshot(TradingStore.MarketView view) {
		events.add(new Snapshot(view));
	}

	public List<MakerFill> makerFills() {
		return Collections.unmodifiableList(makerFills);
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

	public Set<UUID> accounts() {
		return Collections.unmodifiableSet(accounts);
	}

	public List<Event> events() {
		return Collections.unmodifiableList(events);
	}

	public boolean isEmpty() {
		return makerFills.isEmpty() && trades.isEmpty() && batches.isEmpty() && balances.isEmpty()
				&& accounts.isEmpty() && events.isEmpty();
	}

}
