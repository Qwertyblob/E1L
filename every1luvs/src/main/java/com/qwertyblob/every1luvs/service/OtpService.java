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

    // A throwaway hash in the encoder's own format/cost, compared against on every verification
    // path that has no live stored hash to check (missing account, already-verified/no OTP,
    // expired, or malformed code) so all of them still spend exactly one BCrypt round and can't
    // be told apart from a real wrong-code attempt by timing. Computed once at startup rather
    // than hardcoded so it always matches whatever PasswordEncoder is wired in.
    private final String dummyOtpHash;

    public OtpService(
            PasswordEncoder passwordEncoder,
            @Value("${app.auth.otp.expiration-ms}") long expirationMs
    ) {
        this.passwordEncoder = passwordEncoder;
        this.expirationMs = expirationMs;
        this.dummyOtpHash = passwordEncoder.encode("000000");
    }

    public String assignVerificationOtp(UserEntity user) {
        String otp = String.format("%06d", secureRandom.nextInt(OTP_BOUND));
        user.setVerifyOtp(passwordEncoder.encode(otp));
        user.setVerifyOtpExpireAt(Instant.now().plusMillis(expirationMs).toEpochMilli());
        user.setVerifiedAccount(false);
        return otp;
    }

    /**
     * Verify an account OTP at constant BCrypt cost: every call performs exactly one hash
     * comparison — against the stored hash when {@code user} is a live verification candidate
     * (non-null, has an unexpired stored OTP, and a well-formed 6-digit code), and against a
     * throwaway hash in every other case (null user for "no such account", no/expired stored
     * OTP, or a malformed code). This keeps verify-account timing uniform so it can't be used to
     * tell account states apart. Returns true only when a well-formed code matched a live hash.
     */
    public boolean matchesVerificationOtp(UserEntity user, String otp) {
        boolean live = user != null
                && user.getVerifyOtp() != null
                && user.getVerifyOtpExpireAt() > Instant.now().toEpochMilli()
                && otp != null && otp.matches("\\d{6}");
        String hash = live ? user.getVerifyOtp() : dummyOtpHash;
        // otp is guaranteed non-null when live; coerce to "" otherwise so the encoder never NPEs.
        boolean matches = passwordEncoder.matches(otp == null ? "" : otp, hash);
        return live && matches;
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
