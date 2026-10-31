package com.omkar.api_gateway.ratelimit;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component("clientIdKeyResolver")
public class ClientIdKeyResolver implements KeyResolver {

	public static final String CLIENT_ID_HEADER = "X-Client-Id";

	static final String ANONYMOUS_KEY = "anonymous";

	private static final int MAX_CLIENT_ID_LENGTH = 128;

	@Override
	public Mono<String> resolve(ServerWebExchange exchange) {
		String clientId = exchange.getRequest().getHeaders().getFirst(CLIENT_ID_HEADER);
		if (isSafeClientId(clientId)) {
			return Mono.just(clientId.trim());
		}
		InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
		if (remote != null && remote.getAddress() != null) {
			return Mono.just(remote.getAddress().getHostAddress());
		}
		return Mono.just(ANONYMOUS_KEY);
	}

	private static boolean isSafeClientId(String clientId) {
		if (clientId == null) {
			return false;
		}
		String trimmed = clientId.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_CLIENT_ID_LENGTH) {
			return false;
		}
		for (int i = 0; i < trimmed.length(); i++) {
			char character = trimmed.charAt(i);
			if (character <= ' ' || character == 0x7f) {
				return false;
			}
		}
		return true;
	}

}
