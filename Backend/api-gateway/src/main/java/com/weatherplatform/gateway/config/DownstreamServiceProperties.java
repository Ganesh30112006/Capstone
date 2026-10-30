package com.weatherplatform.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health-probe targets for the aggregated system health endpoint.
 *
 * Keyed by the display name shown in the operator console; the value is the
 * fully qualified health URL for that service (Spring services expose
 * /actuator/health, the FastAPI AI service exposes /health).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.health")
public class DownstreamServiceProperties {

    private Map<String, String> services = new LinkedHashMap<>();

    /** Per-probe timeout in milliseconds. */
    private long timeoutMs = 2000;
}
