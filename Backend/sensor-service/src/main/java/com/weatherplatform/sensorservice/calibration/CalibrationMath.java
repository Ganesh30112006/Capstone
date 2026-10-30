package com.weatherplatform.sensorservice.calibration;

import com.weatherplatform.sensorservice.entity.SensorCalibration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The arithmetic of calibration, kept free of Spring and the database so each
 * rule can be tested on its own.
 *
 * <pre>
 *   raw value  --(+ offset)-->  corrected value
 *   offset      = reference - sensor, observed together at calibration time
 * </pre>
 */
public final class CalibrationMath {

    /** Offsets are stored to three decimals; see sensor_calibration. */
    public static final int OFFSET_SCALE = 3;

    /**
     * The shortest gap between two calibrations that drift is estimated from.
     *
     * Drift is extrapolated: the change between the last two calibrations,
     * carried forward to today. Over a short baseline that amplifies ordinary
     * calibration noise into nonsense -- two calibrations a minute apart that
     * differ by 0.1° would "predict" hundreds of degrees of drift a month later.
     */
    public static final Duration MIN_DRIFT_BASELINE = Duration.ofDays(7);

    private static final double SECONDS_PER_DAY = 86_400.0;

    private CalibrationMath() {}

    /**
     * The correction implied by one side-by-side observation.
     *
     * Both inputs are brought to the stored scale before subtracting, so the
     * result is exactly the difference of the two stored values -- which the
     * database checks.
     */
    public static BigDecimal offset(BigDecimal referenceValue, BigDecimal sensorValue) {
        return referenceValue.setScale(OFFSET_SCALE, RoundingMode.HALF_UP)
                .subtract(sensorValue.setScale(OFFSET_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * A raw reading with the calibration offset applied, at the precision the
     * reading is stored in.
     *
     * Humidity and oxygen are percentages and air quality is a non-negative
     * index, so a correction is clamped at their physical limits: a sensor at
     * 99.8% RH with a +0.5 offset is saturated, not reading 100.3%. The raw
     * value is kept regardless, so the clamp never loses information.
     *
     * With no offset the raw value is returned unchanged.
     */
    public static BigDecimal correct(String sensorType, BigDecimal raw, BigDecimal offset) {
        if (raw == null) return null;
        if (offset == null) return raw;
        BigDecimal corrected = raw.add(offset);
        switch (sensorType) {
            case "humidity", "oxygen" -> corrected = clamp(corrected, BigDecimal.ZERO, BigDecimal.valueOf(100));
            default -> { }
        }
        return corrected.setScale(2, RoundingMode.HALF_UP);
    }

    /** Air quality is an integer index; the corrected value is rounded, not truncated. */
    public static Integer correctIndex(Integer raw, BigDecimal offset) {
        if (raw == null) return null;
        if (offset == null) return raw;
        BigDecimal corrected = BigDecimal.valueOf(raw).add(offset).setScale(0, RoundingMode.HALF_UP);
        return Math.max(0, corrected.intValueExact());
    }

    /**
     * Where a sensor's calibration stands.
     *
     * NOT_CALIBRATED when there is no record at all. That is deliberately not
     * VALID: a sensor nobody has checked against a reference is not known to be
     * accurate, it is simply unchecked.
     *
     * An EXPIRED calibration is still applied to readings -- its offset is the
     * best correction known. Expiry means "due for re-verification", not
     * "the correction is wrong".
     */
    public static CalibrationStatus status(SensorCalibration latest, LocalDateTime now, int dueSoonDays) {
        if (latest == null) return CalibrationStatus.NOT_CALIBRATED;
        if (!now.isBefore(latest.getValidUntil())) return CalibrationStatus.EXPIRED;
        if (!now.plusDays(dueSoonDays).isBefore(latest.getValidUntil())) return CalibrationStatus.DUE_SOON;
        return CalibrationStatus.VALID;
    }

    /**
     * How far the sensor is estimated to have drifted since its last
     * calibration, in the sensor's own units. Positive means it now reads
     * higher than the corrected-for bias assumes.
     *
     * The sensor's bias at each calibration is the negative of the offset
     * applied then. Between the last two calibrations that bias moved at some
     * rate; this carries that rate forward from the latest calibration to now.
     *
     * Null -- not zero -- when there is nothing to estimate from: fewer than
     * two calibrations, or two taken too close together to give a meaningful
     * rate. Zero would claim the sensor is known not to have drifted.
     */
    public static BigDecimal driftSinceLastCalibration(SensorCalibration latest,
                                                       SensorCalibration previous,
                                                       LocalDateTime now) {
        if (latest == null || previous == null) return null;

        Duration baseline = Duration.between(previous.getCalibratedAt(), latest.getCalibratedAt());
        if (baseline.compareTo(MIN_DRIFT_BASELINE) < 0) return null;

        // bias = -offset, so the change in bias is previous.offset - latest.offset.
        double biasChange = previous.getOffsetValue().subtract(latest.getOffsetValue()).doubleValue();
        double ratePerDay = biasChange / (baseline.getSeconds() / SECONDS_PER_DAY);

        double daysSince = Math.max(0, Duration.between(latest.getCalibratedAt(), now).getSeconds()) / SECONDS_PER_DAY;
        return BigDecimal.valueOf(ratePerDay * daysSince).setScale(OFFSET_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }
}
