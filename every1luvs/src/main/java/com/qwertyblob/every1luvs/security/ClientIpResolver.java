package com.qwertyblob.every1luvs.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the real client IP for rate-limiting keys. Only honors X-Forwarded-For when a
 * trusted reverse proxy is known to overwrite it (see nginx/nginx.conf, which replaces the
 * inbound header with the single realip-resolved address); otherwise a client could spoof
 * the header to dodge per-IP limits. Shared by guest-booking and login throttling so both
 * use the same trust decision.
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwardedFor;

    public ClientIpResolver(@Value("${app.proxy.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
