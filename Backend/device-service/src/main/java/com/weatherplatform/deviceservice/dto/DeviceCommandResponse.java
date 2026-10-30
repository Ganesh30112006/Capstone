package com.weatherplatform.deviceservice.dto;

import com.weatherplatform.deviceservice.entity.DeviceCommand;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Acknowledgement that a command was accepted and published to AWS IoT.
 *
 * Note the wording of {@code status}: this confirms the platform delivered the
 * instruction to the broker, not that the station executed it. MQTT delivery to
 * a possibly-offline device is asynchronous, and reporting "acknowledged by
 * device" here would be a lie the UI would faithfully repeat.
 *
 * Actual execution surfaces later through the device's own telemetry, heartbeat
 * or OTA status events.
 */
@Value
@Builder
public class DeviceCommandResponse {

    String deviceUid;
    DeviceCommand command;

    /** Correlates this dispatch with the status event the device sends back. */
    String commandId;

    /** Always "PUBLISHED" — see the note above on what that does and does not mean. */
    String status;

    String topic;
    Instant issuedAt;
}
