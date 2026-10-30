package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DeviceCommandResponse;
import com.weatherplatform.deviceservice.entity.DeviceCommand;

/**
 * Dispatches an authorized operation to a physical station.
 *
 * The only supported path is:
 * <pre>
 * Admin UI → API Gateway → Device Service → AWS IoT Core → ESP32
 * </pre>
 *
 * A browser never talks to hardware. Every command is authenticated at the
 * gateway, authorized as ADMIN here, constrained to {@link DeviceCommand}, and
 * delivered over the device's own MQTT topic under its own X.509 identity.
 */
public interface DeviceCommandService {

    DeviceCommandResponse send(String deviceUid, DeviceCommand command, String issuedBy);
}
