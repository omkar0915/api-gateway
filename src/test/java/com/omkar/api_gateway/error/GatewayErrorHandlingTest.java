package com.omkar.api_gateway.error;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayErrorHandlingTest {

	private static final WireMockServer backend = new WireMockServer(
			WireMockConfiguration.options().dynamicPort());

	static {
		backend.start();
	}

	@DynamicPropertySource
	static void gatewayProperties(DynamicPropertyRegistry registry) {
		registry.add("app.gateway.backend-uri", backend::baseUrl);
		registry.add("app.gateway.response-timeout", () -> "500ms");
		registry.add("app.gateway.connect-timeout-ms", () -> "500");
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
		webTestClient = WebTestClient.bindToServer()
				.baseUrl("http://127.0.0.1:" + gatewayPort)
				.responseTimeout(java.time.Duration.ofSeconds(5))
				.build();
	}

	@Test
	void returnsJsonForAnUnknownRoute() {
		webTestClient.get()
				.uri("/unknown")
				.exchange()
				.expectStatus().isNotFound()
				.expectHeader().exists("X-Request-Id")
				.expectBody()
				.jsonPath("$.status").isEqualTo(404)
				.jsonPath("$.error").isEqualTo("Not Found")
				.jsonPath("$.message").isEqualTo("No route matches the request path.")
				.jsonPath("$.exception").doesNotExist()
				.jsonPath("$.trace").doesNotExist();

		assertThat(backend.findAll(getRequestedFor(urlEqualTo("/unknown")))).isEmpty();
	}

	@Test
	void passesBackendResponsesThrough() {
		backend.stubFor(get(urlEqualTo("/missing"))
				.willReturn(aResponse().withStatus(404).withBody("backend-missing")));

		webTestClient.get()
				.uri("/api/missing")
				.exchange()
				.expectStatus().isNotFound()
				.expectBody(String.class).isEqualTo("backend-missing");
	}

	@Test
	void returnsGatewayTimeoutWhenTheBackendIsTooSlow() {
		backend.stubFor(get(urlEqualTo("/hello"))
				.willReturn(aResponse().withStatus(200).withFixedDelay(2_000).withBody("too-late")));

		webTestClient.get()
				.uri("/api/hello")
				.exchange()
				.expectStatus().isEqualTo(504)
				.expectBody()
				.jsonPath("$.status").isEqualTo(504)
				.jsonPath("$.message").isEqualTo("The backend did not respond in time.")
				.jsonPath("$.exception").doesNotExist()
				.jsonPath("$.trace").doesNotExist();
	}

	@Test
	void returnsServiceUnavailableWhenTheBackendConnectionFails() {
		backend.stubFor(get(urlEqualTo("/hello"))
				.inScenario("reset")
				.whenScenarioStateIs(STARTED)
				.willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

		webTestClient.get()
				.uri("/api/hello")
				.exchange()
				.expectStatus().isEqualTo(503)
				.expectBody()
				.jsonPath("$.status").isEqualTo(503)
				.jsonPath("$.message").isEqualTo("The backend is unavailable.")
				.jsonPath("$.exception").doesNotExist();
	}

}
