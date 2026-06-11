package com.qwertyblob.every1luvs.security;

import com.qwertyblob.every1luvs.repository.UserRepository;
import com.qwertyblob.every1luvs.service.AuthCookieService;
import com.qwertyblob.every1luvs.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final AuthCookieService authCookieService;

    public TokenAuthenticationFilter(
            TokenService tokenService,
            UserRepository userRepository,
            AuthCookieService authCookieService
    ) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.authCookieService = authCookieService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveToken(request);
            if (token != null && !token.isBlank()) {
                authenticate(token, request);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Prefers the httpOnly auth cookie (the new default), falling back to the
     * Authorization: Bearer header for non-browser/API clients.
     */
    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (authCookieService.cookieName().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            TokenClaims claims = tokenService.validateToken(token);
            userRepository.findByEmail(claims.email())
                    .filter(user -> Boolean.TRUE.equals(user.getVerifiedAccount()))
                    // Reject any token minted at or before the user's last password change/reset,
                    // so a password change invalidates every previously-issued cookie immediately.
                    // Both values are epoch millis and a legitimate re-login is always a strictly
                    // later request, so strict '>' leaves no equality escape window.
                    .filter(user -> claims.issuedAt() > user.getPasswordChangedAt())
                    .ifPresent(user -> {
                        String role = user.getRole() == null ? "USER" : user.getRole();
                        List<SimpleGrantedAuthority> authorities = List.of(
                                new SimpleGrantedAuthority("ROLE_" + role)
                        );
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                user.getEmail(),
                                null,
                                authorities
                        );
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        } catch (RuntimeException ignored) {
            SecurityContextHolder.clearContext();
        }
    }
}
