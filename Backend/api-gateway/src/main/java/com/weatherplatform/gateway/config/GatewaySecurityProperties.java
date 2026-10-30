package com.weatherplatform.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Declarative authorization policy for the gateway.
 *
 * Each rule is written as "METHOD /ant/path/pattern" (for example
 * "POST /api/devices/**"). "*" may be used as the method wildcard.
 *
 * - publicPaths   : reachable with no credentials at all
 * - adminPaths    : require a valid JWT whose role claim is ADMIN
 * - anything else : requires a valid JWT of any role
 *
 * The gateway is the first authorization checkpoint, not the only one — each
 * downstream service re-checks the propagated identity independently.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {

    private List<String> publicPaths = new ArrayList<>();
    private List<String> adminPaths = new ArrayList<>();

    /**
     * Shared secret injected on every proxied request so downstream services can
     * distinguish gateway-originated traffic from a direct call to their port.
     * Defence in depth behind private networking, not a replacement for it.
     */
    private String internalSecret;
}
