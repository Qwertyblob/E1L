package com.qwertyblob.every1luvs.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.util.StringUtils;

/**
 * Keeps the {@code XSRF-TOKEN} cookie STABLE for the lifetime of a login, which is what a
 * stateless JWT API wants.
 *
 * <p>On authentication Spring rotates the CSRF token via {@code CsrfAuthenticationStrategy}:
 * it first calls {@code saveToken(null, ...)} to delete the current cookie, then reloads a
 * "fresh" token. But because this API re-authenticates from the auth cookie on EVERY request
 * (see {@link TokenAuthenticationFilter}) and the reload reads the unchanged <em>request</em>
 * cookie, it finds the old value, considers the token present, and skips re-writing it. The
 * response therefore carries ONLY the delete header: the browser drops its {@code XSRF-TOKEN}
 * cookie, so the NEXT state-changing request has no token to echo and is rejected with 403 —
 * e.g. two consecutive password changes, where the second fails until a page reload.
 *
 * <p>Token rotation defends against session fixation, which cannot occur in a sessionless JWT
 * setup, so suppressing the delete is safe. We drop {@code saveToken} calls that would clear
 * the cookie (null/blank token) and let genuine token writes through, so the token a client
 * received at login stays valid for every subsequent request.
 */
public final class StableCsrfTokenRepository implements CsrfTokenRepository {

    private final CsrfTokenRepository delegate;

    public StableCsrfTokenRepository(CsrfTokenRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // Ignore the rotation-delete (null/blank token) so the existing XSRF-TOKEN cookie survives.
        if (token == null || !StringUtils.hasText(token.getToken())) {
            return;
        }
        delegate.saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }

    @Override
    public DeferredCsrfToken loadDeferredToken(HttpServletRequest request, HttpServletResponse response) {
        return delegate.loadDeferredToken(request, response);
    }
}
