package com.weatherplatform.alertservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Canonical {@code anomaly.detected} event published by the AI service.
 *
 * Field names match the AI worker's JSON exactly. An earlier version of this
 * DTO expected {@code confidence}/{@code message} while the worker published
 * {@code score}/{@code reason}, so every field bound to null and alert creation
 * failed on the first arithmetic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyDetectedEvent implements Serializable {

    /** Producer-assigned ID, used for idempotent handling. */
    private String eventId;

    private String deviceUid;

    /** Which sensor drove the anomaly, e.g. "temperature". */
    private String sensor;

    /** The observed reading. */
    private Double value;

    /** What the forecast model expected instead. */
    private Double expectedValue;

    /**
     * Anomaly score in [0, 1].
     *
     * Explicitly a score, not a calibrated confidence: it measures distance
     * from the model's expectation, not the probability of a real fault.
     */
    private Double score;

    /** INFO | WARNING | CRITICAL, as judged by the scorer. */
    private String severity;

    /** Human-readable explanation assembled from the contributing detectors. */
    private String reason;

    private String modelName;
    private String modelVersion;

    /** UTC. */
    private LocalDateTime detectedAt;
}
