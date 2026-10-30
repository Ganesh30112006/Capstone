package com.weatherplatform.sensorservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One side-by-side observation: what a reference instrument read, and what the
 * sensor read at the same moment.
 *
 * The usual case sends only {@code referenceValue}: the operator reads the
 * reference beside the station and the sensor's own latest raw reading is paired
 * with it. Recording a calibration done earlier -- a bench certificate, say --
 * sends {@code calibratedAt} and must then send {@code sensorValue} too, since
 * the sensor's current reading says nothing about what it read back then.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalibrationRequest {

    @NotBlank(message = "sensorType is required")
    private String sensorType;

    @NotNull(message = "referenceValue is required")
    @Digits(integer = 6, fraction = 3, message = "referenceValue must have up to 6 integer digits and 3 decimals")
    private BigDecimal referenceValue;

    @Digits(integer = 6, fraction = 3, message = "sensorValue must have up to 6 integer digits and 3 decimals")
    private BigDecimal sensorValue;

    private LocalDateTime calibratedAt;

    @Size(max = 500, message = "notes must be at most 500 characters")
    private String notes;
}
