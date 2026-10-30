package com.weatherplatform.deviceservice.dto;

import com.weatherplatform.deviceservice.entity.DeviceStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceStatusUpdateRequest {

    @NotNull
    private DeviceStatus status;
}
