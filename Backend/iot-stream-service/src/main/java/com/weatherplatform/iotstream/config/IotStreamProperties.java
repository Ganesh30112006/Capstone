package com.weatherplatform.iotstream.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Everything that governs where the live stream comes from and where it goes.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "iot.stream")
public class IotStreamProperties {

    /**
     * Where live telemetry is read from.
     *
     * <ul>
     *   <li>{@code aws} — subscribe directly to AWS IoT Core over MQTT/WSS with
     *       SigV4. This is the production configuration and the true parallel
     *       branch off the broker.</li>
     *   <li>{@code rabbit} — consume {@code iot.telemetry.received} /
     *       {@code device.heartbeat} off the internal bus instead. Used for
     *       local development and CI, where there is no IoT Core, while still
     *       exercising the fan-out and WebSocket paths end to end.</li>
     * </ul>
     */
    private Source source = Source.rabbit;

    /** MQTT topic tree root. Must match {@code aws.iot.topic-prefix} in device-service. */
    private String topicPrefix = "weather/devices";

    private final Aws aws = new Aws();
    private final Fanout fanout = new Fanout();
    private final WebSocket webSocket = new WebSocket();

    public enum Source { aws, rabbit }

    @Getter
    @Setter
    public static class Aws {
        /** ATS data endpoint, e.g. {@code a1b2c3d4e5-ats.iot.us-east-1.amazonaws.com}. */
        private String endpoint;
        private String region = "us-east-1";
        /** MQTT client id. Must be unique per running instance. */
        private String clientId = "iot-stream-service";
        /** Topic filters to subscribe to. {@code +} is the deviceUid wildcard. */
        private List<String> topicFilters = List.of(
                "weather/devices/+/telemetry",
                "weather/devices/+/heartbeat");
        /** Seconds between reconnect attempts, capped. */
        private long reconnectMinSeconds = 1;
        private long reconnectMaxSeconds = 30;
    }

    @Getter
    @Setter
    public static class Fanout {
        /**
         * Publish each reading onto the internal bus as well as broadcasting it.
         *
         * Off by default. The AI service consumes the backend branch —
         * {@code sensor.reading.created}, after sensor-service has stored the
         * reading — which is also what feeds analytics, alerting and the digital
         * twin. It used to consume this branch too, so every reading was scored
         * twice. With nothing bound to the routing key, leaving this on means
         * every frame returns as unroutable and logs a warning.
         */
        private boolean publishToRabbit = false;
        /** Broadcast each reading to connected WebSocket clients. */
        private boolean broadcastToWebSocket = true;
        /**
         * Forward AI output ({@code prediction.generated}, {@code report.generated},
         * {@code anomaly.detected}) from the bus back onto the WebSocket so the
         * admin console sees predictions and anomalies live.
         */
        private boolean forwardAiEvents = true;
        /**
         * Forward device lifecycle events ({@code device.registered},
         * {@code firmware.update.requested}, {@code firmware.update.completed})
         * onto the WebSocket.
         *
         * <p>device-service published all three from the day it was written and
         * nothing was ever bound to them, so the broker discarded every one. The
         * state behind them is safe -- it is committed to PostgreSQL before the
         * publish -- but the notification was not: an operator watching the
         * console learned nothing when a station was registered or a firmware
         * rollout finished.
         */
        private boolean forwardDeviceEvents = true;
    }

    @Getter
    @Setter
    public static class WebSocket {
        /** Max frames buffered per slow client before its session is dropped. */
        private int perSessionBufferLimit = 256;
        /** Drop a session that has not completed a send within this many ms. */
        private long sendTimeoutMs = 5_000;
        /** Hard ceiling on concurrent sessions. */
        private int maxSessions = 500;
    }
}
