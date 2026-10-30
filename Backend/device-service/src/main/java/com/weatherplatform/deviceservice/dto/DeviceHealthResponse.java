package com.weatherplatform.deviceservice.dto;

import com.weatherplatform.deviceservice.entity.DeviceStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class DeviceHealthResponse {
    String deviceUid;
    DeviceStatus status;
    Integer batteryLevel;
    Integer wifiSignalStrength;
    String firmwareVersion;
    LocalDateTime lastSeenAt;
    boolean healthy;
    String message;
}
