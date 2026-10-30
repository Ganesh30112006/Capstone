package com.weatherplatform.deviceservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceHeartbeatEvent implements Serializable {
    private String deviceUid;
    private Integer batteryLevel;
    private Integer wifiSignalStrength;
    private String firmwareVersion;
    private LocalDateTime timestamp;
}
