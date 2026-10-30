package com.weatherplatform.sensorservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Canonical telemetry payload as it leaves the IoT ingestion service.
 *
 * Mirrors the MQTT contract published by the ESP32 fleet:
 * <pre>
 * weather/devices/{deviceUid}/telemetry
 * </pre>
 *
 * {@code recordedAt} is always UTC — ingestion normalises the device's ISO-8601
 * offset timestamp before publishing, so no consumer has to guess a zone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryReceivedEvent implements Serializable {

    /** Device-assigned unique ID; the idempotency key for the whole pipeline. */
    private String messageId;

    private String deviceUid;

    private BigDecimal temperature;
    private BigDecimal humidity;
    private Integer airQuality;
    private BigDecimal pressure;
    private Boolean rain;
    private BigDecimal oxygen;

    private Double latitude;
    private Double longitude;

    /** UTC. */
    private LocalDateTime recordedAt;
}
