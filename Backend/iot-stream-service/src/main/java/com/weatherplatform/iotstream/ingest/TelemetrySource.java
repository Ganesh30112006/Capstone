package com.weatherplatform.iotstream.ingest;

/**
 * A running subscription that feeds live readings into the fan-out service.
 *
 * <p>There are two implementations, chosen by {@code iot.stream.source}:
 * {@link AwsIotTelemetrySource} (production, MQTT/WSS to IoT Core) and
 * {@link RabbitTelemetrySource} (local/CI, off the internal bus). Exactly one
 * is active at a time.
 */
public interface TelemetrySource {

    /** {@code "aws"} or {@code "rabbit"} — surfaced to clients in the hello frame. */
    String name();

    /** True when the underlying subscription is currently receiving. */
    boolean isConnected();
}
