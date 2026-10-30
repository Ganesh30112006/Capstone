package com.weatherplatform.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-client token-bucket rate limiting, applied before authentication so that
 * credential-stuffing attempts are throttled without ever reaching auth-service.
 *
 * Buckets are held in memory, which is correct for a single gateway instance.
 * Horizontally scaled deployments should switch to the Redis-backed
 * RequestRateLimiter; see docs/reliability.md for the migration note.
 */
@Slf4j
@Component
public class RateLimitConfig implements GlobalFilter, Ordered {

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Value("${gateway.ratelimit.auth.capacity:10}")
    private int authCapacity;

    @Value("${gateway.ratelimit.auth.refill-per-second:1}")
    private int authRefill;

    @Value("${gateway.ratelimit.report.capacity:20}")
    private int reportCapacity;

    @Value("${gateway.ratelimit.report.refill-per-second:2}")
    private int reportRefill;

    @Value("${gateway.ratelimit.public.capacity:120}")
    private int publicCapacity;

    @Value("${gateway.ratelimit.public.refill-per-second:20}")
    private int publicRefill;

    @Value("${gateway.ratelimit.default.capacity:200}")
    private int defaultCapacity;

    @Value("${gateway.ratelimit.default.refill-per-second:20}")
    private int defaultRefill;

    public RateLimitConfig() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RateLimit-Cleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::cleanupBuckets, 5, 5, TimeUnit.MINUTES);
    }

    private void cleanupBuckets() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        buckets.entrySet().removeIf(e -> e.getValue().idleSince(cutoff));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Health and metrics scraping must never be throttled.
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        int capacity = defaultCapacity;
        int refill = defaultRefill;
        String tier = "default";

        if (path.startsWith("/api/auth/")) {
            capacity = authCapacity;
            refill = authRefill;
            tier = "auth";
        } else if (path.startsWith("/api/reports/")) {
            capacity = reportCapacity;
            refill = reportRefill;
            tier = "report";
        } else if (path.startsWith("/api/public/")) {
            capacity = publicCapacity;
            refill = publicRefill;
            tier = "public";
        }

        String key = clientIdentity(exchange) + ":" + tier;
        final int finalCapacity = capacity;
        final int finalRefill = refill;

        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(finalCapacity, finalRefill));
        if (bucket.tryConsume()) {
            return chain.filter(exchange);
        }

        log.warn("Rate limit exceeded on tier {} for {}", tier, path);
        return tooManyRequests(exchange, finalRefill);
    }

    /**
     * Behind an ALB the socket address is the load balancer, so the left-most
     * X-Forwarded-For entry is the real client.
     */
    private String clientIdentity(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, int refillPerSecond) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        long retryAfterSeconds = Math.max(1, (long) Math.ceil(1.0 / Math.max(1, refillPerSecond)));
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        String body = """
                {"success":false,"status":429,"error":"Too Many Requests",\
                "message":"Rate limit exceeded. Please retry shortly.","path":"%s"}"""
                .formatted(exchange.getRequest().getPath().value());

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -2; // Before JwtAuthenticationFilter (-1).
    }

    private static class TokenBucket {
        private final int capacity;
        private final int refillTokensPerSecond;
        private double tokens;
        private Instant lastRefill;

        TokenBucket(int capacity, int refillTokensPerSecond) {
            this.capacity = capacity;
            this.refillTokensPerSecond = refillTokensPerSecond;
            this.tokens = capacity;
            this.lastRefill = Instant.now();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized boolean idleSince(Instant cutoff) {
            return lastRefill.isBefore(cutoff);
        }

        private void refill() {
            Instant now = Instant.now();
            double seconds = Duration.between(lastRefill, now).toMillis() / 1000.0;
            tokens = Math.min(capacity, tokens + seconds * refillTokensPerSecond);
            lastRefill = now;
        }
    }
}
