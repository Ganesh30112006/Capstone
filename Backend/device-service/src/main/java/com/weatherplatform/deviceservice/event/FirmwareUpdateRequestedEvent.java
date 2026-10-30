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
public class FirmwareUpdateRequestedEvent implements Serializable {
    private Long updateId;
    private String deviceUid;
    private String firmwareVersion;
    private String fileUrl;
    private String checksum;
    private LocalDateTime requestedAt;
}
