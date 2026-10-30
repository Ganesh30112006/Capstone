package com.weatherplatform.sensorservice.dto;

import com.weatherplatform.sensorservice.calibration.CalibrationStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Value
@Builder
public class SensorHealthResponse {
    String deviceUid;
    String overallStatus;
    Map<String, SensorStatusDetail> sensors;
    LocalDateTime lastCheckedAt;

    /**
     * Everything known about one sensor. Every field beyond the first three is
     * nullable, and null always means "not known" -- never zero, never fine.
     */
    @Value
    @Builder(toBuilder = true)
    public static class SensorStatusDetail {
        String status;
        String details;
        LocalDateTime lastUpdated;

        /** Which check decided the status. Null on records written before conditions were tracked. */
        String condition;
        /** The last reading that passed every check. Null if none has since tracking began. */
        LocalDateTime lastHealthyAt;
        /** True or false once the condition is known; null when it was never recorded. */
        Boolean flatlined;
        /** When the current flatline began; null unless flatlined. */
        LocalDateTime flatlineSince;
        /** The range the sensor can physically report; readings outside it are failures. */
        Double expectedMin;
        Double expectedMax;

        /** Null when the sensor has no evaluations in the window: 0 of 0 is not a score. */
        HealthScore health;
        /** Null for sensors that cannot be calibrated, such as rain. */
        CalibrationSummary calibration;
    }

    /**
     * The share of a sensor's evaluations that passed, and the counts behind it.
     *
     * A pass rate, not a weighted composite: every number here can be checked
     * against the counts beside it.
     */
    @Value
    @Builder
    public static class HealthScore {
        /** Rounded down to one decimal, so only a sensor with no failures reads 100%. */
        BigDecimal percent;
        int passed;
        int warned;
        /** Failure count over the window. */
        int failed;
        /** Start of the oldest hour counted. */
        LocalDateTime windowStart;
    }

    @Value
    @Builder
    public static class CalibrationSummary {
        CalibrationStatus status;
        /** All null when NOT_CALIBRATED. */
        LocalDateTime lastCalibratedAt;
        LocalDateTime validUntil;
        /** The correction applied to every raw reading while this calibration is in force. */
        BigDecimal offset;
        /**
         * Estimated drift since the last calibration, in the sensor's units.
         * Null unless two calibrations at least a week apart exist to estimate from.
         */
        BigDecimal driftEstimate;
        /** The earlier calibration the drift rate was measured from; null with no estimate. */
        LocalDateTime driftBaselineFrom;
    }
}
