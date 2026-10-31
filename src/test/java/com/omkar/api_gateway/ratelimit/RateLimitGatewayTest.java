package com.omkar.api_gateway.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.omkar.api_gateway.support.RedisFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitGatewayTest {

	private static final WireMockServer backend = new WireMockServer(
			WireMockConfiguration.options().dynamicPort());

	private static final RedisFixture redis = RedisFixture.start();

	static {
		backend.start();
		System.out.println("Rate-limit Redis: " + redis.description());
	}

	@DynamicPropertySource
	static void gatewayProperties(DynamicPropertyRegistry registry) {
		registry.add("app.gateway.backend-uri", backend::baseUrl);
		registry.add("app.gateway.rate-limit.replenish-rate", () -> "1");
		registry.add("app.gateway.rate-limit.burst-capacity", () -> "1");
		registry.add("spring.data.redis.host", redis::host);
		registry.add("spring.data.redis.port", () -> String.valueOf(redis.port()));
	}

	@AfterAll
	static void stopDependencies() throws Exception {
		if (backend.isRunning()) {
			backend.stop();
		}
		redis.close();
	}

	@LocalServerPort
	private int gatewayPort;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		backend.resetAll();
		backend.stubFor(get(urlEqualTo("/hello"))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "text/plain")
						.withBody("hello-from-backend")));
		webTestClient = WebTestClient.bindToServer()
				.baseUrl("http://127.0.0.1:" + gatewayPort)
				.responseTimeout(Duration.ofSeconds(10))
				.build();
	}

	@Test
	void limitsOneClientAndLeavesAnotherClientUnaffected() {
		List<Integer> statuses = new ArrayList<>();
		statuses.add(send("client-a").getStatus().value());
		statuses.add(send("client-a").getStatus().value());
		if (statuses.get(1) == HttpStatus.OK.value()) {
			statuses.add(send("client-a").getStatus().value());
		}

		assertThat(statuses).contains(HttpStatus.TOO_MANY_REQUESTS.value());
		assertThat(statuses).allMatch(status -> status == 200 || status == 429);

		EntityExchangeResult<String> otherClient = send("client-b");
		assertThat(otherClient.getStatus()).isEqualTo(HttpStatus.OK);
		assertThat(otherClient.getResponseBody()).isEqualTo("hello-from-backend");

		int forwarded = (int) statuses.stream().filter(status -> status == 200).count() + 1;
		backend.verify(forwarded, getRequestedFor(urlEqualTo("/hello")));
	}

	@Test
	void allowsARequestAgainAfterTokensRefill() throws InterruptedException {
		EntityExchangeResult<String> allowed = send("client-refill");
		assertThat(allowed.getStatus()).isEqualTo(HttpStatus.OK);
		assertThat(allowed.getResponseHeaders().getFirst("X-RateLimit-Replenish-Rate")).isEqualTo("1");
		assertThat(allowed.getResponseHeaders().getFirst("X-RateLimit-Burst-Capacity")).isEqualTo("1");

		EntityExchangeResult<String> limited = send("client-refill");
		if (limited.getStatus().is2xxSuccessful()) {
			limited = send("client-refill");
		}
		assertThat(limited.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(limited.getResponseBody()).contains("\"status\":429");
		assertThat(limited.getResponseBody()).contains("Rate limit exceeded.");
		assertThat(limited.getResponseBody()).doesNotContain("Exception");
		assertThat(limited.getResponseBody()).doesNotContain("redis");

		Thread.sleep(2_100);

		EntityExchangeResult<String> refilled = send("client-refill");
		assertThat(refilled.getStatus()).isEqualTo(HttpStatus.OK);
		assertThat(refilled.getResponseBody()).isEqualTo("hello-from-backend");
	}

	private EntityExchangeResult<String> send(String clientId) {
		return webTestClient.get()
				.uri("/api/hello")
				.header(ClientIdKeyResolver.CLIENT_ID_HEADER, clientId)
				.exchange()
				.expectBody(String.class)
				.returnResult();
	}

}
