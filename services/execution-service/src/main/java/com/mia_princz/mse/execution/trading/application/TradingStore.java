package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.Fill;
import com.mia_princz.mse.execution.trading.domain.OrderRequest;
import com.mia_princz.mse.execution.trading.domain.Posting;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan;

/** What the engine needs from the database. Every method runs inside the caller's transaction. */
public interface TradingStore {

	record Market(UUID simulationId, String state, long writerEpoch, int maxOpenOrdersPerAccount,
			int maxOpenOrdersTotal, int maxFillsPerCommand) {
	}

	record Instrument(UUID id, String symbol) {
	}

	record Available(long cashCents, long shares) {
	}

	/** A new order row. Rejected orders have no accepted sequence. */
	record NewOrder(UUID id, UUID simulationId, OrderRequest request, UUID instrumentId,
			PlacementResult.OrderState state, String reason, long filled, long working, long canceled,
			Long acceptedSequence) {
	}

	record Trade(UUID id, long tradeSequence) {
	}

	/** An order row as cancelling needs it, locked for the rest of the transaction. */
	record ExistingOrder(UUID id, UUID accountId, UUID instrumentId, String state, long working) {
	}

	/** Locks the market row for the rest of the transaction; every trading command starts here. */
	Market lockMarket();

	long nextCommandSequence(UUID simulationId);

	List<Instrument> instruments(UUID simulationId);

	Available available(UUID accountId, UUID instrumentId);

	int openOrders(UUID accountId);

	int openOrdersTotal(UUID simulationId);

	void insertOrder(NewOrder order);

	void recordMakerFill(UUID makerOrderId, long quantity);

	Optional<ExistingOrder> lockOrder(UUID orderId);

	void cancelOrder(UUID orderId, long quantity, String reason);

	Trade insertTrade(UUID simulationId, UUID instrumentId, OrderRequest taker, Fill fill, Instant executedAt,
			long commandSequence);

	void insertBatch(UUID simulationId, SettlementPlan.Cause cause, UUID tradeId, long commandSequence,
			List<Posting> postings);

	void applyBalance(SettlementPlan.BalanceKey key, SettlementPlan.BalanceChange change);

	void touchAccounts(Collection<UUID> accountIds);

	void insertTradeEvent(UUID simulationId, UUID instrumentId, String symbol, Trade trade, Fill fill,
			Instant executedAt, long commandSequence);

}
