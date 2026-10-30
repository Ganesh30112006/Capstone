package com.weatherplatform.iotstream.security;

import com.weatherplatform.iotstream.config.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Authorizes the WebSocket handshake before the socket is opened.
 *
 * <p>Two accepted paths, in order of preference:
 *
 * <ol>
 *   <li><b>Via the gateway</b> — the request carries {@code X-Gateway-Auth}
 *       matching the shared internal secret. The gateway has already verified
 *       the JWT, so the forwarded {@code X-User-Role} / {@code X-User-Email}
 *       are trusted. This is the only path enabled in production.</li>
 *   <li><b>Direct, dev only</b> — {@code jwt.allow-direct-token-handshake=true}
 *       and an {@code access_token} query parameter (or bearer header) that
 *       verifies against {@code jwt.secret}.</li>
 * </ol>
 *
 * Either way the identity must have the {@code ADMIN} role. A browser can only
 * ever <em>receive</em> on this socket; nothing here grants it any control over
 * a device.
 */
@Slf4j
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    static final String ATTR_ROLE = "userRole";
    static final String ATTR_EMAIL = "userEmail";
    static final String ATTR_SUBJECT = "userId";

    private final JwtProperties jwtProperties;
    private final JwtVerifier jwtVerifier;

    public WebSocketAuthInterceptor(JwtProperties jwtProperties, JwtVerifier jwtVerifier) {
        this.jwtProperties = jwtProperties;
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {

        if (authorizeViaGateway(request, attributes)) {
            return true;
        }
        if (authorizeDirect(request, attributes)) {
            return true;
        }
        log.warn("Rejected WebSocket handshake from {}",
                request.getRemoteAddress() != null ? request.getRemoteAddress() : "unknown");
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    private boolean authorizeViaGateway(ServerHttpRequest request, Map<String, Object> attributes) {
        String configured = jwtProperties.getInternalSecret();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String presented = request.getHeaders().getFirst("X-Gateway-Auth");
        if (presented == null || !constantTimeEquals(configured, presented)) {
            return false;
        }
        String role = request.getHeaders().getFirst("X-User-Role");
        if (!"ADMIN".equalsIgnoreCase(role == null ? "" : role)) {
            log.warn("Gateway-proxied WebSocket handshake denied: role '{}' is not ADMIN", role);
            return false;
        }
        attributes.put(ATTR_ROLE, "ADMIN");
        attributes.put(ATTR_EMAIL, headerOrEmpty(request, "X-User-Email"));
        attributes.put(ATTR_SUBJECT, headerOrEmpty(request, "X-User-Id"));
        return true;
    }

    private boolean authorizeDirect(ServerHttpRequest request, Map<String, Object> attributes) {
        if (!jwtProperties.isAllowDirectTokenHandshake() || !jwtVerifier.isConfigured()) {
            return false;
        }
        String token = bearer(request);
        if (token == null) {
            token = queryParam(request, "access_token");
        }
        return jwtVerifier.verify(token)
                .filter(JwtVerifier.VerifiedIdentity::isAdmin)
                .map(identity -> {
                    attributes.put(ATTR_ROLE, identity.role());
                    attributes.put(ATTR_EMAIL, identity.email() == null ? "" : identity.email());
                    attributes.put(ATTR_SUBJECT, identity.subject() == null ? "" : identity.subject());
                    return true;
                })
                .orElse(false);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }

    private static String bearer(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    private static String queryParam(ServerHttpRequest request, String name) {
        if (!(request instanceof ServletServerHttpRequest servlet)) {
            return null;
        }
        return servlet.getServletRequest().getParameter(name);
    }

    private static String headerOrEmpty(ServerHttpRequest request, String name) {
        String v = request.getHeaders().getFirst(name);
        return v == null ? "" : v;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
