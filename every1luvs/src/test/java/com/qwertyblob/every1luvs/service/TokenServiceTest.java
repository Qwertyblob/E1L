package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.security.TokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class TokenServiceTest {

    private static final String SECRET = "a-secret-key-that-is-at-least-32-characters!!";
    private static final long EXPIRATION_MS = 3_600_000L;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(new ObjectMapper(), SECRET, EXPIRATION_MS);
    }

    @Test
    void createToken_returnsThreePartToken() {
        String token = tokenService.createToken(testUser());
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void validateToken_validToken_returnsCorrectClaims() {
        String token = tokenService.createToken(testUser());

        TokenClaims claims = tokenService.validateToken(token);

        assertThat(claims.email()).isEqualTo("alice@example.com");
        assertThat(claims.role()).isEqualTo("USER");
    }

    @Test
    void validateToken_carriesIssuedAtInMillis() {
        long beforeMillis = java.time.Instant.now().toEpochMilli();
        String token = tokenService.createToken(testUser());

        TokenClaims claims = tokenService.validateToken(token);

        // iat is epoch MILLIS so TokenAuthenticationFilter can reject tokens minted before a
        // password change with sub-second granularity (no same-second escape).
        assertThat(claims.issuedAt())
                .isGreaterThanOrEqualTo(beforeMillis)
                .isLessThanOrEqualTo(java.time.Instant.now().toEpochMilli());
    }

    @Test
    void validateToken_tamperedSignature_throwsIllegalArgument() {
        String token = tokenService.createToken(testUser());
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + ".invalidsignature";

        assertThatThrownBy(() -> tokenService.validateToken(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateToken_twoPartToken_throwsIllegalArgument() {
        assertThatThrownBy(() -> tokenService.validateToken("header.payload"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateToken_expiredToken_throwsIllegalArgument() {
        TokenService shortLived = new TokenService(new ObjectMapper(), SECRET, -1_000L);
        String token = shortLived.createToken(testUser());

        assertThatThrownBy(() -> tokenService.validateToken(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void constructor_shortSecret_throwsIllegalState() {
        assertThatThrownBy(() -> new TokenService(new ObjectMapper(), "tooshort", EXPIRATION_MS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_nullSecret_throwsIllegalState() {
        assertThatThrownBy(() -> new TokenService(new ObjectMapper(), null, EXPIRATION_MS))
                .isInstanceOf(IllegalStateException.class);
    }

    private UserEntity testUser() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("Alice");
        user.setEmail("alice@example.com");
        user.setRole("USER");
        return user;
    }
}
