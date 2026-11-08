package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.Side;

/** A trade as the public tape shows it: the aggressor is the taker's side. */
public record TapeTrade(UUID tradeId, long tradeSequence, long priceCents, long quantity, Side aggressor,
		Instant executedAt) {

	public TapeTrade {
		Objects.requireNonNull(tradeId, "tradeId");
		Objects.requireNonNull(aggressor, "aggressor");
		Objects.requireNonNull(executedAt, "executedAt");
	}

}
