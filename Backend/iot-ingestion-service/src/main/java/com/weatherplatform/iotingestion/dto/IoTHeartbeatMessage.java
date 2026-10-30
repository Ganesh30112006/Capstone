package com.weatherplatform.iotingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Heartbeat exactly as an ESP32 publishes it to
 * {@code weather/devices/{deviceUid}/heartbeat}.
 *
 * Heartbeats are what keep a station's last-seen, battery, Wi-Fi and firmware
 * picture current — a station may heartbeat without sending telemetry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IoTHeartbeatMessage {

    private String messageId;
    private String deviceUid;

    /** UTC instant, parsed from the device's ISO-8601 offset timestamp. */
    private Instant timestamp;

    private Integer batteryLevel;
    private Integer wifiSignalStrength;
    private String firmwareVersion;
}
