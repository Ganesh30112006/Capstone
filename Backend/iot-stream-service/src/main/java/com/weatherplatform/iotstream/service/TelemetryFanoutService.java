package com.weatherplatform.iotstream.service;

import com.weatherplatform.iotstream.config.IotStreamProperties;
import com.weatherplatform.iotstream.config.RabbitStreamConfig;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import com.weatherplatform.iotstream.web.LiveSessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The junction where one incoming reading is fanned two independent ways.
 *
 * <p>The two branches do not know about each other. The RabbitMQ publish feeds
 * the AI service; the WebSocket broadcast feeds browsers. If the broker is down
 * the browsers still update; if every browser is gone the AI still gets its
 * feed. Neither branch can throw into the other, and neither can throw back
 * into the {@link com.weatherplatform.iotstream.ingest.TelemetrySource} that
 * called {@link #accept}, because the SQS branch — not this one — is what
 * guarantees a reading is stored.
 */
@Slf4j
@Service
public class TelemetryFanoutService {

    private final RabbitTemplate rabbitTemplate;
    private final LiveSessionRegistry registry;
    private final IotStreamProperties properties;

    private final Counter received;
    private final Counter published;
    private final Counter publishFailed;
    private final Counter broadcast;

    public TelemetryFanoutService(RabbitTemplate rabbitTemplate,
                                  LiveSessionRegistry registry,
                                  IotStreamProperties properties,
                                  MeterRegistry meters) {
        this.rabbitTemplate = rabbitTemplate;
        this.registry = registry;
        this.properties = properties;
        this.received = Counter.builder("iot_stream_frames_received_total").register(meters);
        this.published = Counter.builder("iot_stream_frames_published_total").register(meters);
        this.publishFailed = Counter.builder("iot_stream_publish_failures_total").register(meters);
        this.broadcast = Counter.builder("iot_stream_frames_broadcast_total").register(meters);
    }

    /** Entry point for both telemetry sources. Never throws. */
    public void accept(StreamEnvelope envelope) {
        if (envelope == null || envelope.getDeviceUid() == null) {
            return;
        }
        if (envelope.getReceivedAt() == null) {
            envelope.setReceivedAt(Instant.now());
        }
        received.increment();

        if (properties.getFanout().isPublishToRabbit()) {
            publishToBus(envelope);
        }
        if (properties.getFanout().isBroadcastToWebSocket()) {
            try {
                registry.broadcast(envelope);
                broadcast.increment();
            } catch (RuntimeException e) {
                log.warn("WebSocket broadcast failed for {}: {}", envelope.getDeviceUid(), e.toString());
            }
        }
    }

    /** Forwards an AI event ({@code prediction}/{@code report}/{@code anomaly}) to browsers only. */
    public void mirrorToClients(StreamEnvelope envelope) {
        if (!properties.getFanout().isForwardAiEvents() || envelope == null) {
            return;
        }
        try {
            registry.broadcast(envelope);
        } catch (RuntimeException e) {
            log.debug("Mirror of {} event failed: {}", envelope.getType(), e.toString());
        }
    }

    /**
     * Forwards a device lifecycle event ({@code deviceRegistered},
     * {@code firmwareRequested}, {@code firmwareCompleted}) to browsers only.
     *
     * <p>Separate from {@link #mirrorToClients} so the two can be turned off
     * independently: they come from different producers and answer different
     * questions, and an operator silencing AI chatter should not also lose
     * notice of a station being registered.
     */
    public void mirrorDeviceEvent(StreamEnvelope envelope) {
        if (!properties.getFanout().isForwardDeviceEvents() || envelope == null) {
            return;
        }
        try {
            registry.broadcast(envelope);
        } catch (RuntimeException e) {
            log.debug("Mirror of {} event failed: {}", envelope.getType(), e.toString());
        }
    }

    private void publishToBus(StreamEnvelope envelope) {
        /*
         * Telemetry only.
         *
         * The bus branch of this fan-out exists to feed the AI service, which
         * scores measurements; a heartbeat carries a battery level and a signal
         * strength and nothing to score. Nothing is bound to the heartbeat
         * routing key, so every heartbeat frame published here came straight
         * back as an unroutable return and logged a warning -- 304 of them in
         * the first twenty minutes of a four-station fleet, against a fleet
         * size that will be far larger.
         *
         * Heartbeats still reach browsers: the WebSocket branch below is what
         * keeps the console's liveness indicator honest, and it is unaffected.
         */
        if (envelope.getType() == StreamEnvelope.Type.heartbeat) {
            return;
        }
        String routingKey = RabbitStreamConfig.RK_TELEMETRY_STREAM;
        try {
            rabbitTemplate.convertAndSend(RabbitStreamConfig.EXCHANGE, routingKey, toBusPayload(envelope));
            published.increment();
        } catch (AmqpException e) {
            // Best-effort: a dropped stream frame costs one forecast tick, and
            // the SQS branch has already taken responsibility for durability.
            publishFailed.increment();
            log.warn("Could not publish stream frame for {} to {}: {}",
                    envelope.getDeviceUid(), routingKey, e.toString());
        }
    }

    /**
     * Flattens the envelope into the shape the AI worker consumes: reading
     * fields at the top level, plus {@code deviceUid}, {@code recordedAt} and a
     * {@code messageId} for idempotency.
     */
    static Map<String, Object> toBusPayload(StreamEnvelope envelope) {
        Map<String, Object> payload = new HashMap<>();
        if (envelope.getData() != null) {
            payload.putAll(envelope.getData());
        }
        payload.put("deviceUid", envelope.getDeviceUid());
        Instant when = envelope.getTimestamp() != null ? envelope.getTimestamp() : envelope.getReceivedAt();
        payload.put("recordedAt", when.toString());
        payload.putIfAbsent("messageId", UUID.randomUUID().toString());
        payload.put("streamSource", envelope.getSource());
        payload.put("frameType", envelope.getType().name());
        return payload;
    }
}
