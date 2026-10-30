package com.weatherplatform.deviceservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
public class FirmwareCreateRequest {

    @NotBlank(message = "version is required (e.g. 1.0.4)")
    private String version;

    @NotBlank(message = "fileUrl is required")
    private String fileUrl;

    @NotBlank(message = "checksum is required")
    private String checksum;

    @NotNull(message = "fileSize is required")
    @Positive(message = "fileSize must be positive")
    private Long fileSize;

    private String releaseNotes;
}
