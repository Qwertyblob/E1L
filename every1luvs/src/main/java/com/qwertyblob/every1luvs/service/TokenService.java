package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.security.TokenClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class TokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();
    private final String tokenSecret;
    private final long tokenExpirationMs;

    public TokenService(
            ObjectMapper objectMapper,
            @Value("${app.auth.token-secret}") String tokenSecret,
            @Value("${app.auth.token-expiration-ms}") long tokenExpirationMs
    ) {
        if (tokenSecret == null || tokenSecret.length() < 32) {
            throw new IllegalStateException(
                    "app.auth.token-secret must be at least 32 characters. Set AUTH_TOKEN_SECRET.");
        }
        this.objectMapper = objectMapper;
        this.tokenSecret = tokenSecret;
        this.tokenExpirationMs = tokenExpirationMs;
    }

    public String createToken(UserEntity user) {
        Instant now = Instant.now();
        // iat is epoch MILLIS (not seconds) so password-change invalidation has sub-second
        // granularity — see TokenAuthenticationFilter. exp stays in seconds (JWT-conventional).
        long issuedAt = now.toEpochMilli();
        long expiresAt = now.plusMillis(tokenExpirationMs).getEpochSecond();
        String header = encode(Map.of("alg", "HS256", "typ", "JWT"));
        String payload = encode(Map.of(
                "sub", user.getEmail(),
                "role", user.getRole(),
                "name", user.getName(),
                "iat", issuedAt,
                "exp", expiresAt
        ));
        String unsignedToken = header + "." + payload;

        return unsignedToken + "." + sign(unsignedToken);
    }

    public TokenClaims validateToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid token");
        }

        String unsignedToken = parts[0] + "." + parts[1];
        String expectedSignature = sign(unsignedToken);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new IllegalArgumentException("Invalid token signature");
        }

        Map<String, Object> payload = decode(parts[1]);
        Object expiresAtValue = payload.get("exp");
        long expiresAt = expiresAtValue instanceof Number number ? number.longValue() : 0L;
        if (expiresAt < Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("Token has expired");
        }

        Object issuedAtValue = payload.get("iat");
        long issuedAt = issuedAtValue instanceof Number number ? number.longValue() : 0L;

        return new TokenClaims(String.valueOf(payload.get("sub")), String.valueOf(payload.get("role")), issuedAt);
    }

    private String encode(Object value) {
        try {
            return encoder.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode token", exception);
        }
    }

    private Map<String, Object> decode(String value) {
        try {
            return objectMapper.readValue(decoder.decode(value), MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid token payload", exception);
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return encoder.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign token", exception);
        }
    }
}
