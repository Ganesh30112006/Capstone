package com.weatherplatform.iotstream.security;

import com.weatherplatform.iotstream.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketAuthInterceptorTest {

    private static final String SECRET = "test-secret-value-that-is-long-enough-for-hs256-signing";
    private static final String INTERNAL = "gateway-internal-shared-secret";

    private WebSocketAuthInterceptor interceptor(boolean allowDirect) {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setInternalSecret(INTERNAL);
        props.setAllowDirectTokenHandshake(allowDirect);
        return new WebSocketAuthInterceptor(props, new JwtVerifier(props));
    }

    private String jwt(String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("u-1")
                .claim("email", "admin@example.com")
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }

    private boolean handshake(WebSocketAuthInterceptor interceptor, MockHttpServletRequest req, Map<String, Object> attrs) {
        ServerHttpRequest request = new ServletServerHttpRequest(req);
        ServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        return interceptor.beforeHandshake(request, response, null, attrs);
    }

    @Test
    void acceptsGatewayProxiedAdmin() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/live");
        req.addHeader("X-Gateway-Auth", INTERNAL);
        req.addHeader("X-User-Role", "ADMIN");
        req.addHeader("X-User-Email", "admin@example.com");

        Map<String, Object> attrs = new HashMap<>();
        assertThat(handshake(interceptor(false), req, attrs)).isTrue();
        assertThat(attrs).containsEntry("userRole", "ADMIN");
        assertThat(attrs).containsEntry("userEmail", "admin@example.com");
    }

    @Test
    void rejectsGatewayProxiedNonAdmin() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/live");
        req.addHeader("X-Gateway-Auth", INTERNAL);
        req.addHeader("X-User-Role", "USER");

        assertThat(handshake(interceptor(false), req, new HashMap<>())).isFalse();
    }

    @Test
    void rejectsWrongInternalSecret() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/live");
        req.addHeader("X-Gateway-Auth", "not-the-secret");
        req.addHeader("X-User-Role", "ADMIN");

        assertThat(handshake(interceptor(false), req, new HashMap<>())).isFalse();
    }

    @Test
    void rejectsDirectTokenWhenDirectHandshakeDisabled() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/live");
        req.addParameter("access_token", jwt("ADMIN"));

        assertThat(handshake(interceptor(false), req, new HashMap<>())).isFalse();
    }

    @Test
    void acceptsDirectAdminTokenWhenEnabled() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/live");
        req.addParameter("access_token", jwt("ADMIN"));

        Map<String, Object> attrs = new HashMap<>();
        assertThat(handshake(interceptor(true), req, attrs)).isTrue();
        assertThat(attrs).containsEntry("userRole", "ADMIN");
    }

    @Test
    void rejectsDirectNonAdminTokenEvenWhenEnabled() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/live");
        req.addParameter("access_token", jwt("USER"));

        assertThat(handshake(interceptor(true), req, new HashMap<>())).isFalse();
    }

    @Test
    void setsUnauthorizedStatusOnRejection() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/live");
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        ServerHttpRequest request = new ServletServerHttpRequest(req);
        ServerHttpResponse response = new ServletServerHttpResponse(rawResponse);

        boolean ok = interceptor(true).beforeHandshake(request, response, null, new HashMap<>());

        assertThat(ok).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
