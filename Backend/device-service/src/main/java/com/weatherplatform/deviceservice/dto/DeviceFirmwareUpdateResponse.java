package com.weatherplatform.deviceservice.dto;

import com.weatherplatform.deviceservice.entity.FirmwareUpdateStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class DeviceFirmwareUpdateResponse {
    Long id;
    String deviceUid;
    String firmwareVersion;
    FirmwareUpdateStatus status;
    LocalDateTime startedAt;
    LocalDateTime completedAt;
    String errorMessage;
}
