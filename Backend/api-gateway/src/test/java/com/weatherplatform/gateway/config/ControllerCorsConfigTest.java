package com.weatherplatform.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerCorsConfigTest {

    private static final String CONSOLE = "https://console.example.com";

    private WebFilter filter;
    private AtomicBoolean chainCalled;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        CorsConfiguration policy = new CorsConfiguration();
        policy.setAllowedOriginPatterns(List.of(CONSOLE));
        policy.setAllowedMethods(List.of("GET", "OPTIONS"));
        policy.setAllowedHeaders(List.of("*"));
        policy.setAllowCredentials(true);
        GlobalCorsProperties props = new GlobalCorsProperties();
        props.getCorsConfigurations().put("/**", policy);

        filter = new ControllerCorsConfig().controllerCorsFilter(props);
        chainCalled = new AtomicBoolean(false);
        chain = exchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };
    }

    private MockServerWebExchange preflight(String path, String origin) {
        // Absolute URL: a request with no host cannot be judged cross-origin.
        return MockServerWebExchange.from(MockServerHttpRequest.options("https://gateway.internal" + path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION));
    }

    @Test
    void answersPreflightForGatewayOwnEndpoint() {
        MockServerWebExchange exchange = preflight("/api/system/health", CONSOLE);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(CONSOLE, exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
        assertFalse(chainCalled.get(), "a preflight is answered here, not passed to the controller");
    }

    @Test
    void refusesPreflightFromUnlistedOrigin() {
        MockServerWebExchange exchange = preflight("/api/system/health", "https://evil.example.com");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNull(exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
    }

    @Test
    void leavesProxiedRoutesToGlobalCors() {
        MockServerWebExchange exchange = preflight("/api/devices", CONSOLE);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertTrue(chainCalled.get(), "route preflights must reach globalcors");
        assertNull(exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
    }
}
