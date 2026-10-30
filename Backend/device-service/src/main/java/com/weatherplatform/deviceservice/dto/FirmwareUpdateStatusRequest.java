package com.weatherplatform.deviceservice.dto;

import com.weatherplatform.deviceservice.entity.FirmwareUpdateStatus;
import jakarta.validation.constraints.NotNull;
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
public class FirmwareUpdateStatusRequest {

    @NotNull(message = "status is required")
    private FirmwareUpdateStatus status;

    private String errorMessage;
}
