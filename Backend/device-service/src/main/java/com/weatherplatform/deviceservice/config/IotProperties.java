package com.weatherplatform.deviceservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AWS IoT Core settings and the canonical MQTT topic scheme.
 *
 * The topic layout is defined once here and reused by provisioning (to tell the
 * firmware author which topics to use), by the OTA publisher, and by the
 * Terraform policy that scopes each device to its own topics. Keeping a single
 * definition is what makes the least-privilege policy and the firmware agree.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "aws.iot")
public class IotProperties {

    /** When false, no AWS client is created and provisioning is unavailable. */
    private boolean enabled = false;

    /** Account-specific ATS endpoint, e.g. xxxx-ats.iot.eu-west-1.amazonaws.com. */
    private String mqttEndpoint;

    /** Least-privilege policy attached to every device certificate. */
    private String policyName = "weather-station-device-policy";

    private String thingTypeName = "weather-station";

    /** Prefix for Thing names, keeping the fleet grouped in the registry. */
    private String thingNamePrefix = "weather-station-";

    /** Root of the MQTT topic tree. Mirrored in the IoT policy and the IoT rules. */
    private String topicPrefix = "weather/devices";

    public String thingNameFor(String deviceUid) {
        return thingNamePrefix + deviceUid;
    }

    public String telemetryTopic(String deviceUid) {
        return topicPrefix + "/" + deviceUid + "/telemetry";
    }

    public String heartbeatTopic(String deviceUid) {
        return topicPrefix + "/" + deviceUid + "/heartbeat";
    }

    public String eventsTopic(String deviceUid) {
        return topicPrefix + "/" + deviceUid + "/events";
    }

    /** Downlink: the platform publishes here, the device subscribes. */
    public String otaTopic(String deviceUid) {
        return topicPrefix + "/" + deviceUid + "/ota";
    }
}
