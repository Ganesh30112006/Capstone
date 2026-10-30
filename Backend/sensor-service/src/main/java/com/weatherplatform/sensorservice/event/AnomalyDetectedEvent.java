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
public class AnomalyDetectedEvent implements Serializable {
    private String deviceUid;
    private String sensor;
    private Double value;
    private Double confidence;
    private String message;
    private LocalDateTime detectedAt;
}
