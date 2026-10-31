package com.omkar.api_gateway.ratelimit;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisFailureGatewayTest {

	private static final WireMockServer backend = new WireMockServer(
			WireMockConfiguration.options().dynamicPort());

	static {
		backend.start();
	}

	@DynamicPropertySource
	static void gatewayProperties(DynamicPropertyRegistry registry) {
		registry.add("app.gateway.backend-uri", backend::baseUrl);
		registry.add("spring.data.redis.host", () -> "127.0.0.1");
		registry.add("spring.data.redis.port", () -> "1");
		registry.add("spring.data.redis.timeout", () -> "300ms");
	}

	@AfterAll
	static void stopBackend() {
		if (backend.isRunning()) {
			backend.stop();
		}
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
				.responseTimeout(java.time.Duration.ofSeconds(8))
				.build();
	}

	@Test
	void allowsTheRequestWhenRedisIsUnavailable() {
		webTestClient.get()
				.uri("/api/hello")
				.header(ClientIdKeyResolver.CLIENT_ID_HEADER, "client-a")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-RateLimit-Remaining", "-1")
				.expectBody(String.class)
				.value(body -> {
					assertThat(body).isEqualTo("hello-from-backend");
					assertThat(body).doesNotContain("Exception");
				});
	}

}
