package com.weatherplatform.iotingestion.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weatherplatform.iotingestion.config.RabbitMQConfig;
import com.weatherplatform.iotingestion.dto.IoTHeartbeatMessage;
import com.weatherplatform.iotingestion.dto.IoTTelemetryMessage;
import com.weatherplatform.iotingestion.event.DeviceHeartbeatEvent;
import com.weatherplatform.iotingestion.event.TelemetryReceivedEvent;
import com.weatherplatform.iotingestion.exception.InvalidTelemetryException;
import com.weatherplatform.iotingestion.service.DuplicateDetector;
import com.weatherplatform.iotingestion.service.TelemetryValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsMessageProcessorTest {

    @Mock
    private TelemetryValidator validator;

    @Mock
    private DuplicateDetector duplicateDetector;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private MeterRegistry meterRegistry;
    private SqsMessageProcessor processor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // java.time support
        processor = new SqsMessageProcessor(objectMapper, validator, duplicateDetector, rabbitTemplate, meterRegistry);
    }

    private String telemetryPayload(String messageId, Instant timestamp) throws Exception {
        IoTTelemetryMessage msg = new IoTTelemetryMessage();
        msg.setMessageId(messageId);
        msg.setDeviceUid("STATION-001");
        msg.setTimestamp(timestamp);
        msg.setTemperature(BigDecimal.valueOf(31.4));
        msg.setHumidity(BigDecimal.valueOf(67.8));
        msg.setAirQuality(82);
        msg.setPressure(BigDecimal.valueOf(1007.6));
        msg.setOxygen(BigDecimal.valueOf(20.8));
        msg.setRain(false);
        msg.setLatitude(15.5057);
        msg.setLongitude(80.0499);
        return objectMapper.writeValueAsString(msg);
    }

    private TelemetryReceivedEvent capturePublishedTelemetry() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_NAME),
                eq(RabbitMQConfig.ROUTING_KEY_TELEMETRY_RECEIVED),
                captor.capture(),
                any(MessagePostProcessor.class));
        return (TelemetryReceivedEvent) captor.getValue();
    }

    @Test
    @DisplayName("Valid telemetry is forwarded on the raw-telemetry routing key")
    void forwardsValidTelemetry() throws Exception {
        when(duplicateDetector.isDuplicate("sqs-msg-123")).thenReturn(false);

        processor.processTelemetryMessage(telemetryPayload("sqs-msg-123", Instant.now()));

        verify(validator, times(1)).validate(any(IoTTelemetryMessage.class));
        assertEquals(1, meterRegistry.counter("iot.messages.received", "type", "telemetry").count());
        assertEquals(1, meterRegistry.counter("iot.messages.published", "type", "telemetry").count());
    }

    @Test
    @DisplayName("The canonical payload keeps messageId and GPS through the hop")
    void preservesMessageIdAndCoordinates() throws Exception {
        when(duplicateDetector.isDuplicate(anyString())).thenReturn(false);

        processor.processTelemetryMessage(telemetryPayload("sqs-msg-123", Instant.now()));

        TelemetryReceivedEvent event = capturePublishedTelemetry();
        assertEquals("sqs-msg-123", event.getMessageId());
        assertEquals("STATION-001", event.getDeviceUid());
        assertEquals(15.5057, event.getLatitude());
        assertEquals(80.0499, event.getLongitude());
        assertEquals(BigDecimal.valueOf(20.8), event.getOxygen());
        assertNotNull(event.getRecordedAt());
    }

    @Test
    @DisplayName("An offset timestamp is normalised to UTC, not reinterpreted as local time")
    void normalisesTimestampToUtc() throws Exception {
        when(duplicateDetector.isDuplicate(anyString())).thenReturn(false);
        Instant sentAt = Instant.parse("2026-08-31T18:30:00Z");

        processor.processTelemetryMessage(telemetryPayload("sqs-msg-utc", sentAt));

        TelemetryReceivedEvent event = capturePublishedTelemetry();
        assertEquals(LocalDateTime.ofInstant(sentAt, ZoneOffset.UTC), event.getRecordedAt());
    }

    @Test
    @DisplayName("A duplicate is consumed without republishing")
    void suppressesDuplicateTelemetry() throws Exception {
        when(duplicateDetector.isDuplicate("sqs-msg-123")).thenReturn(true);

        processor.processTelemetryMessage(telemetryPayload("sqs-msg-123", Instant.now()));

        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
        assertEquals(1, meterRegistry.counter("iot.messages.duplicates", "type", "telemetry").count());
    }

    @Test
    @DisplayName("A device that omits messageId still gets a deterministic ID")
    void derivesStableIdWhenMessageIdMissing() throws Exception {
        Instant timestamp = Instant.parse("2026-08-31T18:30:00Z");
        when(duplicateDetector.isDuplicate(anyString())).thenReturn(false);

        processor.processTelemetryMessage(telemetryPayload(null, timestamp));

        TelemetryReceivedEvent event = capturePublishedTelemetry();
        // Deterministic, so a redelivery of the same reading collapses to one row.
        assertEquals("STATION-001-" + timestamp.toEpochMilli(), event.getMessageId());
    }

    @Test
    @DisplayName("An invalid payload propagates so SQS can redrive it to the DLQ")
    void invalidPayloadIsRethrownForRedrive() throws Exception {
        doThrow(new InvalidTelemetryException("Missing deviceUid"))
                .when(validator).validate(any(IoTTelemetryMessage.class));

        String payload = telemetryPayload("sqs-msg-bad", Instant.now());

        assertThrows(InvalidTelemetryException.class, () -> processor.processTelemetryMessage(payload));
        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
        assertEquals(1, meterRegistry.counter("iot.messages.invalid", "type", "telemetry").count());
    }

    @Test
    @DisplayName("Unparseable JSON is not acknowledged away")
    void malformedJsonIsRethrown() {
        assertThrows(IllegalStateException.class, () -> processor.processTelemetryMessage("{not-json"));
        assertEquals(1, meterRegistry.counter("iot.messages.failed", "type", "telemetry").count());
    }

    @Test
    @DisplayName("A broker outage must not silently drop the message")
    void brokerFailureIsRethrown() throws Exception {
        when(duplicateDetector.isDuplicate(anyString())).thenReturn(false);
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(
                        anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));

        String payload = telemetryPayload("sqs-msg-broker", Instant.now());

        assertThrows(IllegalStateException.class, () -> processor.processTelemetryMessage(payload));
        assertEquals(1, meterRegistry.counter("iot.messages.failed", "type", "telemetry").count());
    }

    @Test
    @DisplayName("Heartbeats are forwarded on their own routing key")
    void forwardsHeartbeat() throws Exception {
        IoTHeartbeatMessage msg = new IoTHeartbeatMessage();
        msg.setMessageId("hb-1");
        msg.setDeviceUid("STATION-001");
        msg.setTimestamp(Instant.now());
        msg.setBatteryLevel(87);
        msg.setWifiSignalStrength(-52);
        msg.setFirmwareVersion("v2.8.4");

        when(duplicateDetector.isDuplicate("hb-1")).thenReturn(false);

        processor.processHeartbeatMessage(objectMapper.writeValueAsString(msg));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_NAME),
                eq(RabbitMQConfig.ROUTING_KEY_DEVICE_HEARTBEAT),
                captor.capture(),
                any(MessagePostProcessor.class));

        DeviceHeartbeatEvent event = (DeviceHeartbeatEvent) captor.getValue();
        assertEquals("STATION-001", event.getDeviceUid());
        assertEquals(87, event.getBatteryLevel());
        assertEquals(-52, event.getWifiSignalStrength());
        assertEquals("v2.8.4", event.getFirmwareVersion());
    }

    @Test
    @DisplayName("A heartbeat with no clock reading is stamped on arrival")
    void stampsHeartbeatWithoutTimestamp() throws Exception {
        IoTHeartbeatMessage msg = new IoTHeartbeatMessage();
        msg.setMessageId("hb-2");
        msg.setDeviceUid("STATION-001");
        msg.setTimestamp(null);
        msg.setBatteryLevel(64);

        when(duplicateDetector.isDuplicate("hb-2")).thenReturn(false);

        processor.processHeartbeatMessage(objectMapper.writeValueAsString(msg));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                anyString(), anyString(), captor.capture(), any(MessagePostProcessor.class));

        DeviceHeartbeatEvent event = (DeviceHeartbeatEvent) captor.getValue();
        assertNotNull(event.getTimestamp());
        // Stamped now, so it should be within a hair of the current UTC clock.
        assertEquals(0, ChronoUnit.MINUTES.between(
                event.getTimestamp(), LocalDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * The tests above mock the duplicate detector, which is precisely why the
     * defect these cover went unnoticed: with a mocked detector a redelivery
     * reports whatever the stub is told to report, so the interaction between a
     * failed publish and the dedupe cache is never exercised.
     *
     * <p>Here the detector is real. A sixty-second broker outage under live
     * traffic destroyed 248 messages through that interaction -- the claim taken
     * by the check outlived the publish that failed, so SQS redelivered the
     * message, the cache called it a duplicate, and it was acknowledged and
     * deleted without ever reaching the bus.
     */
    @Nested
    @DisplayName("Redelivery after a failed publish")
    class RedeliveryAfterFailure {

        private SqsMessageProcessor realProcessor;

        @BeforeEach
        void useARealDedupeCache() {
            realProcessor = new SqsMessageProcessor(
                    objectMapper, validator, new DuplicateDetector(300, 1000), rabbitTemplate, meterRegistry);
        }

        private void brokerIsDown() {
            doThrow(new AmqpException("broker unavailable"))
                    .when(rabbitTemplate).convertAndSend(
                            anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
        }

        @Test
        @DisplayName("a reading interrupted by a broker outage is published when SQS redelivers it")
        void telemetryInterruptedByAnOutageSurvives() throws Exception {
            String payload = telemetryPayload("sqs-msg-outage", Instant.now());

            brokerIsDown();
            assertThrows(IllegalStateException.class, () -> realProcessor.processTelemetryMessage(payload));

            // Broker is back, and SQS redelivers the message it was never told to delete.
            reset(rabbitTemplate);
            realProcessor.processTelemetryMessage(payload);

            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_NAME),
                    eq(RabbitMQConfig.ROUTING_KEY_TELEMETRY_RECEIVED),
                    any(Object.class),
                    any(MessagePostProcessor.class));
        }

        @Test
        @DisplayName("a heartbeat interrupted by a broker outage is published when SQS redelivers it")
        void heartbeatInterruptedByAnOutageSurvives() throws Exception {
            IoTHeartbeatMessage msg = new IoTHeartbeatMessage();
            msg.setMessageId("hb-outage");
            msg.setDeviceUid("STATION-001");
            msg.setTimestamp(Instant.now());
            msg.setBatteryLevel(71);
            String payload = objectMapper.writeValueAsString(msg);

            brokerIsDown();
            assertThrows(IllegalStateException.class, () -> realProcessor.processHeartbeatMessage(payload));

            reset(rabbitTemplate);
            realProcessor.processHeartbeatMessage(payload);

            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_NAME),
                    eq(RabbitMQConfig.ROUTING_KEY_DEVICE_HEARTBEAT),
                    any(Object.class),
                    any(MessagePostProcessor.class));
        }

        @Test
        @DisplayName("a genuine redelivery of an already published reading is still suppressed")
        void realDuplicatesAreStillSuppressed() throws Exception {
            String payload = telemetryPayload("sqs-msg-twice", Instant.now());

            realProcessor.processTelemetryMessage(payload);
            realProcessor.processTelemetryMessage(payload);

            // Published exactly once: releasing a claim on failure must not
            // weaken suppression on the path where the publish succeeded.
            verify(rabbitTemplate, times(1)).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_NAME),
                    eq(RabbitMQConfig.ROUTING_KEY_TELEMETRY_RECEIVED),
                    any(Object.class),
                    any(MessagePostProcessor.class));
            assertEquals(1, meterRegistry.counter("iot.messages.duplicates", "type", "telemetry").count());
        }
    }
}
