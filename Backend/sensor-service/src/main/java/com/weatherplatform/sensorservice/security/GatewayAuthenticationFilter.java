package com.weatherplatform.sensorservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Establishes the caller's identity from the headers the API Gateway attaches
 * after it has verified the JWT.
 *
 * This service never sees the raw token. It trusts the gateway only when the
 * request also carries the shared internal secret, which the gateway strips
 * from and re-adds to every proxied request — so a request sent straight to
 * this container's port cannot forge an identity.
 *
 * Authorization itself is enforced by @PreAuthorize on the controllers; this
 * filter only supplies the authenticated principal and its authority.
 */
@Slf4j
@Component
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    public static final String HEADER_INTERNAL_SECRET = "X-Gateway-Auth";

    private final String internalSecret;
    private final boolean enforceGatewayOrigin;

    public GatewayAuthenticationFilter(
            @Value("${gateway.internal-secret:}") String internalSecret,
            @Value("${gateway.enforce-origin:true}") boolean enforceGatewayOrigin) {
        this.internalSecret = internalSecret;
        this.enforceGatewayOrigin = enforceGatewayOrigin;

        if (enforceGatewayOrigin && (internalSecret == null || internalSecret.isBlank())) {
            log.warn("gateway.internal-secret is not set — direct-access protection is disabled. "
                    + "Set GATEWAY_INTERNAL_SECRET in every environment that is not a local sandbox.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (isInfrastructureEndpoint(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        if (!originatedFromGateway(request)) {
            log.warn("Rejected request to {} that did not come through the API Gateway", request.getRequestURI());
            writeForbidden(response, request.getRequestURI());
            return;
        }

        String userId = request.getHeader(HEADER_USER_ID);
        String role = request.getHeader(HEADER_USER_ROLE);

        if (userId != null && !userId.isBlank() && role != null && !role.isBlank()) {
            String authority = "ROLE_" + role.toUpperCase(Locale.ROOT);
            var authentication = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority(authority)));
            authentication.setDetails(request.getHeader(HEADER_USER_EMAIL));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Health, metrics and API docs are probed on the private network, not via the gateway. */
    private boolean isInfrastructureEndpoint(String uri) {
        return uri.startsWith("/actuator")
                || uri.startsWith("/api-docs")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui");
    }

    private boolean originatedFromGateway(HttpServletRequest request) {
        if (!enforceGatewayOrigin || internalSecret == null || internalSecret.isBlank()) {
            return true;
        }
        String presented = request.getHeader(HEADER_INTERNAL_SECRET);
        return presented != null && constantTimeEquals(presented, internalSecret);
    }

    /** Avoids leaking secret length/prefix through response-timing differences. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    private void writeForbidden(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"success":false,"status":403,"error":"Forbidden",\
                "message":"This service is only reachable through the API Gateway.","path":"%s"}"""
                .formatted(path));
    }
}
