package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.execution.trading.application.BotJob;

/**
 * The request for a bot job: any count an int holds, strategies that add up to it, and the endowment as for bots made
 * by hand. Every problem is named at once.
 */
class BotJobBodyTest {

	private static final long SEED = 20261124L;

	@Test
	void strategiesThatAddUpToTheCountAreTakenExactly() {
		Random random = new Random(SEED);
		for (int i = 0; i < 2_000; i++) {
			long makers = random.nextInt(1 + random.nextInt(100_000));
			long noise = random.nextInt(1 + random.nextInt(100_000));
			long momentum = random.nextInt(1 + random.nextInt(100_000));
			long count = makers + noise + momentum;
			if (count == 0) {
				continue;
			}
			BotJobBody.Valid valid = body(count, makers, noise, momentum).validate();
			assertThat(valid.requested()).isEqualTo(new BotJob.Counts((int) makers, (int) noise, (int) momentum));
			assertThat(valid.cashCents()).isEqualTo(50_000);
		}
		BotJobBody.Valid largest = body(Integer.MAX_VALUE, 0L, (long) Integer.MAX_VALUE, 0L).validate();
		assertThat(largest.requested().total()).as("no limit short of what a count holds").isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	void strategiesThatDoNotAddUpAreRefusedNotGuessedAt() {
		assertThat(errors(body(20L, 4L, 12L, 3L)))
			.containsExactly(new ApiException.FieldError("strategies", "MIX_MISMATCH",
					"the strategies add up to 19, not the count of 20"));
		assertThat(errors(body(20L, 4L, 12L, 5L))).extracting(ApiException.FieldError::code)
			.containsExactly("MIX_MISMATCH");
	}

	@Test
	void everyProblemIsNamedAtOnce() {
		assertThat(errors(new BotJobBody(0L, null, null, null))).extracting(ApiException.FieldError::field)
			.containsExactly("count", "strategies", "cash");
		assertThat(errors(body((long) Integer.MAX_VALUE + 1, -1L, null, (long) Integer.MAX_VALUE + 1)))
			.extracting(ApiException.FieldError::field)
			.containsExactly("count", "strategies.marketMakers", "strategies.noise", "strategies.momentum");
		assertThat(errors(new BotJobBody(5L, new BotJobBody.Strategies(0L, 5L, 0L), "1.00",
				List.of(new Endowments.HoldingBody(UUID.randomUUID(), 0L)))))
			.extracting(ApiException.FieldError::field)
			.containsExactly("holdings[0].quantity");
	}

	@Test
	void theHashIsTheSplitAndTheEndowment() {
		BotJobBody.Valid valid = body(20L, 4L, 12L, 4L).validate();
		assertThat(valid.hash()).isEqualTo(CommandCalls.sha256("4|12|4|50000|"));
		assertThat(valid.hash()).isNotEqualTo(body(20L, 5L, 11L, 4L).validate().hash());
	}

	private static BotJobBody body(long count, Long makers, Long noise, Long momentum) {
		return new BotJobBody(count, new BotJobBody.Strategies(makers, noise, momentum), "500.00", List.of());
	}

	@SuppressWarnings("unchecked")
	private static List<ApiException.FieldError> errors(BotJobBody body) {
		try {
			body.validate();
		}
		catch (ApiException refused) {
			return (List<ApiException.FieldError>) refused.toProblem().getProperties().get("errors");
		}
		throw new AssertionError("Not refused: " + body);
	}

}
