package com.qwertyblob.every1luvs.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security loads the CSRF token lazily (a deferred token), so the
 * {@code XSRF-TOKEN} cookie is only written once something actually reads the
 * token. This filter reads it on every request, forcing the cookie to be sent so
 * the SPA always has a token to echo back in the {@code X-XSRF-TOKEN} header.
 *
 * <p>This is the cookie-based CSRF pattern from the Spring Security reference
 * "Configure CSRF for a JavaScript Single Page Application".
 */
public final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // Touch the token value to trigger the deferred token to be persisted to the cookie.
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
