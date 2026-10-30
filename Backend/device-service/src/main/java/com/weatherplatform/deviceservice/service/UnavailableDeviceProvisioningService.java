package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DeviceProvisioningResponse;
import com.weatherplatform.deviceservice.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Stand-in used when AWS IoT is not configured (local development and CI).
 *
 * It fails loudly rather than returning a fake certificate. A stub identity
 * would let a device appear provisioned while being able to authenticate to
 * nothing, which is a far worse failure than an explicit error.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "aws.iot.enabled", havingValue = "false", matchIfMissing = true)
public class UnavailableDeviceProvisioningService implements DeviceProvisioningService {

    private static final String MESSAGE =
            "AWS IoT provisioning is not enabled in this environment. "
                    + "Set aws.iot.enabled=true and provide the IoT endpoint and credentials.";

    @Override
    public DeviceProvisioningResponse provision(String deviceUid) {
        log.warn("Provisioning requested for {} but AWS IoT is disabled", deviceUid);
        throw new BusinessException(MESSAGE);
    }

    @Override
    public void revoke(String deviceUid) {
        log.warn("Revocation requested for {} but AWS IoT is disabled", deviceUid);
        throw new BusinessException(MESSAGE);
    }
}
