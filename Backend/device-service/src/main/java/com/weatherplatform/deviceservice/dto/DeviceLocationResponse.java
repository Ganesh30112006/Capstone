package com.weatherplatform.deviceservice.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class DeviceLocationResponse {
    String deviceUid;
    String locationName;
    Double latitude;
    Double longitude;
    LocalDateTime updatedAt;
}
