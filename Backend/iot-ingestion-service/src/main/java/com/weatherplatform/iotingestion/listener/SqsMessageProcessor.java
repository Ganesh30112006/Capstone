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
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Bridges AWS IoT Core to the internal event bus.
 *
 * <pre>
 * ESP32 → MQTT/TLS → AWS IoT Core → IoT Rule → SQS → [this] → RabbitMQ
 * </pre>
 *
 * The service is intentionally thin: parse, validate structurally, and forward.
 * It owns no database and makes no business decisions, so it can scale
 * independently of the services that interpret the data.
 *
 * Failure handling is deliberate. Anything thrown here returns the message to
 * SQS, which retries it and — once {@code maxReceiveCount} is exhausted —
 * moves it to the dead-letter queue rather than dropping it. That applies to
 * malformed payloads too: a poison message belongs in the DLQ where it can be
 * inspected, not silently discarded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqsMessageProcessor {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_MDC = "correlationId";

    private final ObjectMapper objectMapper;
    private final TelemetryValidator validator;
    private final DuplicateDetector duplicateDetector;
    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;

    @SqsListener("${iot.sqs.telemetry-queue}")
    public void processTelemetryMessage(String payload) {
        meterRegistry.counter("iot.messages.received", "type", "telemetry").increment();

        String correlationId = UUID.randomUUID().toString();
        MDC.put(CORRELATION_MDC, correlationId);

        // Held outside the try so the failure paths can release the dedupe claim
        // this message took. A claim that outlives a failed publish turns the
        // SQS redelivery into a "duplicate" and deletes the reading.
        String messageId = null;

        try {
            IoTTelemetryMessage msg = objectMapper.readValue(payload, IoTTelemetryMessage.class);
            validator.validate(msg);

            // First-line duplicate filter. It is an optimisation only — it is
            // per-instance and cleared on restart. The authoritative guarantee is
            // the unique constraint on sensor_data.message_id downstream.
            messageId = msg.getMessageId() != null && !msg.getMessageId().isBlank()
                    ? msg.getMessageId()
                    : fallbackId(msg.getDeviceUid(), msg.getTimestamp());

            if (duplicateDetector.isDuplicate(messageId)) {
                log.debug("Duplicate telemetry {} for device {} suppressed", messageId, msg.getDeviceUid());
                meterRegistry.counter("iot.messages.duplicates", "type", "telemetry").increment();
                return; // Consume and acknowledge — the message is already in flight.
            }

            TelemetryReceivedEvent event = TelemetryReceivedEvent.builder()
                    .messageId(messageId)
                    .deviceUid(msg.getDeviceUid())
                    .temperature(msg.getTemperature())
                    .humidity(msg.getHumidity())
                    .airQuality(msg.getAirQuality())
                    .pressure(msg.getPressure())
                    .rain(msg.getRain())
                    .oxygen(msg.getOxygen())
                    .latitude(msg.getLatitude())
                    .longitude(msg.getLongitude())
                    .recordedAt(toUtc(msg.getTimestamp()))
                    .build();

            publish(RabbitMQConfig.ROUTING_KEY_TELEMETRY_RECEIVED, event, correlationId);

            log.info("Forwarded telemetry {} for device {}", messageId, msg.getDeviceUid());
            meterRegistry.counter("iot.messages.published", "type", "telemetry").increment();

        } catch (InvalidTelemetryException e) {
            // Structurally unusable. Retrying cannot fix it, but it still goes to
            // the DLQ via SQS redrive so nothing is lost without a trace.
            log.error("Invalid telemetry payload: {}", e.getMessage());
            meterRegistry.counter("iot.messages.invalid", "type", "telemetry").increment();
            duplicateDetector.forget(messageId);
            throw e;
        } catch (Exception e) {
            log.error("Failed to process telemetry message", e);
            meterRegistry.counter("iot.messages.failed", "type", "telemetry").increment();
            duplicateDetector.forget(messageId);
            throw new IllegalStateException("SQS telemetry processing failed", e);
        } finally {
            MDC.remove(CORRELATION_MDC);
        }
    }

    @SqsListener("${iot.sqs.heartbeat-queue}")
    public void processHeartbeatMessage(String payload) {
        meterRegistry.counter("iot.messages.received", "type", "heartbeat").increment();

        String correlationId = UUID.randomUUID().toString();
        MDC.put(CORRELATION_MDC, correlationId);

        String messageId = null;

        try {
            IoTHeartbeatMessage msg = objectMapper.readValue(payload, IoTHeartbeatMessage.class);
            validator.validate(msg);

            // A heartbeat with no clock reading is still meaningful: it proves the
            // station is alive right now, so stamp it on arrival.
            Instant timestamp = msg.getTimestamp() != null ? msg.getTimestamp() : Instant.now();

            messageId = msg.getMessageId() != null && !msg.getMessageId().isBlank()
                    ? msg.getMessageId()
                    : fallbackId(msg.getDeviceUid() + "-hb", timestamp);

            if (duplicateDetector.isDuplicate(messageId)) {
                log.debug("Duplicate heartbeat {} for device {} suppressed", messageId, msg.getDeviceUid());
                meterRegistry.counter("iot.messages.duplicates", "type", "heartbeat").increment();
                return;
            }

            DeviceHeartbeatEvent event = DeviceHeartbeatEvent.builder()
                    .messageId(messageId)
                    .deviceUid(msg.getDeviceUid())
                    .batteryLevel(msg.getBatteryLevel())
                    .wifiSignalStrength(msg.getWifiSignalStrength())
                    .firmwareVersion(msg.getFirmwareVersion())
                    .timestamp(toUtc(timestamp))
                    .build();

            publish(RabbitMQConfig.ROUTING_KEY_DEVICE_HEARTBEAT, event, correlationId);

            log.info("Forwarded heartbeat {} for device {}", messageId, msg.getDeviceUid());
            meterRegistry.counter("iot.messages.published", "type", "heartbeat").increment();

        } catch (InvalidTelemetryException e) {
            log.error("Invalid heartbeat payload: {}", e.getMessage());
            meterRegistry.counter("iot.messages.invalid", "type", "heartbeat").increment();
            duplicateDetector.forget(messageId);
            throw e;
        } catch (Exception e) {
            log.error("Failed to process heartbeat message", e);
            meterRegistry.counter("iot.messages.failed", "type", "heartbeat").increment();
            duplicateDetector.forget(messageId);
            throw new IllegalStateException("SQS heartbeat processing failed", e);
        } finally {
            MDC.remove(CORRELATION_MDC);
        }
    }

    private void publish(String routingKey, Object event, String correlationId) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, routingKey, event, message -> {
            message.getMessageProperties().setHeader(CORRELATION_HEADER, correlationId);
            return message;
        });
    }

    /** Normalises to UTC so no consumer has to guess a zone. */
    private LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Derives a stable ID for a device that omits messageId. Device + timestamp
     * is deterministic, so a redelivery of the same reading still collapses to
     * one row rather than becoming a new "unique" message every time.
     */
    private String fallbackId(String deviceUid, Instant timestamp) {
        return deviceUid + "-" + timestamp.toEpochMilli();
    }
}
