package com.weatherplatform.deviceservice.scheduler;

import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import com.weatherplatform.deviceservice.event.DeviceOfflineEvent;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled task that monitors device heartbeats.
 * Marks ONLINE devices as OFFLINE if they haven't reported
 * within the configured threshold, and publishes DeviceOfflineEvent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceHeartbeatScheduler {

    private final DeviceRepository deviceRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${device.heartbeat.offline-threshold-minutes:5}")
    private int offlineThresholdMinutes;

    @Scheduled(fixedDelayString = "${device.heartbeat.check-interval-ms:60000}")
    @Transactional
    public void checkDeviceHeartbeats() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(offlineThresholdMinutes);
        List<Device> timedOutDevices = deviceRepository.findByStatusAndLastSeenAtBefore(DeviceStatus.ONLINE, threshold);

        if (!timedOutDevices.isEmpty()) {
            for (Device device : timedOutDevices) {
                device.setStatus(DeviceStatus.OFFLINE);
                deviceRepository.save(device);
                log.warn("Device {} has not reported in {} minutes -> marked OFFLINE", device.getDeviceUid(), offlineThresholdMinutes);

                try {
                    DeviceOfflineEvent event = DeviceOfflineEvent.builder()
                            .deviceUid(device.getDeviceUid())
                            .deviceName(device.getDeviceName())
                            .lastSeenAt(device.getLastSeenAt())
                            .offlineDetectedAt(LocalDateTime.now())
                            .build();
                    rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY_DEVICE_OFFLINE, event);
                } catch (Exception e) {
                    log.error("Failed to publish DeviceOfflineEvent for device {}", device.getDeviceUid(), e);
                }
            }
        } else {
            log.debug("Heartbeat check: all ONLINE devices are reporting within threshold");
        }
    }
}
