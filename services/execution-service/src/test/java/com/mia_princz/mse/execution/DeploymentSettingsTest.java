package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The settings around this service that its own tests can't reach, such as the reverse proxy in front of it. Each is
 * read as text, and the proxy's locations are matched in nginx's own order.
 */
class DeploymentSettingsTest {

	private static final Path ROOT = Path.of("../..");

	private static final Pattern LOCATION = Pattern.compile("location\\s+(=|~\\*|~|\\^~)?\\s*(\\S+)\\s*\\{([^}]*)}");

	@Test
	void theProxyWaitsLongerForOperatorCommandsThanTheCloseDoes() throws IOException {
		Duration close = Duration.parse("PT" + applicationProperties().getProperty("mse.market.close-timeout"));

		for (String path : List.of("/api/v1/admin/market/close", "/api/v1/admin/bot-runs", "/api/v1/admin")) {
			Location location = proxy().route(path);

			assertThat(location.directive("proxy_pass")).as(path).contains("http://execution");
			// Waits before the close starts, such as for a database connection, still end in the service's answer.
			assertThat(readTimeout(location)).as(path).isGreaterThanOrEqualTo(close.plusSeconds(20));
		}
	}

	@Test
	void otherRoutesKeepTheProxysDefaultWait() throws IOException {
		for (String path : List.of("/api/v1/orders", "/api/v1/auth/login", "/api/v1/me", "/api/v1/market",
				"/api/v1/instruments")) {
			assertThat(proxy().route(path).directive("proxy_read_timeout")).as(path).isEmpty();
		}
		assertThat(proxy().route("/api/v1/administrators").directive("proxy_pass")).as("not an admin route").isEmpty();
	}

	@Test
	void streamsStayOpenAndUnbufferedForAnHour() throws IOException {
		for (String path : List.of("/api/v1/market-events", "/api/v1/account-events")) {
			Location location = proxy().route(path);

			assertThat(location.directive("proxy_buffering")).as(path).contains("off");
			assertThat(readTimeout(location)).as(path).isEqualTo(Duration.ofHours(1));
		}
	}

	private static Properties applicationProperties() throws IOException {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
			properties.load(reader);
		}
		return properties;
	}

	/** nginx's value, or its default of 60 s when the location sets none. */
	private static Duration readTimeout(Location location) {
		String value = location.directive("proxy_read_timeout").orElse("60s");
		Matcher matcher = Pattern.compile("(\\d+)(ms|s|m|h)").matcher(value);
		assertThat(matcher.matches()).as(value).isTrue();
		long amount = Long.parseLong(matcher.group(1));
		return switch (matcher.group(2)) {
			case "ms" -> Duration.ofMillis(amount);
			case "s" -> Duration.ofSeconds(amount);
			case "m" -> Duration.ofMinutes(amount);
			default -> Duration.ofHours(amount);
		};
	}

	private static Proxy proxy() throws IOException {
		String config = Files.readString(ROOT.resolve("web/nginx.conf")).replaceAll("(?m)#.*$", "");
		List<Location> locations = new ArrayList<>();
		Matcher matcher = LOCATION.matcher(config);
		while (matcher.find()) {
			Map<String, String> directives = new LinkedHashMap<>();
			for (String statement : matcher.group(3).split(";")) {
				String[] parts = statement.strip().split("\\s+", 2);
				if (parts.length == 2) {
					directives.put(parts[0], parts[1]);
				}
			}
			locations.add(new Location(matcher.group(1) == null ? "" : matcher.group(1), matcher.group(2), directives));
		}
		return new Proxy(locations);
	}

	private record Location(String modifier, String pattern, Map<String, String> directives) {

		Optional<String> directive(String name) {
			return Optional.ofNullable(directives.get(name));
		}

	}

	private record Proxy(List<Location> locations) {

		/**
		 * nginx's order: an exact match; else the longest prefix, unless a regular expression matches first, tried in
		 * the order written.
		 */
		Location route(String path) {
			Optional<Location> exact = locations.stream()
				.filter((location) -> location.modifier().equals("=") && location.pattern().equals(path))
				.findFirst();
			if (exact.isPresent()) {
				return exact.get();
			}
			Optional<Location> prefix = locations.stream()
				.filter((location) -> (location.modifier().isEmpty() || location.modifier().equals("^~"))
						&& path.startsWith(location.pattern()))
				.max(Comparator.comparingInt((location) -> location.pattern().length()));
			if (prefix.isPresent() && prefix.get().modifier().equals("^~")) {
				return prefix.get();
			}
			return locations.stream()
				.filter((location) -> location.modifier().startsWith("~"))
				.filter((location) -> Pattern
					.compile(location.pattern(), location.modifier().equals("~*") ? Pattern.CASE_INSENSITIVE : 0)
					.matcher(path)
					.find())
				.findFirst()
				.or(() -> prefix)
				.orElseThrow(() -> new AssertionError("no location for " + path));
		}

	}

}
