package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DeviceCommandResponse;
import com.weatherplatform.deviceservice.entity.DeviceCommand;
import com.weatherplatform.deviceservice.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Used when AWS IoT is not configured.
 *
 * Fails explicitly instead of pretending. An emulated "SUCCESS" would train
 * operators to trust a control path that does not exist — the exact failure
 * mode the old browser-side hardware console had.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "aws.iot.enabled", havingValue = "false", matchIfMissing = true)
public class UnavailableDeviceCommandService implements DeviceCommandService {

    @Override
    public DeviceCommandResponse send(String deviceUid, DeviceCommand command, String issuedBy) {
        log.warn("Command {} requested for {} but AWS IoT is disabled", command, deviceUid);
        throw new BusinessException(
                "Device commands require AWS IoT, which is not enabled in this environment. "
                        + "No command was sent.");
    }
}
