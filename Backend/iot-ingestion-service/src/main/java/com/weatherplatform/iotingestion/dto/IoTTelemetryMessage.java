package com.weatherplatform.iotingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Telemetry exactly as an ESP32 publishes it to
 * {@code weather/devices/{deviceUid}/telemetry}.
 *
 * The timestamp is an {@link Instant}, so the ISO-8601 offset the device sends
 * is honoured rather than silently reinterpreted as server-local time. A device
 * in a different zone — or a server whose zone is not UTC — would otherwise
 * corrupt every downstream aggregate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IoTTelemetryMessage {

    /** Device-assigned unique ID; the idempotency key for the whole pipeline. */
    private String messageId;

    @NotBlank(message = "Device UID is required")
    private String deviceUid;

    private BigDecimal temperature;
    private BigDecimal humidity;
    private Integer airQuality;
    private BigDecimal pressure;
    private Boolean rain;
    private BigDecimal oxygen;

    /** Station GPS position, carried through so analytics can build heatmaps. */
    private Double latitude;
    private Double longitude;

    @NotNull(message = "Timestamp is required")
    private Instant timestamp;
}
