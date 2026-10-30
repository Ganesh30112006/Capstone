package com.weatherplatform.iotstream.security;

import com.weatherplatform.iotstream.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Verifies an HS256 JWT issued by auth-service. Used only on the dev direct
 * WebSocket handshake; behind the gateway, identity arrives pre-verified in
 * headers and this class is not consulted.
 */
@Slf4j
@Component
public class JwtVerifier {

    public record VerifiedIdentity(String subject, String email, String role) {
        public boolean isAdmin() {
            return "ADMIN".equals(role);
        }
    }

    private final SecretKey key;

    public JwtVerifier(JwtProperties properties) {
        String secret = properties.getSecret();
        this.key = (secret == null || secret.isBlank())
                ? null
                : Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isConfigured() {
        return key != null;
    }

    public Optional<VerifiedIdentity> verify(String token) {
        if (key == null || token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String role = claims.get("role", String.class);
            return Optional.of(new VerifiedIdentity(
                    claims.getSubject(),
                    claims.get("email", String.class),
                    role == null ? "" : role.toUpperCase(Locale.ROOT)));
        } catch (Exception e) {
            log.warn("WebSocket handshake token rejected: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
