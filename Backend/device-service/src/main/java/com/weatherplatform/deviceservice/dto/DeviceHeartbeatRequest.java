package com.weatherplatform.deviceservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceHeartbeatRequest {

    private String deviceUid;

    @Min(value = 0, message = "batteryLevel must be at least 0")
    @Max(value = 100, message = "batteryLevel must be at most 100")
    private Integer batteryLevel;

    @Min(value = -120, message = "wifiSignalStrength must be at least -120 dBm")
    @Max(value = 0, message = "wifiSignalStrength must be at most 0 dBm")
    private Integer wifiSignalStrength;

    private String firmwareVersion;

    private LocalDateTime timestamp;
}
