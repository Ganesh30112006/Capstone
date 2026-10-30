package com.weatherplatform.iotingestion.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Heartbeat event published on {@code device.heartbeat}, consumed by
 * device-service to refresh last-seen, battery, Wi-Fi, firmware and the twin.
 *
 * Field names match device-service's own DeviceHeartbeatEvent so the JSON
 * converter binds it directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceHeartbeatEvent implements Serializable {

    private String messageId;
    private String deviceUid;
    private Integer batteryLevel;
    private Integer wifiSignalStrength;
    private String firmwareVersion;

    /** UTC. */
    private LocalDateTime timestamp;
}
