package com.qwertyblob.every1luvs.security;

import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.UserRepository;
import com.qwertyblob.every1luvs.service.AuthCookieService;
import com.qwertyblob.every1luvs.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers the P2 token-invalidation gate: a token whose issued-at predates the user's last
 * password change must NOT authenticate, while a newer token must. The verified-account
 * check is exercised too, since both share the same Optional filter chain.
 */
@ExtendWith(MockitoExtension.class)
class TokenAuthenticationFilterTest {

    @Mock TokenService tokenService;
    @Mock UserRepository userRepository;
    @Mock AuthCookieService authCookieService;

    @InjectMocks TokenAuthenticationFilter filter;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static UserEntity verifiedUser(long passwordChangedAt) {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("Alice");
        user.setEmail("alice@example.com");
        user.setRole("USER");
        user.setVerifiedAccount(true);
        user.setPasswordChangedAt(passwordChangedAt);
        return user;
    }

    private Authentication runFilterWithToken(TokenClaims claims, UserEntity user) throws Exception {
        when(tokenService.validateToken("the-token")).thenReturn(claims);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.ofNullable(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer the-token");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void tokenIssuedAfterPasswordChange_authenticates() throws Exception {
        Authentication auth = runFilterWithToken(
                new TokenClaims("alice@example.com", "USER", 1_000L), verifiedUser(500L));

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alice@example.com");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    void tokenIssuedAtPasswordChange_isRejected() throws Exception {
        Authentication auth = runFilterWithToken(
                new TokenClaims("alice@example.com", "USER", 1_000L), verifiedUser(1_000L));

        assertThat(auth).as("issuedAt == passwordChangedAt must NOT authenticate (strict '>')").isNull();
    }

    @Test
    void tokenIssuedBeforePasswordChange_isRejected() throws Exception {
        Authentication auth = runFilterWithToken(
                new TokenClaims("alice@example.com", "USER", 500L), verifiedUser(1_000L));

        assertThat(auth).as("a token minted before the last password change must not authenticate").isNull();
    }

    @Test
    void unverifiedAccount_isRejected() throws Exception {
        UserEntity user = verifiedUser(0L);
        user.setVerifiedAccount(false);

        Authentication auth = runFilterWithToken(
                new TokenClaims("alice@example.com", "USER", 1_000L), user);

        assertThat(auth).isNull();
    }
}
