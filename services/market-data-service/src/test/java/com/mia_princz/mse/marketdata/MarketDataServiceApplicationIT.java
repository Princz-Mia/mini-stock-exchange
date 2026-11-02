package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MarketDataServiceApplicationIT {

	@LocalServerPort
	int port;

	@Test
	void healthProbesAreUpAndOpen() throws Exception {
		assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
		HttpResponse<String> readiness = get("/actuator/health/readiness");
		assertThat(readiness.statusCode()).isEqualTo(200);
		assertThat(readiness.body()).contains("\"status\":\"UP\"");
	}

	@Test
	void everythingElseIsDeniedByDefault() throws Exception {
		assertThat(get("/api/v1/anything").statusCode()).isEqualTo(403);
		assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
	}

	private HttpResponse<String> get(String path) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

}
