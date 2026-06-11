package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.entity.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;

@Service
public class OtpService {
    private static final int OTP_BOUND = 1_000_000;

    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long expirationMs;

    public OtpService(
            PasswordEncoder passwordEncoder,
            @Value("${app.auth.otp.expiration-ms}") long expirationMs
    ) {
        this.passwordEncoder = passwordEncoder;
        this.expirationMs = expirationMs;
    }

    public String assignVerificationOtp(UserEntity user) {
        String otp = String.format("%06d", secureRandom.nextInt(OTP_BOUND));
        user.setVerifyOtp(passwordEncoder.encode(otp));
        user.setVerifyOtpExpireAt(Instant.now().plusMillis(expirationMs).toEpochMilli());
        user.setVerifiedAccount(false);
        return otp;
    }

    public boolean isValidVerificationOtp(UserEntity user, String otp) {
        if (otp == null || !otp.matches("\\d{6}")) {
            return false;
        }
        if (user.getVerifyOtp() == null || user.getVerifyOtpExpireAt() <= Instant.now().toEpochMilli()) {
            return false;
        }

        return passwordEncoder.matches(otp, user.getVerifyOtp());
    }

    public void markVerified(UserEntity user) {
        user.setVerifiedAccount(true);
        user.setVerifyOtp(null);
        user.setVerifyOtpExpireAt(0);
    }

    public String assignResetOtp(UserEntity user) {
        String otp = String.format("%06d", secureRandom.nextInt(OTP_BOUND));
        user.setResetOtp(passwordEncoder.encode(otp));
        user.setResetOtpExpireAt(Instant.now().plusMillis(expirationMs).toEpochMilli());
        return otp;
    }

    public boolean isValidResetOtp(UserEntity user, String otp) {
        if (otp == null || !otp.matches("\\d{6}")) {
            return false;
        }
        if (user.getResetOtp() == null || user.getResetOtpExpireAt() <= Instant.now().toEpochMilli()) {
            return false;
        }

        return passwordEncoder.matches(otp, user.getResetOtp());
    }

    public void clearResetOtp(UserEntity user) {
        user.setResetOtp(null);
        user.setResetOtpExpireAt(0);
    }
}
