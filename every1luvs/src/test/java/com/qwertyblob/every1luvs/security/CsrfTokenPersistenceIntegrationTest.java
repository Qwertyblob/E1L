package com.qwertyblob.every1luvs.security;

import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the stateless CSRF token-deletion bug. An authenticated request used
 * to emit {@code Set-Cookie: XSRF-TOKEN=; Max-Age=0} without re-writing the token, so a
 * SECOND consecutive state-changing request had no token left and was rejected with 403
 * (e.g. two password changes in a row).
 *
 * <p>The test keeps a {@link CookieJar} that applies {@code Set-Cookie} semantics across
 * requests exactly as a browser would — a {@code Max-Age=0} cookie is dropped — and always
 * echoes the CURRENT {@code XSRF-TOKEN} value in the {@code X-XSRF-TOKEN} header. Without
 * that browser-accurate cookie handling the bug hides (a single captured token would mask the
 * deletion), so this harness is essential for the regression to actually bite.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:every1luvs_csrf_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        // Migrations are Postgres-only; H2 tests build their schema from the entities.
        "spring.flyway.enabled=false",
        "app.auth.token-secret=test-secret-that-is-at-least-32-chars!!"
})
@AutoConfigureMockMvc
class CsrfTokenPersistenceIntegrationTest {

    private static final String EMAIL = "csrf-test@example.com";
    private static final String PASSWORD = "Admin12345";
    private static final String NEW_PASSWORD = "NewPass1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedVerifiedUser() {
        userRepository.deleteAll();
        UserEntity user = new UserEntity();
        user.setName("CSRF Tester");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole("USER");
        user.setVerifiedAccount(true);
        userRepository.save(user);
    }

    @Test
    void login_issuesAuthAndXsrfCookies() throws Exception {
        CookieJar jar = new CookieJar();
        login(jar);

        assertThat(jar.value("auth_token")).isNotBlank();
        assertThat(jar.value("XSRF-TOKEN")).isNotBlank();
    }

    @Test
    void authenticatedGet_keepsXsrfTokenAlive() throws Exception {
        CookieJar jar = new CookieJar();
        login(jar);
        String tokenAfterLogin = jar.value("XSRF-TOKEN");

        send(jar, get("/api/me")).andExpect(status().isOk());

        // The bug deleted the cookie here; the SPA would then have nothing to echo.
        assertThat(jar.value("XSRF-TOKEN"))
                .as("authenticated GET must leave a usable XSRF-TOKEN in the jar")
                .isNotBlank();
        assertThat(jar.value("XSRF-TOKEN")).isEqualTo(tokenAfterLogin);
    }

    @Test
    void passwordChange_keepsXsrfToken_butInvalidatesSession() throws Exception {
        CookieJar jar = new CookieJar();
        login(jar);

        // The write succeeds and — the original CSRF regression — must NOT delete the
        // XSRF-TOKEN (the bug emitted Set-Cookie: XSRF-TOKEN=; Max-Age=0 here).
        changePassword(jar, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());
        assertThat(jar.value("XSRF-TOKEN"))
                .as("password change must leave a usable XSRF-TOKEN (stateless CSRF deletion bug)")
                .isNotBlank();

        // New security behavior (P2, force re-login): the change invalidates every token issued
        // before it AND clears this session's auth cookie, so the same session cannot change the
        // password again — it must re-authenticate first.
        assertThat(jar.value("auth_token"))
                .as("auth cookie is cleared after a password change")
                .isNull();
        changePassword(jar, NEW_PASSWORD, PASSWORD).andExpect(status().is4xxClientError());
    }

    @Test
    void passwordChangeWithoutCsrfHeader_isForbidden() throws Exception {
        CookieJar jar = new CookieJar();
        login(jar);

        // CSRF protection is still enforced: cookies present but no matching header -> 403.
        send(jar, post("/api/me/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(passwordBody(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    private void login(CookieJar jar) throws Exception {
        send(jar, post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(
            CookieJar jar, String current, String next) throws Exception {
        return send(jar, post("/api/me/change-password")
                .header("X-XSRF-TOKEN", jar.value("XSRF-TOKEN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(passwordBody(current, next)));
    }

    /**
     * Sends a request with the jar's current cookies, then folds the response's Set-Cookie
     * headers back into the jar (mimicking the browser, including Max-Age=0 deletions).
     */
    private org.springframework.test.web.servlet.ResultActions send(
            CookieJar jar, MockHttpServletRequestBuilder builder) throws Exception {
        Cookie[] cookies = jar.toCookies();
        if (cookies.length > 0) {
            builder.cookie(cookies);
        }
        org.springframework.test.web.servlet.ResultActions actions = mockMvc.perform(builder);
        MvcResult result = actions.andReturn();
        for (Cookie set : result.getResponse().getCookies()) {
            jar.apply(set);
        }
        return actions;
    }

    private static String passwordBody(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    /** Minimal browser-like cookie store: Max-Age=0 removes, anything else stores the value. */
    private static final class CookieJar {
        private final Map<String, String> values = new LinkedHashMap<>();

        void apply(Cookie cookie) {
            if (cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isEmpty()) {
                values.remove(cookie.getName());
            } else {
                values.put(cookie.getName(), cookie.getValue());
            }
        }

        String value(String name) {
            return values.get(name);
        }

        Cookie[] toCookies() {
            return values.entrySet().stream()
                    .map(e -> new Cookie(e.getKey(), e.getValue()))
                    .toArray(Cookie[]::new);
        }
    }
}
