package com.weatherplatform.iotstream.ingest;

import com.weatherplatform.iotstream.config.RabbitStreamConfig;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import com.weatherplatform.iotstream.service.TelemetryFanoutService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Mirrors the AI service's output back onto the client WebSocket so the admin
 * console shows predictions, reports and anomalies as they are produced.
 *
 * <p>Read-only and best-effort: these events have their own durable consumers
 * (analytics, alerting). This listener exists only so a browser that is already
 * watching the live telemetry also sees the AI's take on it, on the same socket.
 */
@Slf4j
@Component
public class AiEventMirrorListener {

    private final TelemetryFanoutService fanout;

    public AiEventMirrorListener(TelemetryFanoutService fanout) {
        this.fanout = fanout;
    }

    @RabbitListener(queues = RabbitStreamConfig.QUEUE_AI_MIRROR)
    public void onAiEvent(Map<String, Object> body,
                          @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        StreamEnvelope.Type type = switch (routingKey) {
            case RabbitStreamConfig.RK_PREDICTION -> StreamEnvelope.Type.prediction;
            case RabbitStreamConfig.RK_REPORT -> StreamEnvelope.Type.report;
            default -> StreamEnvelope.Type.anomaly;
        };

        StreamEnvelope envelope = StreamEnvelope.builder()
                .type(type)
                .deviceUid(str(body.get("deviceUid")))
                .timestamp(StreamEnvelopeParser.parseTimestamp(
                        body.get("detectedAt"), body.get("generatedAt"),
                        body.get("windowEnd"), body.get("timestamp")))
                .receivedAt(Instant.now())
                .source("ai")
                .data(body)
                .build();

        fanout.mirrorToClients(envelope);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
