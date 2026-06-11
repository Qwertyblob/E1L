package com.qwertyblob.every1luvs.config;

import com.qwertyblob.every1luvs.security.CsrfCookieFilter;
import com.qwertyblob.every1luvs.security.StableCsrfTokenRepository;
import com.qwertyblob.every1luvs.security.TokenAuthenticationFilter;
import com.qwertyblob.every1luvs.repository.UserRepository;
import com.qwertyblob.every1luvs.service.AuthCookieService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private final TokenAuthenticationFilter tokenAuthenticationFilter;
    private final AuthCookieService authCookieService;

    public SecurityConfig(TokenAuthenticationFilter tokenAuthenticationFilter, AuthCookieService authCookieService) {
        this.tokenAuthenticationFilter = tokenAuthenticationFilter;
        this.authCookieService = authCookieService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Cookie-based CSRF protection. Because the JWT now rides in a cookie that the
                // browser attaches automatically, we need CSRF defence. CookieCsrfTokenRepository
                // writes a JS-readable XSRF-TOKEN cookie; the SPA echoes it back in the
                // X-XSRF-TOKEN header, and Spring verifies the two match. The plain
                // CsrfTokenRequestAttributeHandler keeps the cookie/header values un-encoded so an
                // SPA reading the cookie can send it verbatim. Pre-auth endpoints (no cookie to
                // protect yet) are exempted to keep first-request login/registration friction-free.
                .csrf(csrf -> csrf
                        // Wrap the cookie repository so Spring's CsrfAuthenticationStrategy cannot
                        // delete the XSRF-TOKEN cookie on the per-request re-authentication. See
                        // StableCsrfTokenRepository for why the rotation-delete breaks this stateless API.
                        .csrfTokenRepository(new StableCsrfTokenRepository(csrfTokenRepository()))
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/verify-account",
                                "/api/auth/resend-verification-otp",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/bookings"
                        )
                )
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/verify-account",
                                "/api/auth/resend-verification-otp",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password"
                        ).permitAll()
                        .requestMatchers("/error").permitAll()
                        // Compose healthcheck probe; port 8080 is never published to the host,
                        // so this is only reachable from inside the compose networks.
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/slots/available").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/bookings").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                        (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                ))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Runs after CsrfFilter has registered the deferred token; forces the
                // XSRF-TOKEN cookie onto every response so the SPA always has a token to send.
                .addFilterAfter(new CsrfCookieFilter(), TokenAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Keep the XSRF-TOKEN cookie's Secure/SameSite in lockstep with the auth cookie
        // (off over HTTP in dev, hardened in prod).
        repository.setCookieCustomizer(cookie -> cookie
                .secure(authCookieService.secure())
                .sameSite(authCookieService.sameSite()));
        return repository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByEmail(username)
                .map(user -> User.withUsername(user.getEmail())
                        .password(user.getPassword())
                        .authorities("ROLE_" + user.getRole())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found."));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String allowedOrigins
    ) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        // Credentials must be allowed so the browser sends/receives the httpOnly auth cookie
        // and the XSRF-TOKEN cookie cross-origin (localhost:3000 -> localhost:8080).
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
