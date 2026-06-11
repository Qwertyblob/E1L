package com.qwertyblob.every1luvs.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

class RateLimiterServiceTest {

    private RateLimiterService rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiterService();
    }

    @Test
    void check_firstCall_doesNotThrow() {
        assertThatNoException().isThrownBy(
                () -> rateLimiter.check("test:key", 3, 60_000L, "rate limited"));
    }

    @Test
    void check_atExactlyLimit_doesNotThrow() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.check("test:key", 3, 60_000L, "rate limited");
        }
    }

    @Test
    void check_exceedsLimit_throws429() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.check("test:key", 3, 60_000L, "rate limited");
        }
        var ex = catchThrowableOfType(
                () -> rateLimiter.check("test:key", 3, 60_000L, "rate limited"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex.getReason()).isEqualTo("rate limited");
    }

    @Test
    void reset_clearsCounter_allowsFreshAttempts() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.check("test:key", 3, 60_000L, "rate limited");
        }
        rateLimiter.reset("test:key");
        assertThatNoException().isThrownBy(
                () -> rateLimiter.check("test:key", 3, 60_000L, "rate limited"));
    }

    @Test
    void check_differentKeys_independentCounters() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.check("key:a", 3, 60_000L, "rate limited");
        }
        assertThatNoException().isThrownBy(
                () -> rateLimiter.check("key:b", 3, 60_000L, "rate limited"));
    }

    @Test
    void check_expiredWindow_resetsCounter() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            rateLimiter.check("test:key", 3, 50L, "rate limited");
        }
        Thread.sleep(60L);
        assertThatNoException().isThrownBy(
                () -> rateLimiter.check("test:key", 3, 50L, "rate limited"));
    }
}
