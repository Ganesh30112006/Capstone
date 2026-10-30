package com.weatherplatform.sensorservice.dto;

import com.weatherplatform.sensorservice.calibration.CalibrationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalibrationResponse {
    private Long id;
    private String deviceUid;
    private String sensorType;
    private BigDecimal referenceValue;
    private BigDecimal sensorValue;
    private BigDecimal offset;
    private LocalDateTime calibratedAt;
    private LocalDateTime validUntil;
    private String performedBy;
    private String notes;
    /** Where this record stands now. Only the newest record per sensor is the one in force. */
    private CalibrationStatus status;
}
