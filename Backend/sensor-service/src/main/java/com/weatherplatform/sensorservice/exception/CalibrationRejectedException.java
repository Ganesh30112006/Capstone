package com.weatherplatform.sensorservice.exception;

/**
 * A calibration that is well-formed but cannot be accepted as given: an
 * implausible offset, a date in the future, no recent reading to pair the
 * reference with. Reported as 422 -- a definitive answer that retrying the same
 * request will not change.
 */
public class CalibrationRejectedException extends RuntimeException {
    public CalibrationRejectedException(String message) {
        super(message);
    }
}
