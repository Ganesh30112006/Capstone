package com.weatherplatform.iotstream.service;

import com.weatherplatform.iotstream.config.IotStreamProperties;
import com.weatherplatform.iotstream.config.RabbitStreamConfig;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import com.weatherplatform.iotstream.web.LiveSessionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

class TelemetryFanoutServiceTest {

    private RabbitTemplate rabbitTemplate;
    private LiveSessionRegistry registry;
    private IotStreamProperties properties;
    private TelemetryFanoutService service;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        registry = mock(LiveSessionRegistry.class);
        properties = new IotStreamProperties();
        service = new TelemetryFanoutService(rabbitTemplate, registry, properties, new SimpleMeterRegistry());
    }

    private StreamEnvelope telemetry() {
        Map<String, Object> data = new HashMap<>();
        data.put("temperature", 22.1);
        return StreamEnvelope.builder()
                .type(StreamEnvelope.Type.telemetry)
                .deviceUid("esp32-01")
                .timestamp(Instant.parse("2026-09-01T00:00:00Z"))
                .source("aws")
                .data(data)
                .build();
    }

    @Test
    void broadcastsToBrowsersAndLeavesTheBusAlone() {
        service.accept(telemetry());

        // This branch exists to show readings. Reasoning about them happens on
        // the backend branch, where the AI service consumes
        // sensor.reading.created alongside analytics, alerting and the digital
        // twin. Publishing here as well meant every reading was scored twice,
        // and with nothing bound to the key it would now return as unroutable.
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(registry).broadcast(any(StreamEnvelope.class));
    }

    @Test
    void heartbeatReachesBrowsersButNotTheBus() {
        StreamEnvelope hb = telemetry();
        hb.setType(StreamEnvelope.Type.heartbeat);

        service.accept(hb);

        // The bus branch feeds the AI, which scores measurements; a heartbeat
        // has none, and nothing is bound to a heartbeat stream key. Publishing
        // one returned it as unroutable and logged a warning per heartbeat.
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        // Browsers still need it: this is what the liveness indicator reads.
        verify(registry).broadcast(any(StreamEnvelope.class));
    }

    @Test
    void publishesToTheBusOnlyWhenThatBranchIsTurnedOn() {
        // The seam is kept: a deployment that wants a scorer on the live branch
        // can have one back without a code change.
        properties.getFanout().setPublishToRabbit(true);

        service.accept(telemetry());

        verify(rabbitTemplate).convertAndSend(eq(RabbitStreamConfig.EXCHANGE),
                eq(RabbitStreamConfig.RK_TELEMETRY_STREAM), any(Object.class));
    }

    @Test
    void brokerFailureDoesNotStopTheWebSocketBroadcast() {
        // Only meaningful with the bus branch on; off, there is no publish to fail.
        properties.getFanout().setPublishToRabbit(true);
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        service.accept(telemetry());

        verify(registry).broadcast(any(StreamEnvelope.class));
    }

    @Test
    void respectsFanoutToggles() {
        properties.getFanout().setPublishToRabbit(false);

        service.accept(telemetry());

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
        verify(registry).broadcast(any(StreamEnvelope.class));
    }

    @Test
    void ignoresFramesWithoutADevice() {
        service.accept(StreamEnvelope.builder().type(StreamEnvelope.Type.telemetry).build());

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
        verify(registry, never()).broadcast(any());
    }

    @Test
    void busPayloadIsFlattenedWithIdentityAndTimestamp() {
        Map<String, Object> payload = TelemetryFanoutService.toBusPayload(telemetry());

        assertThat(payload).containsEntry("deviceUid", "esp32-01");
        assertThat(payload).containsEntry("temperature", 22.1);
        assertThat(payload).containsEntry("recordedAt", "2026-09-01T00:00:00Z");
        assertThat(payload).containsKey("messageId");
        assertThat(payload).containsEntry("streamSource", "aws");
    }

    @Test
    void mirrorToClientsCanBeDisabled() {
        properties.getFanout().setForwardAiEvents(false);

        service.mirrorToClients(StreamEnvelope.builder()
                .type(StreamEnvelope.Type.prediction).deviceUid("esp32-01").build());

        verify(registry, never()).broadcast(any());
    }
}
