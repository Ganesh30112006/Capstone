package com.weatherplatform.deviceservice.listener;

import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.dto.DeviceHeartbeatRequest;
import com.weatherplatform.deviceservice.event.DeviceHeartbeatEvent;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.service.DeviceService;
import com.weatherplatform.deviceservice.service.DigitalTwinService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Applies device heartbeats arriving from AWS IoT Core via the ingestion service.
 *
 * Heartbeats are what keep a station's last-seen timestamp, battery, Wi-Fi and
 * reported firmware current. Without this consumer a station that only sends
 * heartbeats (and no telemetry) would be swept OFFLINE by the scheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceHeartbeatListener {

    private final DeviceService deviceService;
    private final DigitalTwinService digitalTwinService;
    private final MeterRegistry meterRegistry;

    @RabbitListener(queues = RabbitMQConfig.HEARTBEAT_QUEUE)
    public void handleHeartbeat(DeviceHeartbeatEvent event) {
        if (event == null || event.getDeviceUid() == null || event.getDeviceUid().isBlank()) {
            // Structurally invalid: retrying cannot help, so reject straight to the DLQ.
            throw new AmqpRejectAndDontRequeueException("Heartbeat event is missing deviceUid");
        }

        log.debug("Heartbeat received for device {}", event.getDeviceUid());

        DeviceHeartbeatRequest request = DeviceHeartbeatRequest.builder()
                .deviceUid(event.getDeviceUid())
                .batteryLevel(event.getBatteryLevel())
                .wifiSignalStrength(event.getWifiSignalStrength())
                .firmwareVersion(event.getFirmwareVersion())
                .timestamp(event.getTimestamp())
                .build();

        try {
            // applyHeartbeat, not processHeartbeat: this heartbeat arrived from
            // the bus, so re-publishing it would feed this very listener.
            deviceService.applyHeartbeat(event.getDeviceUid(), request);
            digitalTwinService.updateHeartbeatState(event);
            meterRegistry.counter("device.heartbeat.applied").increment();
        } catch (ResourceNotFoundException e) {
            // An unregistered station is a provisioning problem, not a transient
            // fault — redelivering it forever would only fill the queue.
            log.warn("Heartbeat for unregistered device {} discarded", event.getDeviceUid());
            meterRegistry.counter("device.heartbeat.unknown_device").increment();
            throw new AmqpRejectAndDontRequeueException(e);
        } catch (Exception e) {
            // Transient (DB blip, lock contention): let the retry policy work,
            // then dead-letter if it never succeeds.
            log.error("Failed to apply heartbeat for device {}", event.getDeviceUid(), e);
            meterRegistry.counter("device.heartbeat.failed").increment();
            throw e;
        }
    }
}
