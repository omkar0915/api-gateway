package com.omkar.api_gateway.error;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.omkar.api_gateway.observability.RequestCorrelationFilter;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
		if (exchange.getResponse().isCommitted()) {
			return Mono.error(exception);
		}

		int status = resolveStatus(exception);
		String requestId = requestId(exchange);
		if (status >= 500) {
			log.warn("event=gateway_error requestId={} status={} exception={}",
					requestId, status, exception.getClass().getSimpleName());
		}
		else {
			log.info("event=gateway_error requestId={} status={}", requestId, status);
		}

		HttpStatus resolved = HttpStatus.resolve(status);
		String error = resolved == null ? "Error" : resolved.getReasonPhrase();
		byte[] body = json(status, error, publicMessage(status), requestId).getBytes(StandardCharsets.UTF_8);

		exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		exchange.getResponse().getHeaders().setContentLength(body.length);
		exchange.getResponse().getHeaders().set(RequestCorrelationFilter.REQUEST_ID_HEADER, requestId);
		return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
	}

	static int resolveStatus(Throwable exception) {
		Integer responseStatus = null;
		Throwable current = exception;
		int depth = 0;
		while (current != null && depth < 12) {
			if (current instanceof org.springframework.web.client.HttpStatusCodeException httpStatus) {
				return httpStatus.getStatusCode().value();
			}
			if (current instanceof TimeoutException || current instanceof ReadTimeoutException
					|| current instanceof ConnectTimeoutException) {
				return HttpStatus.GATEWAY_TIMEOUT.value();
			}
			if (current instanceof java.net.ConnectException || current instanceof PrematureCloseException) {
				return HttpStatus.SERVICE_UNAVAILABLE.value();
			}
			if (current instanceof ResponseStatusException responseStatusException && responseStatus == null) {
				responseStatus = responseStatusException.getStatusCode().value();
			}
			current = current.getCause();
			depth++;
		}
		return responseStatus == null ? HttpStatus.INTERNAL_SERVER_ERROR.value() : responseStatus;
	}

	static String publicMessage(int status) {
		return switch (status) {
			case 404 -> "No route matches the request path.";
			case 429 -> "Rate limit exceeded.";
			case 502, 503 -> "The backend is unavailable.";
			case 504 -> "The backend did not respond in time.";
			default -> status >= 500
					? "The gateway could not complete the request."
					: "The request could not be processed.";
		};
	}

	private static String requestId(ServerWebExchange exchange) {
		Object requestId = exchange.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
		if (requestId instanceof String value && !value.isBlank()) {
			return value;
		}
		String header = exchange.getResponse().getHeaders().getFirst(RequestCorrelationFilter.REQUEST_ID_HEADER);
		return header == null || header.isBlank() ? "unavailable" : header;
	}

	private static String json(int status, String error, String message, String requestId) {
		return "{\"status\":" + status
				+ ",\"error\":\"" + escape(error)
				+ "\",\"message\":\"" + escape(message)
				+ "\",\"requestId\":\"" + escape(requestId) + "\"}";
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
