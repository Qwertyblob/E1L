package com.qwertyblob.every1luvs.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time email-verification codes for GUEST bookings. The public booking endpoint
 * consumes real slot capacity, so before a seat is taken the caller must prove control
 * of the contact inbox by echoing a code we mailed there — otherwise a script with
 * rotating IPs and made-up emails could fill the calendar (the per-IP rate limit alone
 * can't stop that).
 *
 * <p>Codes are stored in memory like {@link RateLimiterService} buckets (single-instance
 * deployment; a restart just means the guest requests a fresh code), bcrypt-hashed so a
 * heap dump or log never exposes a live code, and single-use: a successful verify
 * consumes the entry, so one code authorises exactly one booking.
 */
@Service
public class GuestVerificationService {

    private record PendingOtp(String otpHash, long expiresAtMs) {}

    private final ConcurrentHashMap<String, PendingOtp> pending = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final PasswordEncoder passwordEncoder;
    private final long expirationMs;

    public GuestVerificationService(
            PasswordEncoder passwordEncoder,
            @Value("${app.auth.otp.expiration-ms}") long expirationMs
    ) {
        this.passwordEncoder = passwordEncoder;
        this.expirationMs = expirationMs;
    }

    /** Issues (and stores) a fresh code for this email, replacing any previous one. */
    public String issue(String email) {
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        pending.put(key(email), new PendingOtp(
                passwordEncoder.encode(otp),
                Instant.now().toEpochMilli() + expirationMs));
        return otp;
    }

    /** Single-use check: a match consumes the stored code so it can't authorise a second booking. */
    public boolean verify(String email, String otp) {
        if (email == null || otp == null || !otp.trim().matches("\\d{6}")) {
            return false;
        }
        PendingOtp entry = pending.get(key(email));
        if (entry == null || entry.expiresAtMs() <= Instant.now().toEpochMilli()) {
            return false;
        }
        if (!passwordEncoder.matches(otp.trim(), entry.otpHash())) {
            return false;
        }
        pending.remove(key(email));
        return true;
    }

    private String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Scheduled(fixedDelay = 300_000)
    void evictExpired() {
        long now = Instant.now().toEpochMilli();
        pending.entrySet().removeIf(e -> e.getValue().expiresAtMs() <= now);
    }
}
