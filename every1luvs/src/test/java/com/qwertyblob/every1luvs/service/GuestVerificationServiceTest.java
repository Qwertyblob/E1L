package com.qwertyblob.every1luvs.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class GuestVerificationServiceTest {

    // Strength 4 keeps bcrypt fast in tests; production uses the default encoder bean.
    private final GuestVerificationService service =
            new GuestVerificationService(new BCryptPasswordEncoder(4), 600_000L);

    @Test
    void issuedCodeVerifies_onceOnly() {
        String otp = service.issue("gina@example.com");

        assertThat(otp).matches("\\d{6}");
        assertThat(service.verify("gina@example.com", otp)).isTrue();
        // Single-use: the successful verify consumed the code.
        assertThat(service.verify("gina@example.com", otp)).isFalse();
    }

    @Test
    void verifyIsCaseInsensitiveOnEmail_andTrimsOtp() {
        String otp = service.issue("Gina@Example.com");

        assertThat(service.verify("  gina@example.com ", " " + otp + " ")).isTrue();
    }

    @Test
    void wrongCode_unknownEmail_andMalformedOtp_allFail() {
        service.issue("gina@example.com");

        assertThat(service.verify("gina@example.com", "000000")).isFalse();
        assertThat(service.verify("other@example.com", "123456")).isFalse();
        assertThat(service.verify("gina@example.com", "12345")).isFalse();
        assertThat(service.verify("gina@example.com", "abcdef")).isFalse();
        assertThat(service.verify("gina@example.com", null)).isFalse();
        assertThat(service.verify(null, "123456")).isFalse();
    }

    @Test
    void expiredCode_failsAndIsEvicted() {
        GuestVerificationService expiring =
                new GuestVerificationService(new BCryptPasswordEncoder(4), -1L); // already expired
        String otp = expiring.issue("gina@example.com");

        assertThat(expiring.verify("gina@example.com", otp)).isFalse();
        expiring.evictExpired(); // must not throw; removes the dead entry
    }

    @Test
    void reissueReplacesPreviousCode() {
        String first = service.issue("gina@example.com");
        String second = service.issue("gina@example.com");

        if (!first.equals(second)) { // 1-in-a-million collision guard
            assertThat(service.verify("gina@example.com", first)).isFalse();
        }
        assertThat(service.verify("gina@example.com", second)).isTrue();
    }
}
