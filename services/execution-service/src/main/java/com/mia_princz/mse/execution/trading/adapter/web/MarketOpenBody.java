package com.mia_princz.mse.execution.trading.adapter.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.HttpMediaTypeNotSupportedException;

/**
 * The optional JSON body of opening the market. Unknown JSON fields are rejected before this class sees them.
 * {@code durationMinutes} left out means no duration: the market stays open until an operator closes it.
 */
record MarketOpenBody(Integer durationMinutes) {

	/** Shorter than this, a market closes before its screens have shown the time it closes at. */
	static final int MIN_MINUTES = 5;

	/** Thirty days: a simulation is a session, and one left running for weeks is what a duration is there to end. */
	static final int MAX_MINUTES = 30 * 24 * 60;

	/**
	 * The body as sent, or null for none. An empty body is no body whatever its content type says: Windows PowerShell
	 * and other clients send a form content type with an empty POST, which the framework, reading this record, would
	 * refuse with 415, although opening took no body before durations existed and they open the market that way. A
	 * body with anything in it is read by the converter that reads every other JSON body, so it takes the same content
	 * types, refuses unknown fields, and is answered 400 when unreadable and 415 for another content type.
	 */
	static MarketOpenBody read(byte[] body, HttpHeaders headers, JacksonJsonHttpMessageConverter json)
			throws IOException, HttpMediaTypeNotSupportedException {
		if (body == null || body.length == 0) {
			return null;
		}
		// As the framework does: a body without a content type is a stream of bytes, which no JSON converter reads.
		MediaType type = headers.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM : headers.getContentType();
		if (!json.canRead(MarketOpenBody.class, type)) {
			throw new HttpMediaTypeNotSupportedException(type, json.getSupportedMediaTypes(MarketOpenBody.class),
					HttpMethod.POST);
		}
		return (MarketOpenBody) json.read(MarketOpenBody.class, new Sent(body, headers));
	}

	private record Sent(byte[] body, HttpHeaders headers) implements HttpInputMessage {

		@Override
		public InputStream getBody() {
			return new ByteArrayInputStream(body);
		}

		@Override
		public HttpHeaders getHeaders() {
			return headers;
		}

	}

	/** The duration, or null for none. */
	Duration validate() {
		if (durationMinutes == null) {
			return null;
		}
		if (durationMinutes < MIN_MINUTES || durationMinutes > MAX_MINUTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The opening request is invalid",
					List.of(new ApiException.FieldError("durationMinutes", "OUT_OF_RANGE",
							"durationMinutes must be between " + MIN_MINUTES + " and " + MAX_MINUTES)));
		}
		return Duration.ofMinutes(durationMinutes);
	}

}
