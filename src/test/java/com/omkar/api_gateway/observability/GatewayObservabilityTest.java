package com.omkar.api_gateway.observability;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class GatewayObservabilityTest {

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
		backend.stubFor(get(urlEqualTo("/hello"))
				.willReturn(aResponse().withStatus(200).withBody("hello-from-backend")));
		webTestClient = WebTestClient.bindToServer()
				.baseUrl("http://127.0.0.1:" + gatewayPort)
				.build();
	}

	@Test
	void echoesASafeRequestIdAndForwardsIt(CapturedOutput output) {
		webTestClient.get()
				.uri("/api/hello?token=secret")
				.header(RequestCorrelationFilter.REQUEST_ID_HEADER, "req-123")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals(RequestCorrelationFilter.REQUEST_ID_HEADER, "req-123");

		backend.verify(getRequestedFor(urlEqualTo("/hello"))
				.withHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, equalTo("req-123")));
		assertThat(output).contains("event=gateway_request");
		assertThat(output).contains("path=/api/hello");
		assertThat(output).contains("requestId=req-123");
		assertThat(output).doesNotContain("secret");
	}

	@Test
	void replacesAnUnsafeRequestId() {
		webTestClient.get()
				.uri("/api/hello")
				.header(RequestCorrelationFilter.REQUEST_ID_HEADER, "bad\nid")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().value(RequestCorrelationFilter.REQUEST_ID_HEADER, value -> {
					assertThat(value).isNotEqualTo("bad\nid");
					assertThat(value).doesNotContain("\n");
				});
	}

	@Test
	void exposesHealthInfoAndMetricsWithoutSensitiveEndpoints() {
		webTestClient.get()
				.uri("/api/hello")
				.exchange()
				.expectStatus().isOk();

		webTestClient.get()
				.uri("/actuator/health")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("UP")
				.jsonPath("$.components").doesNotExist();

		webTestClient.get()
				.uri("/actuator/info")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.app.name").isEqualTo("api-gateway");

		webTestClient.get()
				.uri("/actuator/metrics")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.names[?(@ =~ /.*gateway.*/i)]").exists();

		webTestClient.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
		webTestClient.get().uri("/actuator/beans").exchange().expectStatus().isNotFound();
	}

}
