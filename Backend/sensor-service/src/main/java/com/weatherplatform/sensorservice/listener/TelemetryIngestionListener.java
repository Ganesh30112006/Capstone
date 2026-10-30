package com.weatherplatform.sensorservice.listener;

import com.weatherplatform.sensorservice.config.RabbitMQConfig;
import com.weatherplatform.sensorservice.dto.SensorDataRequest;
import com.weatherplatform.sensorservice.event.TelemetryReceivedEvent;
import com.weatherplatform.sensorservice.service.SensorDataService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Persists telemetry arriving from AWS IoT Core via the ingestion service.
 *
 * This is the step that makes the platform's data plane real: ingestion only
 * validates and forwards, so without this consumer nothing would ever land in
 * sensor_db and every history, statistics and analytics query would be empty.
 *
 * The pipeline is deliberately linear here and fanned out afterwards:
 * <pre>
 * SQS → ingestion → iot.telemetry.received → [this] → sensor.reading.created
 *                                                        ├→ analytics
 *                                                        ├→ alerting
 *                                                        ├→ digital twin
 *                                                        └→ AI worker
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryIngestionListener {

    private final SensorDataService sensorDataService;
    private final MeterRegistry meterRegistry;

    @RabbitListener(queues = RabbitMQConfig.TELEMETRY_QUEUE)
    public void handleTelemetry(TelemetryReceivedEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);

        if (event == null || event.getDeviceUid() == null || event.getDeviceUid().isBlank()) {
            meterRegistry.counter("sensor.telemetry.rejected", "reason", "missing_device_uid").increment();
            throw new AmqpRejectAndDontRequeueException("Telemetry event is missing deviceUid");
        }
        if (event.getRecordedAt() == null) {
            meterRegistry.counter("sensor.telemetry.rejected", "reason", "missing_timestamp").increment();
            throw new AmqpRejectAndDontRequeueException(
                    "Telemetry event for " + event.getDeviceUid() + " is missing recordedAt");
        }

        try {
            SensorDataRequest request = SensorDataRequest.builder()
                    .messageId(event.getMessageId())
                    .deviceUid(event.getDeviceUid())
                    .temperature(event.getTemperature())
                    .humidity(event.getHumidity())
                    .airQuality(event.getAirQuality())
                    .pressure(event.getPressure())
                    .rain(event.getRain())
                    .oxygen(event.getOxygen())
                    .latitude(event.getLatitude())
                    .longitude(event.getLongitude())
                    .recordedAt(event.getRecordedAt())
                    .build();

            sensorDataService.storeSensorData(request);
            meterRegistry.counter("sensor.telemetry.stored").increment();

        } catch (Exception e) {
            // Transient failures (DB unavailable, lock contention) are retried by
            // the listener policy and dead-lettered only once retries are spent.
            log.error("Failed to store telemetry for device {}", event.getDeviceUid(), e);
            meterRegistry.counter("sensor.telemetry.failed").increment();
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("sensor.telemetry.processing"));
        }
    }
}
