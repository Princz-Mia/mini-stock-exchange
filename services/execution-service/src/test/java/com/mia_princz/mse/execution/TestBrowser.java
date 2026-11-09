package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** A minimal browser for the real filter chain: cookies and the CSRF header handled by hand, so both can be observed. */
final class TestBrowser {

	private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

	private final int port;

	/** Concurrent, so one logged-in browser can send parallel requests. */
	final Map<String, String> cookies = new ConcurrentHashMap<>();

	String csrfToken;

	TestBrowser(int port) {
		this.port = port;
	}

	/** A browser that logged in and holds the CSRF token issued after login. */
	static TestBrowser loggedIn(int port, String username, String password) throws Exception {
		TestBrowser browser = new TestBrowser(port);
		browser.fetchCsrfToken();
		HttpResponse<String> login = browser.post("/api/v1/auth/login",
				"{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
		assertThat(login.statusCode()).as("login as " + username).isEqualTo(200);
		browser.fetchCsrfToken();
		return browser;
	}

	/** How a test stores a login it inserts by hand: bcrypt, the only form the service accepts. */
	static String passwordHash(String password) {
		return "{bcrypt}" + new BCryptPasswordEncoder().encode(password);
	}

	void fetchCsrfToken() throws Exception {
		Matcher matcher = TOKEN.matcher(get("/api/v1/auth/csrf").body());
		assertThat(matcher.find()).isTrue();
		csrfToken = matcher.group(1);
	}

	/** Extra headers come as name, value pairs. */
	HttpResponse<String> get(String path, String... headers) throws Exception {
		HttpRequest.Builder request = request(path).GET();
		for (int i = 0; i < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		return send(request);
	}

	/** Extra headers come as name, value pairs. */
	HttpResponse<String> post(String path, String json, String... headers) throws Exception {
		HttpRequest.Builder request = request(path).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json));
		if (csrfToken != null) {
			request.header("X-CSRF-TOKEN", csrfToken);
		}
		for (int i = 0; i < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		return send(request);
	}

	private HttpRequest.Builder request(String path) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (!cookies.isEmpty()) {
			request.header("Cookie", cookies.entrySet()
				.stream()
				.map((cookie) -> cookie.getKey() + "=" + cookie.getValue())
				.collect(Collectors.joining("; ")));
		}
		return request;
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
			for (String header : response.headers().allValues("Set-Cookie")) {
				String pair = header.split(";", 2)[0];
				int separator = pair.indexOf('=');
				cookies.put(pair.substring(0, separator), pair.substring(separator + 1));
			}
			return response;
		}
	}

}
