package com.weatherplatform.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Assigns a correlation ID to every request at the very edge of the platform.
 *
 * The ID is forwarded downstream as a request header, echoed back to the caller
 * on the response, and carried onto RabbitMQ messages by the services, so a
 * single browser action can be traced across HTTP hops and async consumers.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_ATTRIBUTE = "correlationId";

    /** Bound so a hostile client cannot inject an unbounded value into every log line. */
    private static final int MAX_LENGTH = 64;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String correlationId = sanitize(request.getHeaders().getFirst(CORRELATION_ID_HEADER));
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        ServerHttpRequest modifiedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();

        exchange.getAttributes().put(CORRELATION_ID_ATTRIBUTE, correlationId);
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);

        return chain.filter(exchange.mutate().request(modifiedRequest).build());
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        // Log-injection guard: only allow characters that cannot break a log line.
        return trimmed.replaceAll("[^A-Za-z0-9._:-]", "");
    }

    @Override
    public int getOrder() {
        return -10; // Earliest — every other filter should be able to see the ID.
    }
}
