package com.weatherplatform.deviceservice.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class FirmwareResponse {
    Long id;
    String version;
    String fileUrl;
    String checksum;
    Long fileSize;
    String releaseNotes;
    LocalDateTime createdAt;
}
