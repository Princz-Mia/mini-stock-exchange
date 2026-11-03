package com.mia_princz.mse.execution.trading.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The journal batches one command produces, in the order they are written. Each batch sums to zero for each asset.
 * {@link #balanceChanges()} turns them into the deltas for the customer balance rows.
 */
public record SettlementPlan(List<Batch> batches) {

	public enum Cause {

		RESERVE, TRADE, RELEASE

	}

	/** {@code fillIndex} points into the match plan's fills for a trade batch, and is -1 otherwise. */
	public record Batch(Cause cause, int fillIndex, List<Posting> postings) {

		public Batch {
			postings = List.copyOf(postings);
		}

	}

	/** The key of one balance row: an account's cash when {@code instrumentId} is null, its shares otherwise. */
	public record BalanceKey(UUID accountId, UUID instrumentId) {
	}

	public record BalanceChange(long totalDelta, long reservedDelta) {
	}

	public SettlementPlan {
		batches = List.copyOf(batches);
	}

	public static SettlementPlan none() {
		return new SettlementPlan(List.of());
	}

	/** A delta in either bucket changes the total; only reserved deltas change the reserved amount. */
	public Map<BalanceKey, BalanceChange> balanceChanges() {
		Map<BalanceKey, long[]> sums = new LinkedHashMap<>();
		for (Batch batch : batches) {
			for (Posting posting : batch.postings()) {
				long[] sum = sums.computeIfAbsent(new BalanceKey(posting.accountId(), posting.instrumentId()),
						(key) -> new long[2]);
				sum[0] = Math.addExact(sum[0], posting.delta());
				if (posting.bucket() == Posting.Bucket.RESERVED) {
					sum[1] = Math.addExact(sum[1], posting.delta());
				}
			}
		}
		Map<BalanceKey, BalanceChange> changes = new LinkedHashMap<>();
		sums.forEach((key, sum) -> changes.put(key, new BalanceChange(sum[0], sum[1])));
		return changes;
	}

	public List<Posting> postings() {
		List<Posting> all = new ArrayList<>();
		batches.forEach((batch) -> all.addAll(batch.postings()));
		return all;
	}

}
