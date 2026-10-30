package com.weatherplatform.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitConfigTest {

    private static final int AUTH_CAPACITY = 5;

    private RateLimitConfig rateLimitConfig;
    private GatewayFilterChain filterChain;

    @BeforeEach
    void setUp() {
        rateLimitConfig = new RateLimitConfig();
        // @Value fields are not injected when constructing directly.
        ReflectionTestUtils.setField(rateLimitConfig, "authCapacity", AUTH_CAPACITY);
        ReflectionTestUtils.setField(rateLimitConfig, "authRefill", 1);
        ReflectionTestUtils.setField(rateLimitConfig, "reportCapacity", 20);
        ReflectionTestUtils.setField(rateLimitConfig, "reportRefill", 2);
        ReflectionTestUtils.setField(rateLimitConfig, "publicCapacity", 120);
        ReflectionTestUtils.setField(rateLimitConfig, "publicRefill", 20);
        ReflectionTestUtils.setField(rateLimitConfig, "defaultCapacity", 200);
        ReflectionTestUtils.setField(rateLimitConfig, "defaultRefill", 20);

        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void allowsActuatorEndpointsWithoutLimit() {
        for (int i = 0; i < 300; i++) {
            ServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/actuator/health")
                            .remoteAddress(new InetSocketAddress("10.0.0.1", 8080))
                            .build());

            StepVerifier.create(rateLimitConfig.filter(exchange, filterChain)).verifyComplete();
            assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode(),
                    "Actuator must never be rate limited");
        }
    }

    @Test
    void throttlesAuthEndpointsPastCapacity() {
        for (int i = 0; i < AUTH_CAPACITY; i++) {
            ServerWebExchange allowed = exchangeFor("/api/auth/login", "10.0.0.2");
            StepVerifier.create(rateLimitConfig.filter(allowed, filterChain)).verifyComplete();
            assertNull(allowed.getResponse().getStatusCode(), "Request " + i + " should pass");
        }

        ServerWebExchange blocked = exchangeFor("/api/auth/login", "10.0.0.2");
        StepVerifier.create(rateLimitConfig.filter(blocked, filterChain)).verifyComplete();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.getResponse().getStatusCode());
        assertNotNull(blocked.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                "429 responses must tell the client when to retry");
    }

    @Test
    void bucketsAreIsolatedPerClient() {
        for (int i = 0; i < AUTH_CAPACITY + 1; i++) {
            StepVerifier.create(rateLimitConfig.filter(exchangeFor("/api/auth/login", "10.0.0.3"), filterChain))
                    .verifyComplete();
        }

        // A different client still has a full bucket.
        ServerWebExchange other = exchangeFor("/api/auth/login", "10.0.0.4");
        StepVerifier.create(rateLimitConfig.filter(other, filterChain)).verifyComplete();
        assertNull(other.getResponse().getStatusCode());
    }

    @Test
    void authThrottlingDoesNotAffectPublicTier() {
        for (int i = 0; i < AUTH_CAPACITY + 1; i++) {
            StepVerifier.create(rateLimitConfig.filter(exchangeFor("/api/auth/login", "10.0.0.5"), filterChain))
                    .verifyComplete();
        }

        ServerWebExchange publicRequest = exchangeFor("/api/public/stations", "10.0.0.5");
        StepVerifier.create(rateLimitConfig.filter(publicRequest, filterChain)).verifyComplete();
        assertNull(publicRequest.getResponse().getStatusCode(),
                "Public reads use their own, more generous bucket");
    }

    @Test
    void identifiesClientByForwardedHeaderBehindLoadBalancer() {
        // Same socket address (the ALB), different real clients.
        for (int i = 0; i < AUTH_CAPACITY + 1; i++) {
            ServerWebExchange first = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/auth/login")
                            .remoteAddress(new InetSocketAddress("10.0.0.99", 8080))
                            .header("X-Forwarded-For", "203.0.113.10, 10.0.0.99")
                            .build());
            StepVerifier.create(rateLimitConfig.filter(first, filterChain)).verifyComplete();
        }

        ServerWebExchange second = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .remoteAddress(new InetSocketAddress("10.0.0.99", 8080))
                        .header("X-Forwarded-For", "203.0.113.20, 10.0.0.99")
                        .build());
        StepVerifier.create(rateLimitConfig.filter(second, filterChain)).verifyComplete();
        assertNull(second.getResponse().getStatusCode(),
                "One throttled client must not throttle everyone behind the same load balancer");
    }

    @Test
    void acceptsUnresolvedRemoteAddressFromForwardedHeaders() {
        // forward-headers-strategy=framework consumes X-Forwarded-For and
        // rebuilds the remote address unresolved; getAddress() is then null.
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/public/stations")
                        .remoteAddress(InetSocketAddress.createUnresolved("203.0.113.30", 0))
                        .build());

        StepVerifier.create(rateLimitConfig.filter(exchange, filterChain)).verifyComplete();
        assertNull(exchange.getResponse().getStatusCode());
    }

    private ServerWebExchange exchangeFor(String path, String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .remoteAddress(new InetSocketAddress(ip, 8080))
                        .build());
    }
}
