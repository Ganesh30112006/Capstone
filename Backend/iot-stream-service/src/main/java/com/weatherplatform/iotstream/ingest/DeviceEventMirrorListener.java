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
 * Mirrors device lifecycle events onto the client WebSocket so the admin console
 * shows a station being registered, and a firmware rollout starting and
 * finishing, as they happen.
 *
 * <p>device-service published all three of these events from the beginning and
 * nothing was ever bound to them, so the broker discarded every one. Nothing
 * durable was lost -- the device row and the firmware-update row are committed
 * before the publish, and the REST API serves both -- but the live notification
 * was, which is why the console only ever learned about a new station by being
 * asked again.
 *
 * <p>Read-only and best-effort, like {@link AiEventMirrorListener}. If this
 * service is down the events are simply not mirrored; the database remains the
 * record either way.
 */
@Slf4j
@Component
public class DeviceEventMirrorListener {

    private final TelemetryFanoutService fanout;

    public DeviceEventMirrorListener(TelemetryFanoutService fanout) {
        this.fanout = fanout;
    }

    @RabbitListener(queues = RabbitStreamConfig.QUEUE_DEVICE_MIRROR)
    public void onDeviceEvent(Map<String, Object> body,
                              @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        StreamEnvelope.Type type = typeOf(routingKey);
        if (type == null) {
            // Bound to a key this listener does not understand. Say so rather
            // than mirroring it under a type the console would misread.
            log.warn("Ignoring device event on unexpected routing key '{}'", routingKey);
            return;
        }

        StreamEnvelope envelope = StreamEnvelope.builder()
                .type(type)
                .deviceUid(str(body.get("deviceUid")))
                .timestamp(StreamEnvelopeParser.parseTimestamp(
                        body.get("registeredAt"), body.get("requestedAt"),
                        body.get("completedAt"), body.get("timestamp")))
                .receivedAt(Instant.now())
                .source("device")
                .data(body)
                .build();

        fanout.mirrorDeviceEvent(envelope);
    }

    /** Null for a key this listener has no frame type for. */
    static StreamEnvelope.Type typeOf(String routingKey) {
        if (routingKey == null) {
            return null;
        }
        return switch (routingKey) {
            case RabbitStreamConfig.RK_DEVICE_REGISTERED -> StreamEnvelope.Type.deviceRegistered;
            case RabbitStreamConfig.RK_FIRMWARE_REQUESTED -> StreamEnvelope.Type.firmwareRequested;
            case RabbitStreamConfig.RK_FIRMWARE_COMPLETED -> StreamEnvelope.Type.firmwareCompleted;
            default -> null;
        };
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
