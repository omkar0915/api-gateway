package com.omkar.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GatewayConfigurationTests {

	@Autowired
	private Environment environment;

	@Autowired
	private RouteLocator routeLocator;

	@Test
	void loadsDefaultRoutingAndRateLimitConfiguration() {
		assertThat(environment.getProperty("app.gateway.backend-uri")).isEqualTo("https://httpbin.org");
		assertThat(environment.getProperty("app.gateway.rate-limit.replenish-rate")).isEqualTo("2");
		assertThat(environment.getProperty("app.gateway.rate-limit.burst-capacity")).isEqualTo("2");
		assertThat(environment.getProperty("app.gateway.response-timeout")).isEqualTo("3s");

		StepVerifier.create(routeLocator.getRoutes().filter(route -> "api-backend".equals(route.getId())))
				.assertNext(route -> assertThat(route.getUri()).hasToString("https://httpbin.org"))
				.verifyComplete();
	}

}
