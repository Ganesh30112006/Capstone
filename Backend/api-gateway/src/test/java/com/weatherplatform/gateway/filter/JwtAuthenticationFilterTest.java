package com.weatherplatform.gateway.filter;

import com.weatherplatform.gateway.config.GatewaySecurityProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "4a6136695349586361665457597a736468756d6b6336336e3943323067307a51";
    private static final String INTERNAL_SECRET = "internal-gateway-secret";

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtAuthenticationFilter filter;
    private GatewayFilterChain filterChain;

    @BeforeEach
    void setUp() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setInternalSecret(INTERNAL_SECRET);
        properties.setPublicPaths(List.of(
                "POST /api/auth/login",
                "POST /api/auth/register",
                "GET /api/public/**",
                "GET /actuator/health"));
        properties.setAdminPaths(List.of(
                "POST /api/devices",
                "DELETE /api/devices/**",
                "* /api/firmware/**"));

        filter = new JwtAuthenticationFilter(SECRET, properties);
        filter.compilePolicies();

        filterChain = mock(GatewayFilterChain.class);
        when(filterChain.filter(any())).thenReturn(Mono.empty());
    }

    private String tokenFor(String role) {
        return Jwts.builder()
                .subject("user-id-123")
                .claim("email", "test@test.com")
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();
    }

    private ServerWebExchange capturedExchange() {
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        return captor.getValue();
    }

    @Test
    void permitsPublicRouteWithoutToken() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void permitsPublicPortalDataWithoutToken() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/public/stations").build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsPublicPathWithWrongMethod() {
        // The public rule is GET-only; a write to the same prefix must authenticate.
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/public/stations").build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsMissingAuthHeader() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sensors/STATION-001/latest").build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsInvalidToken() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sensors/STATION-001/latest")
                        .header("Authorization", "Bearer not-a-real-token")
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsExpiredToken() {
        String expired = Jwts.builder()
                .subject("user-id-123")
                .claim("role", "ADMIN")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(key)
                .compact();

        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/devices")
                        .header("Authorization", "Bearer " + expired)
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void acceptsValidUserTokenOnSharedRoute() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/devices")
                        .header("Authorization", "Bearer " + tokenFor("USER"))
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void forbidsUserRoleOnAdminRoute() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/devices")
                        .header("Authorization", "Bearer " + tokenFor("USER"))
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void forbidsUserRoleOnFirmwareRouteForEveryMethod() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/firmware")
                        .header("Authorization", "Bearer " + tokenFor("USER"))
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void allowsAdminRoleOnAdminRoute() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/devices")
                        .header("Authorization", "Bearer " + tokenFor("ADMIN"))
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void stripsClientSuppliedIdentityHeaders() {
        // A caller trying to forge an admin identity must not have it honoured.
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/devices")
                        .header("Authorization", "Bearer " + tokenFor("USER"))
                        .header(JwtAuthenticationFilter.HEADER_USER_ROLE, "ADMIN")
                        .header(JwtAuthenticationFilter.HEADER_USER_ID, "999")
                        .header(JwtAuthenticationFilter.HEADER_INTERNAL_SECRET, "guessed-secret")
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

        var forwarded = capturedExchange().getRequest().getHeaders();
        assertEquals("USER", forwarded.getFirst(JwtAuthenticationFilter.HEADER_USER_ROLE));
        assertEquals("user-id-123", forwarded.getFirst(JwtAuthenticationFilter.HEADER_USER_ID));
        assertEquals(INTERNAL_SECRET, forwarded.getFirst(JwtAuthenticationFilter.HEADER_INTERNAL_SECRET));
    }

    @Test
    void spoofedAdminHeaderCannotReachAdminRoute() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.delete("/api/devices/STATION-001")
                        .header("Authorization", "Bearer " + tokenFor("USER"))
                        .header(JwtAuthenticationFilter.HEADER_USER_ROLE, "ADMIN")
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void stripsIdentityHeadersOnPublicRoutes() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/public/stations")
                        .header(JwtAuthenticationFilter.HEADER_USER_ROLE, "ADMIN")
                        .build());

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

        var forwarded = capturedExchange().getRequest().getHeaders();
        assertNull(forwarded.getFirst(JwtAuthenticationFilter.HEADER_USER_ROLE));
        assertNotNull(forwarded.getFirst(JwtAuthenticationFilter.HEADER_INTERNAL_SECRET));
    }
}
