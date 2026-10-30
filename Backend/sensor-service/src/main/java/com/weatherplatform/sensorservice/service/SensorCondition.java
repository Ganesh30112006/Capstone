package com.weatherplatform.sensorservice.service;

/**
 * Which check decided a sensor's status.
 *
 * Status alone says how bad; condition says what is wrong. A frozen sensor and
 * one swinging wildly are both WARNING, and one needs a power cycle while the
 * other needs a look at its wiring.
 */
public enum SensorCondition {
    /** Every check passed. */
    NONE,
    /** The reading carried no value for this sensor. */
    MISSING,
    /** The value is outside what the sensor can physically report. */
    OUT_OF_RANGE,
    /** The value is changing faster than the quantity plausibly can. */
    RAPID_CHANGE,
    /** The value has not moved for longer than a live sensor would hold still. */
    FLATLINE
}
