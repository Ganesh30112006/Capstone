package com.weatherplatform.authservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private static final String SECRET =
            "4a6136695349586361665457597a736468756d6b6336336e3943323067307a51";
    private static final long EXPIRATION_MS = 3_600_000L;

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, EXPIRATION_MS);
    }

    @Test
    void generateToken_ShouldReturnValidToken() {
        String token = tokenProvider.generateToken(1L, "admin@test.com", "ADMIN");
        assertNotNull(token);
        assertTrue(token.length() > 50);
        assertTrue(tokenProvider.validateToken(token));
    }

    @Test
    void getUserIdFromToken_ShouldExtractCorrectId() {
        String token = tokenProvider.generateToken(123L, "user@test.com", "USER");
        assertEquals(123L, tokenProvider.getUserIdFromToken(token));
        assertEquals("user@test.com", tokenProvider.getEmailFromToken(token));
        assertEquals("USER", tokenProvider.getRoleFromToken(token));
    }

    @Test
    void validateToken_ShouldFailForMalformed() {
        assertFalse(tokenProvider.validateToken("eyJhbGciOiJIUzUxMiJ9.invalid.signature"));
    }
}
