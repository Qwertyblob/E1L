package com.qwertyblob.every1luvs.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * Builds the httpOnly cookie that carries the auth JWT. Keeping the token in an
 * httpOnly cookie (instead of localStorage) means client-side JavaScript — and any
 * XSS — cannot read it. {@code Secure}/{@code SameSite} are env-driven so the cookie
 * is stored over plain HTTP in local dev but hardened in production.
 */
@Service
public class AuthCookieService {
    private final String cookieName;
    private final boolean secure;
    private final String sameSite;
    private final long maxAgeSeconds;

    public AuthCookieService(
            @Value("${app.auth.cookie.name:auth_token}") String cookieName,
            @Value("${app.auth.cookie.secure:false}") boolean secure,
            @Value("${app.auth.cookie.same-site:Lax}") String sameSite,
            @Value("${app.auth.token-expiration-ms:86400000}") long tokenExpirationMs
    ) {
        this.cookieName = cookieName;
        this.secure = secure;
        this.sameSite = sameSite;
        this.maxAgeSeconds = tokenExpirationMs / 1000;
    }

    public String cookieName() {
        return cookieName;
    }

    public boolean secure() {
        return secure;
    }

    public String sameSite() {
        return sameSite;
    }

    public ResponseCookie build(String token) {
        return baseCookie(token, maxAgeSeconds);
    }

    public ResponseCookie clear() {
        return baseCookie("", 0);
    }

    private ResponseCookie baseCookie(String value, long maxAge) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
