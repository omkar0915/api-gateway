package com.omkar.api_gateway.ratelimit;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.test.StepVerifier;

class ClientIdKeyResolverTest {

	private final ClientIdKeyResolver resolver = new ClientIdKeyResolver();

	@Test
	void usesTrimmedClientIdHeader() {
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/hello")
				.header(ClientIdKeyResolver.CLIENT_ID_HEADER, "  client-a  ")
				.remoteAddress(new InetSocketAddress("10.0.0.8", 4000)));

		StepVerifier.create(resolver.resolve(exchange))
				.expectNext("client-a")
				.verifyComplete();
	}

	@Test
	void fallsBackToRemoteAddressWhenHeaderIsUnsafe() {
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/hello")
				.header(ClientIdKeyResolver.CLIENT_ID_HEADER, "bad\nid")
				.remoteAddress(new InetSocketAddress("10.1.2.3", 4000)));

		StepVerifier.create(resolver.resolve(exchange))
				.expectNext("10.1.2.3")
				.verifyComplete();
	}

	@Test
	void usesAnonymousKeyWhenNoIdentityIsAvailable() {
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/hello"));

		StepVerifier.create(resolver.resolve(exchange))
				.expectNext(ClientIdKeyResolver.ANONYMOUS_KEY)
				.verifyComplete();
	}

	private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
		return MockServerWebExchange.from(request.build());
	}

}
