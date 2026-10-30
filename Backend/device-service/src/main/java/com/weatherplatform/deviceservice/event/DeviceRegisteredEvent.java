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
public class DeviceRegisteredEvent implements Serializable {
    private String deviceUid;
    private String deviceName;
    private String locationName;
    private Double latitude;
    private Double longitude;
    private LocalDateTime registeredAt;
}
