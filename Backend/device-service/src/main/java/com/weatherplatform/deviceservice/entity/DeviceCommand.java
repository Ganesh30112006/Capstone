package com.weatherplatform.deviceservice.entity;

/**
 * The complete set of operations an administrator may send to a station.
 *
 * Deliberately a closed enum rather than a free-text command string. The
 * platform must never offer a path for arbitrary instructions to reach device
 * firmware — a station accepts these four verbs and nothing else, and anything
 * outside this list is rejected before it reaches AWS IoT.
 *
 * Note what is absent: no shell execution, no serial passthrough, no
 * configuration injection. Adding a case here is a deliberate act that requires
 * matching firmware support.
 */
public enum DeviceCommand {

    /** Ask the station to confirm it is alive and report its current health. */
    PING,

    /** Ask the station to publish a telemetry sample immediately. */
    TELEMETRY_SYNC,

    /** Ask the station to restart. Firmware decides when it is safe to comply. */
    REBOOT,

    /** Ask the station to check for and apply a pending firmware update. */
    OTA_CHECK
}
