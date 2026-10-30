package com.weatherplatform.sensorservice.event;

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
public class SensorFailureDetectedEvent implements Serializable {
    private String deviceUid;
    private String sensorType;
    private String status;
    private String failureReason;
    private String triggerValue;
    private LocalDateTime detectedAt;
}
