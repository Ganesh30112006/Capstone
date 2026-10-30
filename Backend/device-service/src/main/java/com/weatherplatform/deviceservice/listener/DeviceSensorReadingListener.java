package com.weatherplatform.deviceservice.listener;

import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.event.SensorReadingEvent;
import com.weatherplatform.deviceservice.service.DigitalTwinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSensorReadingListener {

    private final DigitalTwinService digitalTwinService;

    @RabbitListener(queues = RabbitMQConfig.DIGITAL_TWIN_QUEUE)
    public void handleSensorReading(SensorReadingEvent event) {
        log.debug("Digital twin received sensor reading for device: {}", event.getDeviceUid());
        try {
            digitalTwinService.updateSensorState(event);
        } catch (Exception e) {
            log.error("Failed to update digital twin for device {}", event.getDeviceUid(), e);
        }
    }
}
