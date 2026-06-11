package com.qwertyblob.every1luvs.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the decorator that neutralises Spring's CSRF rotation-delete.
 * The whole point of the class is that {@code saveToken} drops cookie-clearing
 * calls (null/blank token) while every other operation passes straight through.
 */
class StableCsrfTokenRepositoryTest {

    private CsrfTokenRepository delegate;
    private StableCsrfTokenRepository repository;
    private final HttpServletRequest request = new MockHttpServletRequest();
    private final HttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        delegate = mock(CsrfTokenRepository.class);
        repository = new StableCsrfTokenRepository(delegate);
    }

    @Test
    void saveToken_nullToken_isIgnored() {
        repository.saveToken(null, request, response);

        // The rotation-delete must never reach the cookie repository.
        verify(delegate, never()).saveToken(any(), any(), any());
    }

    @Test
    void saveToken_blankToken_isIgnored() {
        CsrfToken blank = mock(CsrfToken.class);
        when(blank.getToken()).thenReturn("   ");

        repository.saveToken(blank, request, response);

        verify(delegate, never()).saveToken(any(), any(), any());
    }

    @Test
    void saveToken_realToken_isDelegated() {
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "real-token-value");

        repository.saveToken(token, request, response);

        verify(delegate).saveToken(token, request, response);
    }

    @Test
    void generateToken_isDelegated() {
        CsrfToken generated = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "generated");
        when(delegate.generateToken(request)).thenReturn(generated);

        assertThat(repository.generateToken(request)).isSameAs(generated);
    }

    @Test
    void loadToken_isDelegated() {
        CsrfToken loaded = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "loaded");
        when(delegate.loadToken(request)).thenReturn(loaded);

        assertThat(repository.loadToken(request)).isSameAs(loaded);
    }

    @Test
    void loadDeferredToken_isDelegated() {
        DeferredCsrfToken deferred = mock(DeferredCsrfToken.class);
        when(delegate.loadDeferredToken(request, response)).thenReturn(deferred);

        assertThat(repository.loadDeferredToken(request, response)).isSameAs(deferred);
    }
}
