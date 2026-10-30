package com.weatherplatform.deviceservice.dto;

import com.weatherplatform.deviceservice.entity.DeviceStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class DeviceResponse {
    Long id;
    String deviceUid;
    String deviceName;
    String locationName;
    Double latitude;
    Double longitude;
    DeviceStatus status;
    Integer batteryLevel;
    Integer wifiSignalStrength;
    String firmwareVersion;
    LocalDateTime lastSeenAt;
    LocalDateTime registeredAt;
    LocalDateTime updatedAt;
}
