package com.omkar.api_gateway.routing;

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
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RouteForwardingTest {

	private static final WireMockServer backend = new WireMockServer(
			WireMockConfiguration.options().dynamicPort());

	static {
		backend.start();
	}

	@DynamicPropertySource
	static void backendUri(DynamicPropertyRegistry registry) {
		registry.add("app.gateway.backend-uri", backend::baseUrl);
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
				.build();
	}

	@Test
	void forwardsApiHelloToBackendHello() {
		backend.stubFor(get(urlEqualTo("/hello"))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "text/plain")
						.withBody("hello-from-backend")));

		webTestClient.get()
				.uri("/api/hello")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class).isEqualTo("hello-from-backend");

		backend.verify(1, getRequestedFor(urlEqualTo("/hello")));
		backend.verify(0, getRequestedFor(urlEqualTo("/api/hello")));
	}

	@Test
	void forwardsNestedPathAfterStripPrefix() {
		backend.stubFor(get(urlEqualTo("/hello/world"))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "text/plain")
						.withBody("nested-from-backend")));

		webTestClient.get()
				.uri("/api/hello/world")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class).isEqualTo("nested-from-backend");

		backend.verify(1, getRequestedFor(urlEqualTo("/hello/world")));
		backend.verify(0, getRequestedFor(urlEqualTo("/world")));
		backend.verify(0, getRequestedFor(urlEqualTo("/api/hello/world")));
	}

	@Test
	void doesNotForwardUnmatchedPath() {
		webTestClient.get()
				.uri("/hello")
				.exchange()
				.expectStatus().isNotFound();

		backend.verify(0, anyRequestedFor(anyUrl()));
	}

}
