package com.weatherplatform.alertservice.entity;

/**
 * How urgently an alert needs a human.
 *
 * Kept deliberately coarse: an operator scanning a list needs three
 * distinctions, not a numeric scale. The underlying anomaly score is stored
 * separately for anyone who wants the detail.
 */
public enum AlertSeverity {

    /** Worth knowing about; no action required. */
    INFO,

    /** Degrading, or outside the expected envelope. Should be looked at. */
    WARNING,

    /** Station down, sensor failed, or a reading far outside anything physical. */
    CRITICAL
}
