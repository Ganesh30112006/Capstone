package com.weatherplatform.sensorservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensorDataRequest {

    /**
     * Producer-assigned unique ID, carried from the original MQTT payload.
     *
     * Optional — but when present it makes storage idempotent, so a redelivered
     * SQS message or a RabbitMQ retry cannot create a duplicate reading.
     */
    @Size(max = 100, message = "messageId must be at most 100 characters")
    private String messageId;

    @NotBlank(message = "deviceUid is required and cannot be blank")
    @Size(min = 3, max = 100, message = "deviceUid must be between 3 and 100 characters")
    private String deviceUid;

    @NotNull(message = "temperature is required")
    @Digits(integer = 3, fraction = 2, message = "temperature must have up to 3 integer digits and 2 decimals")
    private BigDecimal temperature;

    @NotNull(message = "humidity is required")
    @Digits(integer = 3, fraction = 2, message = "humidity must have up to 3 integer digits and 2 decimals")
    private BigDecimal humidity;

    @NotNull(message = "airQuality is required")
    @Min(value = 0, message = "airQuality index cannot be negative")
    private Integer airQuality;

    @NotNull(message = "pressure is required")
    @Digits(integer = 5, fraction = 2, message = "pressure must have up to 5 integer digits and 2 decimals")
    private BigDecimal pressure;

    @NotNull(message = "rain indicator is required")
    private Boolean rain;

    @Digits(integer = 3, fraction = 2, message = "oxygen must have up to 3 integer digits and 2 decimals")
    @DecimalMin(value = "0.00", message = "oxygen cannot be less than 0.00 %")
    @DecimalMax(value = "100.00", message = "oxygen cannot be greater than 100.00 %")
    private BigDecimal oxygen;

    private Double latitude;
    private Double longitude;

    @NotNull(message = "recordedAt timestamp is required")
    private LocalDateTime recordedAt;
}
