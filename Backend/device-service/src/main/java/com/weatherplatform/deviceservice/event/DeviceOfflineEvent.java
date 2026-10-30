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
public class DeviceOfflineEvent implements Serializable {
    private String deviceUid;
    private String deviceName;
    private LocalDateTime lastSeenAt;
    private LocalDateTime offlineDetectedAt;
}
