package com.weatherplatform.deviceservice.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * The one and only time a device's private key is ever returned.
 *
 * AWS IoT generates the key pair, and this response is the sole opportunity to
 * capture it — the platform deliberately does not persist it anywhere. Only the
 * certificate identifiers are stored in PostgreSQL, so a database compromise
 * cannot yield a working device identity.
 *
 * The operator flashes {@code privateKey} and {@code certificatePem} onto the
 * ESP32; losing them means re-provisioning, which is the correct trade.
 */
@Value
@Builder
public class DeviceProvisioningResponse {

    String deviceUid;

    /** AWS IoT Thing name for this station. */
    String thingName;

    String certificateId;
    String certificateArn;

    /** PEM-encoded device certificate. */
    String certificatePem;

    /**
     * PEM-encoded private key. Returned exactly once and never stored.
     * Treat this response as a secret in transit and at rest on the operator's machine.
     */
    String privateKey;

    /** The MQTT endpoint the device connects to over TLS. */
    String mqttEndpoint;

    /** Topics this device's policy allows, for the firmware author's reference. */
    String telemetryTopic;
    String heartbeatTopic;
    String otaTopic;

    Instant provisionedAt;

    /** Reminder surfaced in the API response, not just the docs. */
    @Builder.Default
    String warning = "Store the private key securely now — it cannot be retrieved again.";
}
