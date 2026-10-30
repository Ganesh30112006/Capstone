package com.weatherplatform.iotstream.security;

import com.weatherplatform.iotstream.config.JwtProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Rejects any {@code /api/**} call that did not come through the API Gateway.
 *
 * <p>Defence in depth behind private networking: this service is never meant to
 * be reachable directly. The WebSocket endpoint ({@code /ws/**}) has its own
 * handshake check and is skipped here; {@code /actuator/**} is left open for
 * container health probes.
 */
@Slf4j
@Component
@Order(1)
public class GatewayOnlyFilter extends OncePerRequestFilter {

    private final String internalSecret;

    public GatewayOnlyFilter(JwtProperties jwtProperties) {
        this.internalSecret = jwtProperties.getInternalSecret();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (internalSecret == null || internalSecret.isBlank()) {
            // Not configured (e.g. a local run without the gateway). Fail closed
            // only if something is clearly trying to reach an admin path.
            chain.doFilter(request, response);
            return;
        }

        String presented = request.getHeader("X-Gateway-Auth");
        if (presented == null || !MessageDigest.isEqual(
                internalSecret.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Blocked direct call to {} (missing/invalid gateway secret)", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"This service is reachable only through the API Gateway\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
