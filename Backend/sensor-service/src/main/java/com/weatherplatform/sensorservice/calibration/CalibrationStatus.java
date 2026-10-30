package com.weatherplatform.sensorservice.calibration;

/** Where a sensor's calibration stands. See {@link CalibrationMath#status}. */
public enum CalibrationStatus {
    /** No calibration on record. Unchecked -- not the same as accurate. */
    NOT_CALIBRATED,
    VALID,
    /** Still valid, and expires within the configured warning period. */
    DUE_SOON,
    /** Past its validity. Its offset is still applied; it needs re-verifying. */
    EXPIRED,
    /**
     * Replaced by a newer calibration of the same sensor. Only ever reported in
     * history: whatever its dates say, it is no longer the one in force.
     */
    SUPERSEDED
}
