package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DigitalTwinResponse;
import com.weatherplatform.deviceservice.event.DeviceHeartbeatEvent;
import com.weatherplatform.deviceservice.event.SensorReadingEvent;

/**
 * The device's shadow: the platform's best current belief about a physical
 * station, assembled from two independent event streams.
 *
 * Telemetry updates the sensor readings; heartbeats update the connectivity
 * and power picture. Either can arrive without the other, so both paths must
 * write the twin independently.
 */
public interface DigitalTwinService {

    DigitalTwinResponse getDigitalTwin(String deviceUid);

    void updateSensorState(SensorReadingEvent event);

    void updateHeartbeatState(DeviceHeartbeatEvent event);
}
