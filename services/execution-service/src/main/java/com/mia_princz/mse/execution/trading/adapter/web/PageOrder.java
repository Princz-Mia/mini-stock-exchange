package com.mia_princz.mse.execution.trading.adapter.web;

/**
 * Which end of a list its pages start from. Every paged list is in time order with a unique tiebreak, so either
 * direction is one keyset scan; a descending index is read backwards for the other.
 */
enum PageOrder {

	NEWEST, OLDEST;

	/** The {@code order} query parameter: {@code newest} or {@code oldest}, or the list's own default when absent. */
	static PageOrder parse(String value, PageOrder fallback) {
		if (value == null) {
			return fallback;
		}
		return switch (value) {
			case "newest" -> NEWEST;
			case "oldest" -> OLDEST;
			default -> throw ApiException.invalid("order", "INVALID_VALUE", "order must be newest or oldest");
		};
	}

	String wire() {
		return this == NEWEST ? "newest" : "oldest";
	}

	/** The SQL sort direction. Fixed text, never from the request. */
	String direction() {
		return this == NEWEST ? "DESC" : "ASC";
	}

	/** The row comparison that keeps rows after a page's last one. Fixed text, never from the request. */
	String after() {
		return this == NEWEST ? "<" : ">";
	}

}
