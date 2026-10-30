package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DeviceProvisioningResponse;

/**
 * Issues and revokes a device's AWS IoT identity.
 *
 * The platform's rule is that {@code deviceUid} is an identifier, never a
 * credential. A station may only publish once it holds an X.509 certificate
 * bound to its own Thing and scoped by a least-privilege IoT policy to its own
 * topics — so a compromised device cannot impersonate the fleet.
 */
public interface DeviceProvisioningService {

    /**
     * Creates the Thing, key pair, certificate and policy attachment.
     *
     * The returned private key is not stored and cannot be retrieved again.
     */
    DeviceProvisioningResponse provision(String deviceUid);

    /**
     * Revokes the device's certificate and detaches it.
     *
     * Used when a station is decommissioned or its key is believed compromised.
     */
    void revoke(String deviceUid);
}
