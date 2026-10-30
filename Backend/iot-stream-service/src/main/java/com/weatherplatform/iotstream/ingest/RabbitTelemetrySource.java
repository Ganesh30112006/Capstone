package com.weatherplatform.iotstream.ingest;

import com.weatherplatform.iotstream.config.RabbitStreamConfig;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import com.weatherplatform.iotstream.service.TelemetryFanoutService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Local/CI telemetry source: mirrors the SQS branch's own events
 * ({@code iot.telemetry.received}, {@code device.heartbeat}) off the internal
 * bus so the fan-out and WebSocket paths can be exercised without an AWS IoT
 * Core endpoint.
 *
 * <p>This is still parallel to the backend in the sense that matters: it is its
 * own queue and its own consumer, never in sensor-service's processing chain.
 * In production {@code iot.stream.source=aws} replaces it with a direct broker
 * subscription.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "iot.stream.source", havingValue = "rabbit", matchIfMissing = true)
public class RabbitTelemetrySource implements TelemetrySource {

    private final StreamEnvelopeParser parser;
    private final TelemetryFanoutService fanout;
    private final RabbitListenerEndpointRegistry listenerRegistry;

    public RabbitTelemetrySource(StreamEnvelopeParser parser,
                                 TelemetryFanoutService fanout,
                                 RabbitListenerEndpointRegistry listenerRegistry) {
        this.parser = parser;
        this.fanout = fanout;
        this.listenerRegistry = listenerRegistry;
        log.info("Telemetry source: internal bus (iot.stream.source=rabbit)");
    }

    @RabbitListener(queues = RabbitStreamConfig.QUEUE_SOURCE_TELEMETRY)
    public void onTelemetry(Map<String, Object> body) {
        dispatch(body);
    }

    @RabbitListener(queues = RabbitStreamConfig.QUEUE_SOURCE_HEARTBEAT)
    public void onHeartbeat(Map<String, Object> body) {
        dispatch(body);
    }

    private void dispatch(Map<String, Object> body) {
        StreamEnvelope envelope = parser.parse(null, body, "rabbit");
        if (envelope != null) {
            fanout.accept(envelope);
        }
    }

    @Override
    public String name() {
        return "rabbit";
    }

    @Override
    public boolean isConnected() {
        return listenerRegistry.getListenerContainers().stream().anyMatch(c -> c.isRunning());
    }
}
