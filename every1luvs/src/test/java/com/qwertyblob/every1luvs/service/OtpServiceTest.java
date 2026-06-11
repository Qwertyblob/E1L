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

    @Test
    void isValidVerificationOtp_nullOtp_returnsFalse() {
        assertThat(otpService.isValidVerificationOtp(new UserEntity(), null)).isFalse();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void isValidVerificationOtp_fiveDigitOtp_returnsFalse() {
        assertThat(otpService.isValidVerificationOtp(new UserEntity(), "12345")).isFalse();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void isValidVerificationOtp_alphanumericOtp_returnsFalse() {
        assertThat(otpService.isValidVerificationOtp(new UserEntity(), "abc123")).isFalse();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void isValidVerificationOtp_nullVerifyOtpOnUser_returnsFalse() {
        UserEntity user = new UserEntity();
        user.setVerifyOtpExpireAt(System.currentTimeMillis() + 60_000L);

        assertThat(otpService.isValidVerificationOtp(user, "123456")).isFalse();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void isValidVerificationOtp_expiredOtp_returnsFalse() {
        UserEntity user = new UserEntity();
        user.setVerifyOtp("hashed-otp");
        user.setVerifyOtpExpireAt(System.currentTimeMillis() - 1_000L);

        assertThat(otpService.isValidVerificationOtp(user, "123456")).isFalse();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void isValidVerificationOtp_correctOtp_returnsTrue() {
        UserEntity user = new UserEntity();
        user.setVerifyOtp("hashed-otp");
        user.setVerifyOtpExpireAt(System.currentTimeMillis() + 60_000L);
        when(passwordEncoder.matches("123456", "hashed-otp")).thenReturn(true);

        assertThat(otpService.isValidVerificationOtp(user, "123456")).isTrue();
    }

    @Test
    void isValidVerificationOtp_wrongOtp_returnsFalse() {
        UserEntity user = new UserEntity();
        user.setVerifyOtp("hashed-otp");
        user.setVerifyOtpExpireAt(System.currentTimeMillis() + 60_000L);
        when(passwordEncoder.matches("000000", "hashed-otp")).thenReturn(false);

        assertThat(otpService.isValidVerificationOtp(user, "000000")).isFalse();
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
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void isValidResetOtp_malformedOtp_returnsFalse() {
        assertThat(otpService.isValidResetOtp(new UserEntity(), "abc123")).isFalse();
        verifyNoInteractions(passwordEncoder);
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
