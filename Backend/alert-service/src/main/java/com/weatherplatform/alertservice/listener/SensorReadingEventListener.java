package com.weatherplatform.alertservice.listener;

import com.weatherplatform.alertservice.config.RabbitMQConfig;
import com.weatherplatform.alertservice.event.AnomalyDetectedEvent;
import com.weatherplatform.alertservice.event.DeviceOfflineEvent;
import com.weatherplatform.alertservice.event.SensorFailureDetectedEvent;
import com.weatherplatform.alertservice.event.SensorReadingEvent;
import com.weatherplatform.alertservice.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SensorReadingEventListener {

    private final AlertService alertService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_SENSOR_READINGS)
    public void handleSensorReading(SensorReadingEvent event) {
        log.info("Received SensorReadingEvent for alerts on device: {}", event.getDeviceUid());
        try {
            alertService.processSensorReading(event);
        } catch (Exception e) {
            log.error("Failed to process sensor reading for alerts on device: {}", event.getDeviceUid(), e);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_SENSOR_FAILURES)
    public void handleSensorFailure(SensorFailureDetectedEvent event) {
        log.warn("Received SensorFailureDetectedEvent for alerts on device: {}", event.getDeviceUid());
        try {
            alertService.processSensorFailure(event);
        } catch (Exception e) {
            log.error("Failed to process sensor failure for alerts on device: {}", event.getDeviceUid(), e);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_ANOMALIES)
    public void handleAnomaly(AnomalyDetectedEvent event) {
        log.warn("Received AnomalyDetectedEvent for alerts on device: {}", event.getDeviceUid());
        try {
            alertService.processAnomaly(event);
        } catch (Exception e) {
            log.error("Failed to process anomaly for alerts on device: {}", event.getDeviceUid(), e);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_DEVICE_OFFLINE)
    public void handleDeviceOffline(DeviceOfflineEvent event) {
        log.warn("Received DeviceOfflineEvent for alerts on device: {}", event.getDeviceUid());
        try {
            alertService.processDeviceOffline(event);
        } catch (Exception e) {
            log.error("Failed to process device offline for alerts on device: {}", event.getDeviceUid(), e);
        }
    }
}
