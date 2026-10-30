package com.weatherplatform.iotstream.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity trust configuration for the WebSocket handshake.
 *
 * In production the API Gateway terminates authentication and forwards a
 * verified identity plus {@code internalSecret}; this service trusts that and
 * nothing from the client. {@code jwt.secret} is only used for the dev
 * fallback where a browser connects to this service directly with an
 * {@code access_token} query parameter.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** HMAC secret shared with auth-service. Optional; required only for direct dev handshakes. */
    private String secret;

    /**
     * Shared secret proving a request was proxied by the API Gateway. When set,
     * the WebSocket handshake requires it and treats the forwarded
     * {@code X-User-*} headers as authoritative.
     */
    private String internalSecret;

    /**
     * When true, a handshake may authenticate with a raw {@code access_token}
     * query parameter verified against {@link #secret}. Intended for local
     * development only; leave false behind the gateway.
     */
    private boolean allowDirectTokenHandshake = false;
}
