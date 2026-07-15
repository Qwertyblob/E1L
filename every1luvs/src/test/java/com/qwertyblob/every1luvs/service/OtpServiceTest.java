package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(passwordEncoder, 600_000L);
    }

    @Test
    void assignVerificationOtp_setsHashedOtpAndExpiry() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-otp");
        UserEntity user = new UserEntity();

        String otp = otpService.assignVerificationOtp(user);

        assertThat(otp).matches("\\d{6}");
        assertThat(user.getVerifyOtp()).isEqualTo("hashed-otp");
        assertThat(user.getVerifyOtpExpireAt()).isGreaterThan(System.currentTimeMillis());
        assertThat(user.getVerifiedAccount()).isFalse();
        verify(passwordEncoder).encode(otp);
    }

    // matchesVerificationOtp must spend EXACTLY ONE BCrypt compare on every path so no failure
    // mode (missing account, no/expired/malformed code) can be told apart by timing. Each test
    // asserts both the boolean result and that exactly one matches() call happened.

    private UserEntity liveOtpUser() {
        UserEntity user = new UserEntity();
        user.setVerifyOtp("hashed-otp");
        user.setVerifyOtpExpireAt(System.currentTimeMillis() + 60_000L);
        return user;
    }

    @Test
    void matchesVerificationOtp_nullUser_spendsOneCompare_returnsFalse() {
        assertThat(otpService.matchesVerificationOtp(null, "123456")).isFalse();
        verify(passwordEncoder, times(1)).matches(eq("123456"), any());
    }

    @Test
    void matchesVerificationOtp_nullOtp_spendsOneCompare_returnsFalse() {
        // Coerced to "" so the encoder is still exercised once (never NPEs on a null raw value).
        assertThat(otpService.matchesVerificationOtp(liveOtpUser(), null)).isFalse();
        verify(passwordEncoder, times(1)).matches(eq(""), any());
    }

    @Test
    void matchesVerificationOtp_malformedOtp_spendsOneCompare_returnsFalse() {
        // A malformed code is not a live candidate, so it burns one compare against the dummy hash
        // (never the real stored hash) and returns false — same cost as a well-formed wrong code.
        assertThat(otpService.matchesVerificationOtp(liveOtpUser(), "abc123")).isFalse();
        verify(passwordEncoder, times(1)).matches(eq("abc123"), any());
        verify(passwordEncoder, never()).matches(anyString(), eq("hashed-otp"));
    }

    @Test
    void matchesVerificationOtp_missingStoredHash_spendsOneCompare_returnsFalse() {
        UserEntity user = new UserEntity();
        user.setVerifyOtpExpireAt(System.currentTimeMillis() + 60_000L); // no stored hash

        assertThat(otpService.matchesVerificationOtp(user, "123456")).isFalse();
        verify(passwordEncoder, times(1)).matches(eq("123456"), any());
    }

    @Test
    void matchesVerificationOtp_expiredOtp_spendsOneCompare_returnsFalse() {
        UserEntity user = new UserEntity();
        user.setVerifyOtp("hashed-otp");
        user.setVerifyOtpExpireAt(System.currentTimeMillis() - 1_000L);

        assertThat(otpService.matchesVerificationOtp(user, "123456")).isFalse();
        verify(passwordEncoder, times(1)).matches(eq("123456"), any());
        // Expired never compares against the real hash — only the throwaway one.
        verify(passwordEncoder, never()).matches(anyString(), eq("hashed-otp"));
    }

    @Test
    void matchesVerificationOtp_correctOtp_comparesRealHash_returnsTrue() {
        UserEntity user = liveOtpUser();
        when(passwordEncoder.matches("123456", "hashed-otp")).thenReturn(true);

        assertThat(otpService.matchesVerificationOtp(user, "123456")).isTrue();
        verify(passwordEncoder, times(1)).matches("123456", "hashed-otp");
    }

    @Test
    void matchesVerificationOtp_wrongOtp_comparesRealHash_returnsFalse() {
        UserEntity user = liveOtpUser();
        when(passwordEncoder.matches("000000", "hashed-otp")).thenReturn(false);

        assertThat(otpService.matchesVerificationOtp(user, "000000")).isFalse();
        verify(passwordEncoder, times(1)).matches("000000", "hashed-otp");
    }

    @Test
    void markVerified_clearsOtpAndSetsVerifiedTrue() {
        UserEntity user = new UserEntity();
        user.setVerifyOtp("hashed-otp");
        user.setVerifyOtpExpireAt(999_999L);
        user.setVerifiedAccount(false);

        otpService.markVerified(user);

        assertThat(user.getVerifiedAccount()).isTrue();
        assertThat(user.getVerifyOtp()).isNull();
        assertThat(user.getVerifyOtpExpireAt()).isZero();
    }

    @Test
    void assignResetOtp_setsHashedOtpAndExpiry() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-reset-otp");
        UserEntity user = new UserEntity();

        String otp = otpService.assignResetOtp(user);

        assertThat(otp).matches("\\d{6}");
        assertThat(user.getResetOtp()).isEqualTo("hashed-reset-otp");
        assertThat(user.getResetOtpExpireAt()).isGreaterThan(System.currentTimeMillis());
        verify(passwordEncoder).encode(otp);
    }

    @Test
    void isValidResetOtp_correctOtp_returnsTrue() {
        UserEntity user = new UserEntity();
        user.setResetOtp("hashed-reset-otp");
        user.setResetOtpExpireAt(System.currentTimeMillis() + 60_000L);
        when(passwordEncoder.matches("123456", "hashed-reset-otp")).thenReturn(true);

        assertThat(otpService.isValidResetOtp(user, "123456")).isTrue();
    }

    @Test
    void isValidResetOtp_expiredOtp_returnsFalse() {
        UserEntity user = new UserEntity();
        user.setResetOtp("hashed-reset-otp");
        user.setResetOtpExpireAt(System.currentTimeMillis() - 1_000L);

        assertThat(otpService.isValidResetOtp(user, "123456")).isFalse();
        // Reset OTP is unaffected by the verify-account timing fix; it still short-circuits an
        // expired code without a compare (the ctor's dummy-hash encode is the only interaction).
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void isValidResetOtp_malformedOtp_returnsFalse() {
        assertThat(otpService.isValidResetOtp(new UserEntity(), "abc123")).isFalse();
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void clearResetOtp_clearsOtpAndExpiry() {
        UserEntity user = new UserEntity();
        user.setResetOtp("hashed-reset-otp");
        user.setResetOtpExpireAt(999_999L);

        otpService.clearResetOtp(user);

        assertThat(user.getResetOtp()).isNull();
        assertThat(user.getResetOtpExpireAt()).isZero();
    }
}
