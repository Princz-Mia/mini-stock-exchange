package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.mia_princz.mse.execution.trading.application.BotJob;
import com.mia_princz.mse.execution.trading.domain.Money;

/** A bot job as the operator's screen reads it: its counts, its progress and an estimate of the time left. */
final class BotJobViews {

	private BotJobViews() {
	}

	record StrategyProgress(int requested, int created) {
	}

	record Strategies(StrategyProgress marketMakers, StrategyProgress noise, StrategyProgress momentum) {
	}

	/**
	 * {@code secondsLeft} is null until a part has committed since the process took the job up, and once the job no
	 * longer runs; {@code failure} is null unless it failed; {@code finishedAt} is null while it runs.
	 */
	record BotJobView(String jobId, String state, int total, int created, Strategies strategies, String cash,
			List<SetupController.HoldingResponse> holdings, int partSize, int partsDone, Instant startedAt,
			Instant finishedAt, Long secondsLeft, String failure, int resumed) {
	}

	static BotJobView of(BotJob job) {
		return new BotJobView(job.id().toString(), job.state().name(), job.total(), job.createdTotal(),
				new Strategies(new StrategyProgress(job.requested().marketMakers(), job.created().marketMakers()),
						new StrategyProgress(job.requested().noise(), job.created().noise()),
						new StrategyProgress(job.requested().momentum(), job.created().momentum())),
				new Money(job.cashCents()).format(),
				job.holdings()
					.stream()
					.map((holding) -> new SetupController.HoldingResponse(holding.instrumentId().toString(),
							holding.quantity()))
					.toList(),
				job.partSize(), job.partsDone(), job.startedAt(), job.finishedAt(),
				job.secondsLeft().isPresent() ? job.secondsLeft().getAsLong() : null, job.failure(), job.resumed());
	}

	/** "4,000 of 20,000 bots are made", for a refusal that names the running job. */
	static String progress(BotJob job) {
		return String.format(Locale.ROOT, "%,d of %,d bots are made", job.createdTotal(), job.total());
	}

}
