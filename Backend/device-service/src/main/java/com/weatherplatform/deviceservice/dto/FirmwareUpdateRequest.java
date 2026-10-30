package com.weatherplatform.deviceservice.dto;

import jakarta.validation.constraints.NotBlank;
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
public class FirmwareUpdateRequest {

    @NotBlank(message = "firmwareVersion is required")
    private String firmwareVersion;
}
