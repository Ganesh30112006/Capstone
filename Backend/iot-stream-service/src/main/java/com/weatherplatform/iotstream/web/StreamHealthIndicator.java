package com.weatherplatform.iotstream.web;

import com.weatherplatform.iotstream.ingest.TelemetrySource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the live subscription's state without failing the overall health
 * check when it is disconnected.
 *
 * <p>A disconnected stream is reported as {@code OUT_OF_SERVICE} detail, not
 * {@code DOWN}: the SQS ingestion branch is the platform's guarantee, and
 * marking this service unhealthy would make the orchestrator cycle a container
 * that is doing its job of buffering and reconnecting.
 */
@Component("stream")
public class StreamHealthIndicator implements HealthIndicator {

    private final TelemetrySource source;
    private final LiveSessionRegistry registry;

    public StreamHealthIndicator(TelemetrySource source, LiveSessionRegistry registry) {
        this.source = source;
        this.registry = registry;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("source", source.name())
                .withDetail("subscriptionConnected", source.isConnected())
                .withDetail("activeClients", registry.size())
                .build();
    }
}
