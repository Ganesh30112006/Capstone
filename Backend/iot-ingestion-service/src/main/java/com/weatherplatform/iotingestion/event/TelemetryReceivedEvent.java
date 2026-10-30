package com.weatherplatform.iotingestion.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Canonical telemetry event published on {@code iot.telemetry.received}.
 *
 * Consumed by sensor-service, which persists it and then republishes the
 * enriched {@code sensor.reading.created} for analytics, alerting, the digital
 * twin and the AI worker. The two routing keys are deliberately distinct so the
 * pipeline cannot loop back on itself.
 *
 * {@code recordedAt} is UTC, already normalised from the device's offset.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryReceivedEvent implements Serializable {

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
