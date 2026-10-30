package com.weatherplatform.gateway.controller;

import com.weatherplatform.gateway.config.DownstreamServiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated health of every platform service, probed live.
 *
 * Only the service name and coarse status are surfaced — never hostnames,
 * ports, credentials, or raw actuator payloads, which would leak internal
 * topology to the operator console.
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemHealthController {

    private static final String STATUS_OPERATIONAL = "operational";
    private static final String STATUS_DEGRADED = "degraded";
    private static final String STATUS_OUTAGE = "outage";
    private static final String STATUS_UNKNOWN = "unknown";

    private final WebClient.Builder webClientBuilder;
    private final DownstreamServiceProperties properties;

    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> getSystemHealth() {
        Duration timeout = Duration.ofMillis(properties.getTimeoutMs());

        return Flux.fromIterable(properties.getServices().entrySet())
                .flatMap(entry -> probe(entry.getKey(), entry.getValue(), timeout))
                .collectList()
                .map(services -> {
                    boolean anyOutage = services.stream()
                            .anyMatch(s -> STATUS_OUTAGE.equals(s.get("status")));
                    boolean anyDegraded = services.stream()
                            .anyMatch(s -> STATUS_DEGRADED.equals(s.get("status")));

                    /*
                     * `gateway.health.services` defaults to an empty map, so a
                     * missing or misspelled configuration block probed nothing
                     * and fell straight through to "operational" -- the gateway
                     * certifying the platform healthy without having asked a
                     * single service. An aggregate over zero probes is unknown,
                     * not well.
                     */
                    String overall = services.isEmpty()
                            ? STATUS_UNKNOWN
                            : anyOutage ? STATUS_OUTAGE : anyDegraded ? STATUS_DEGRADED : STATUS_OPERATIONAL;

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("success", true);
                    body.put("message", services.isEmpty()
                            ? "No health probe targets are configured"
                            : "System health retrieved");
                    body.put("overallStatus", overall);
                    body.put("checkedAt", Instant.now().toString());
                    body.put("data", services);
                    return ResponseEntity.ok(body);
                });
    }

    private Mono<Map<String, Object>> probe(String name, String url, Duration timeout) {
        long start = System.nanoTime();
        return webClientBuilder.build()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(timeout)
                .map(body -> describe(name, translate(body), start))
                .onErrorResume(error -> {
                    log.warn("Health probe for {} failed: {}", name, error.getClass().getSimpleName());
                    return Mono.just(describe(name, STATUS_OUTAGE, start));
                });
    }

    @SuppressWarnings("rawtypes")
    private String translate(Map body) {
        Object status = body == null ? null : body.get("status");
        if (status == null) {
            return STATUS_DEGRADED;
        }
        return switch (status.toString().toUpperCase()) {
            case "UP" -> STATUS_OPERATIONAL;
            case "DOWN", "OUT_OF_SERVICE" -> STATUS_OUTAGE;
            default -> STATUS_DEGRADED;
        };
    }

    private Map<String, Object> describe(String name, String status, long startNanos) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("status", status);
        entry.put("latencyMs", Math.round((System.nanoTime() - startNanos) / 1_000_000.0));
        entry.put("lastCheck", Instant.now().toString());
        return entry;
    }

    /** Health targets are static config, so the list itself is safe to expose. */
    public List<String> monitoredServices() {
        return List.copyOf(properties.getServices().keySet());
    }
}
