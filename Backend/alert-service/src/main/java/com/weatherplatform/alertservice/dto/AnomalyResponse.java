package com.weatherplatform.alertservice.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An AI-detected anomaly as presented to the operator console.
 *
 * {@code score} is deliberately named a score and not a confidence: it measures
 * how far the reading sat from the model's expectation, and the platform does
 * not calibrate it into a probability. Presenting it as "confidence" would
 * overstate what the model actually knows.
 */
@Value
@Builder
public class AnomalyResponse {

    String id;
    String stationUid;

    /** The sensor that drove the anomaly, e.g. "temperature". */
    String parameter;

    /** The observed reading. */
    String actual;

    /** What the forecast model expected instead; null when no forecast was available. */
    String expected;

    /** Anomaly score in [0, 1]. Not a calibrated confidence. */
    BigDecimal score;

    /** INFO | WARNING | CRITICAL. */
    String severity;

    String explanation;

    /** Model provenance, so an operator can tell which version made the call. */
    String modelName;
    String modelVersion;

    LocalDateTime timestamp;
}
