package com.weatherplatform.deviceservice.dto;

import com.weatherplatform.deviceservice.entity.DeviceCommand;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A request to run one predefined operation on a station.
 *
 * {@code command} is the enum type rather than a String on purpose: an
 * unrecognised value is rejected during JSON binding, so no arbitrary
 * instruction can ever reach the dispatch code.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandRequest {

    @NotNull(message = "command is required and must be one of: PING, TELEMETRY_SYNC, REBOOT, OTA_CHECK")
    private DeviceCommand command;
}
