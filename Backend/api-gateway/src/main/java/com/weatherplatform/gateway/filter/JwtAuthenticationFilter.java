package com.weatherplatform.gateway.filter;

import com.weatherplatform.gateway.config.GatewaySecurityProperties;
import com.weatherplatform.gateway.security.RoutePolicy;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Global authentication and authorization filter for the API Gateway.
 *
 * For every incoming request:
 * 1. Strip any client-supplied identity headers so they can never be spoofed.
 * 2. Public routes pass straight through with no identity attached.
 * 3. Everything else requires a valid JWT, else 401.
 * 4. Admin routes additionally require the ADMIN role claim, else 403.
 * 5. Authenticated requests are forwarded with X-User-Id / X-User-Email /
 *    X-User-Role plus the internal gateway secret.
 *
 * Downstream services independently re-check the forwarded identity; this
 * filter is the first checkpoint, not the only one.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    public static final String HEADER_INTERNAL_SECRET = "X-Gateway-Auth";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

    private static final String ROLE_ADMIN = "ADMIN";

    private final SecretKey secretKey;
    private final GatewaySecurityProperties properties;

    private List<RoutePolicy> publicPolicies = List.of();
    private List<RoutePolicy> adminPolicies = List.of();

    public JwtAuthenticationFilter(
            @Value("${jwt.secret}") String secret,
            GatewaySecurityProperties properties) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be provided to the API Gateway");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
    }

    @PostConstruct
    void compilePolicies() {
        this.publicPolicies = RoutePolicy.parseAll(properties.getPublicPaths());
        this.adminPolicies = RoutePolicy.parseAll(properties.getAdminPaths());
        log.info("Gateway authorization compiled: {} public rule(s), {} admin rule(s)",
                publicPolicies.size(), adminPolicies.size());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        HttpMethod method = request.getMethod();

        // CORS preflight carries no credentials and is answered by the CORS filter.
        if (HttpMethod.OPTIONS.equals(method)) {
            return chain.filter(exchange);
        }

        // (1) Never trust identity headers that arrived from the client.
        ServerHttpRequest.Builder sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_EMAIL);
                    headers.remove(HEADER_USER_ROLE);
                    headers.remove(HEADER_INTERNAL_SECRET);
                });

        // (2) Public routes are served without any identity.
        if (RoutePolicy.anyMatch(publicPolicies, method, path)) {
            return chain.filter(withInternalSecret(exchange, sanitized));
        }

        String token = bearerToken(request);
        if (token == null) {
            // Browsers cannot set the Authorization header when opening a
            // WebSocket, so the live-stream handshake passes the JWT as an
            // access_token query parameter instead. Accepted only for the
            // upgrade path, never as a general-purpose auth mechanism.
            if (path.startsWith("/ws/")) {
                token = request.getQueryParams().getFirst("access_token");
            }
        }
        if (token == null || token.isBlank()) {
            log.warn("Missing bearer token for {} {}", method, path);
            return deny(exchange, HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            // Never log the token itself, only why it was rejected.
            log.warn("JWT rejected for {} {}: {}", method, path, e.getClass().getSimpleName());
            return deny(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }

        String role = claims.get("role", String.class);
        String normalisedRole = role == null ? "" : role.toUpperCase(Locale.ROOT);

        // (4) Admin-only routes.
        if (RoutePolicy.anyMatch(adminPolicies, method, path) && !ROLE_ADMIN.equals(normalisedRole)) {
            log.warn("Role {} denied admin route {} {}", normalisedRole, method, path);
            return deny(exchange, HttpStatus.FORBIDDEN, "Administrator role required");
        }

        // (5) Forward the verified identity downstream.
        sanitized.header(HEADER_USER_ID, claims.getSubject())
                .header(HEADER_USER_EMAIL, nullSafe(claims.get("email", String.class)))
                .header(HEADER_USER_ROLE, normalisedRole);

        log.debug("Authorized user {} ({}) for {} {}", claims.getSubject(), normalisedRole, method, path);
        return chain.filter(withInternalSecret(exchange, sanitized));
    }

    private ServerWebExchange withInternalSecret(ServerWebExchange exchange, ServerHttpRequest.Builder builder) {
        String secret = properties.getInternalSecret();
        if (secret != null && !secret.isBlank()) {
            builder.header(HEADER_INTERNAL_SECRET, secret);
        }
        return exchange.mutate().request(builder.build()).build();
    }

    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String correlationId = exchange.getRequest().getHeaders().getFirst(HEADER_CORRELATION_ID);
        String body = """
                {"success":false,"status":%d,"error":"%s","message":"%s","path":"%s","correlationId":"%s"}"""
                .formatted(
                        status.value(),
                        status.getReasonPhrase(),
                        message,
                        exchange.getRequest().getPath().value(),
                        correlationId == null ? "" : correlationId);

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String bearerToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
    }

    @Override
    public int getOrder() {
        return -1; // After rate limiting (-2), before routing.
    }
}
