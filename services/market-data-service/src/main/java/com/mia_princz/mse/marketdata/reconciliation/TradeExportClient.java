package com.mia_princz.mse.marketdata.reconciliation;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one instrument's trades from Execution's internal export, every page up to the high-water mark the first page
 * captured. The token goes only into the Authorization header.
 */
final class TradeExportClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int PAGE = 1_000;

	private final URI execution;

	private final String token;

	private final Duration timeout;

	private final HttpClient http;

	TradeExportClient(URI execution, String token, Duration timeout) {
		this.execution = execution;
		this.token = token == null ? "" : token.strip();
		this.timeout = timeout;
		this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	boolean configured() {
		return !token.isEmpty();
	}

	/** Thrown when the export cannot be read: not configured, unreachable, or answering anything but a page. */
	static final class ExportUnavailableException extends Exception {

		private static final long serialVersionUID = 1L;

		ExportUnavailableException(String message) {
			super(message);
		}

	}

	List<CandleReconciler.Trade> trades(UUID instrumentId) throws ExportUnavailableException {
		if (!configured()) {
			throw new ExportUnavailableException("No export token is configured");
		}
		List<CandleReconciler.Trade> trades = new ArrayList<>();
		String through = null;
		String cursor = null;
		do {
			StringBuilder query = new StringBuilder("/internal/exports/trades?instrumentId=").append(instrumentId)
				.append("&limit=")
				.append(PAGE);
			if (through != null) {
				query.append("&throughSequence=").append(through);
			}
			if (cursor != null) {
				query.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
			}
			JsonNode page = fetch(query.toString());
			through = page.get("throughSequence").asString();
			for (JsonNode row : page.get("trades")) {
				Instant at = Instant.parse(row.get("executedAt").asString());
				trades.add(new CandleReconciler.Trade(Long.parseLong(row.get("tradeSequence").asString()),
						row.get("priceCents").asLong(), row.get("quantity").asLong(),
						Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L), at.getNano() / 1_000L)));
			}
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
		}
		while (cursor != null);
		return trades;
	}

	private JsonNode fetch(String pathAndQuery) throws ExportUnavailableException {
		HttpRequest request = HttpRequest.newBuilder(execution.resolve(pathAndQuery))
			.timeout(timeout)
			.header("Authorization", "Bearer " + token)
			.header("Accept", "application/json")
			.GET()
			.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new ExportUnavailableException("The trade export answered " + response.statusCode());
			}
			return JSON.readTree(response.body());
		}
		catch (IOException ex) {
			throw new ExportUnavailableException("The trade export is unreachable: " + ex.getMessage());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ExportUnavailableException("Interrupted while reading the trade export");
		}
	}

}
