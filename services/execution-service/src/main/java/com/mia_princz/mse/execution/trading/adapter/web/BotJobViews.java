package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

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
	 * {@code secondsLeft} is null until a part has committed since the process took the job up, once the job no
	 * longer runs, and while it is {@code stalled}; {@code failure} is null unless it failed; {@code finishedAt} is
	 * null while it runs. {@code lastProgressAt} is when it last moved.
	 */
	record BotJobView(String jobId, String state, int total, int created, Strategies strategies, String cash,
			List<SetupController.HoldingResponse> holdings, int partSize, int partsDone, Instant startedAt,
			Instant finishedAt, Long secondsLeft, Instant lastProgressAt, boolean stalled, String failure,
			int resumed) {
	}

	/**
	 * The job as read at {@code now}: stalled once it has not moved for longer than {@code stalledAfter}, and the time
	 * left counted down to {@code now}.
	 */
	static BotJobView of(BotJob job, Instant now, Duration stalledAfter) {
		OptionalLong left = job.secondsLeft(now, stalledAfter);
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
				left.isPresent() ? left.getAsLong() : null, job.progressAt(), job.stalled(now, stalledAfter),
				job.failure(), job.resumed());
	}

	/**
	 * The job as it was when an answer was stored with it, such as a refused opening, which a replay repeats: read at
	 * the moment it last moved, so it is never stalled and its time left is the estimate it had then.
	 */
	static BotJobView asItWas(BotJob job) {
		return of(job, job.progressAt(), Duration.ZERO);
	}

	/** "4,000 of 20,000 bots are made", for a refusal that names the running job. */
	static String progress(BotJob job) {
		return String.format(Locale.ROOT, "%,d of %,d bots are made", job.createdTotal(), job.total());
	}

}
