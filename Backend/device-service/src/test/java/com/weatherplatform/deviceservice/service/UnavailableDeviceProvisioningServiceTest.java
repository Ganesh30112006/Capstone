package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stand-in used when AWS IoT is not configured.
 *
 * Its entire value is that it refuses. A stub that returned a plausible-looking
 * certificate would leave a station recorded as provisioned while holding an
 * identity that authenticates to nothing -- the console would show a
 * provisioned fleet and the hardware would fail to connect, with nothing tying
 * the two facts together.
 */
class UnavailableDeviceProvisioningServiceTest {

    private final UnavailableDeviceProvisioningService service = new UnavailableDeviceProvisioningService();

    @Test
    @DisplayName("refuses to provision rather than returning a stub identity")
    void provisionFailsLoudly() {
        assertThatThrownBy(() -> service.provision("ESP32-001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AWS IoT provisioning is not enabled");
    }

    @Test
    @DisplayName("says what to configure, so the failure is actionable")
    void namesTheMissingConfiguration() {
        assertThatThrownBy(() -> service.provision("ESP32-001"))
                .hasMessageContaining("aws.iot.enabled=true");
    }

    @Test
    @DisplayName("refuses to revoke rather than reporting a revocation that never happened")
    void revokeFailsLoudly() {
        // Silently succeeding would tell an operator a compromised key had been
        // withdrawn when nothing was withdrawn.
        assertThatThrownBy(() -> service.revoke("ESP32-001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AWS IoT provisioning is not enabled");
    }
}
