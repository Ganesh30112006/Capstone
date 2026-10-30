package com.weatherplatform.deviceservice.event;

import com.weatherplatform.deviceservice.entity.FirmwareUpdateStatus;
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
public class FirmwareUpdateCompletedEvent implements Serializable {
    private Long updateId;
    private String deviceUid;
    private String firmwareVersion;
    private FirmwareUpdateStatus status;
    private LocalDateTime completedAt;
    private String errorMessage;
}
