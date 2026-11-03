package com.mia_princz.mse.execution.trading.domain;

import java.util.UUID;

/** One execution against a resting order, always at the resting (maker) order's price. */
public record Fill(UUID makerOrderId, UUID makerAccountId, Price price, long quantity, long makerRemainingAfter) {
}
