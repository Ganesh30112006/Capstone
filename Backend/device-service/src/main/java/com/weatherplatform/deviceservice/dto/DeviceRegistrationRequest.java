package com.weatherplatform.deviceservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegistrationRequest {

    @NotBlank(message = "deviceUid is required")
    @Size(min = 3, max = 100, message = "deviceUid must be between 3 and 100 characters")
    private String deviceUid;

    @NotBlank(message = "deviceName is required")
    @Size(min = 3, max = 150, message = "deviceName must be between 3 and 150 characters")
    private String deviceName;

    @NotBlank(message = "locationName is required")
    @Size(min = 2, max = 200, message = "locationName must be between 2 and 200 characters")
    private String locationName;

    private Double latitude;
    private Double longitude;
    private String firmwareVersion;
}
