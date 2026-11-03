package com.mia_princz.mse.execution.trading.domain;

/** One aggregated price level of the book. */
public record DepthLevel(Price price, long quantity, int orderCount) {
}
