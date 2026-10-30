package com.weatherplatform.analyticsservice.listener;

import com.weatherplatform.analyticsservice.config.RabbitMQConfig;
import com.weatherplatform.analyticsservice.event.SensorReadingEvent;
import com.weatherplatform.analyticsservice.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SensorReadingEventListener {

    private final AnalyticsService analyticsService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void handleSensorReading(SensorReadingEvent event) {
        log.info("Received SensorReadingCreated event for device: {}", event.getDeviceUid());
        try {
            analyticsService.processSensorReading(event);
        } catch (Exception e) {
            log.error("Failed to process sensor reading event for device: {}", event.getDeviceUid(), e);
        }
    }
}
