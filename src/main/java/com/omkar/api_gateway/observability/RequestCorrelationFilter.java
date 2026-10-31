package com.omkar.api_gateway.observability;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
public class RequestCorrelationFilter implements WebFilter, Ordered {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	public static final String REQUEST_ID_ATTRIBUTE = "gatewayRequestId";

	private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String requestId = resolveRequestId(exchange.getRequest());
		exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
		exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

		ServerHttpRequest request = exchange.getRequest().mutate()
				.headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
				.build();
		ServerWebExchange correlated = exchange.mutate().request(request).build();
		long startedAt = System.nanoTime();

		return chain.filter(correlated).doFinally(signal -> logRequest(correlated, requestId, startedAt));
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	private static String resolveRequestId(ServerHttpRequest request) {
		String supplied = request.getHeaders().getFirst(REQUEST_ID_HEADER);
		if (supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()) {
			return supplied;
		}
		return UUID.randomUUID().toString();
	}

	private static void logRequest(ServerWebExchange exchange, String requestId, long startedAt) {
		String path = exchange.getRequest().getPath().value();
		if (path.startsWith("/actuator")) {
			return;
		}
		HttpStatusCode status = exchange.getResponse().getStatusCode();
		int statusCode = status == null ? 0 : status.value();
		long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
		log.info("event=gateway_request method={} path={} status={} durationMs={} requestId={}",
				exchange.getRequest().getMethod(), path, statusCode, durationMs, requestId);
	}

}
