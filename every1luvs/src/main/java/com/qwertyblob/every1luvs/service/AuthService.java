package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.AuthResponse;
import com.qwertyblob.every1luvs.dto.ChangePasswordRequest;
import com.qwertyblob.every1luvs.dto.ForgotPasswordRequest;
import com.qwertyblob.every1luvs.dto.LoginRequest;
import com.qwertyblob.every1luvs.dto.MessageResponse;
import com.qwertyblob.every1luvs.dto.ResendVerificationOtpRequest;
import com.qwertyblob.every1luvs.dto.ResetPasswordRequest;
import com.qwertyblob.every1luvs.dto.RegisterRequest;
import com.qwertyblob.every1luvs.dto.UserResponse;
import com.qwertyblob.every1luvs.dto.VerifyAccountRequest;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private static final int MAX_LOGIN_ATTEMPTS = 10;
    private static final long LOGIN_WINDOW_MS = 15 * 60 * 1_000L;

    // Per-IP cap on top of the per-email one: stops one address from spraying attempts
    // across many accounts. Generous enough for a shared NAT (office/campus) of normal users.
    private static final int MAX_LOGIN_ATTEMPTS_PER_IP = 30;

    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final long OTP_WINDOW_MS = 10 * 60 * 1_000L;

    // Per-IP cap on verify-account on top of the per-email one: stops one address from spraying
    // verification guesses across many accounts. Same generous shared-NAT allowance as login.
    private static final int MAX_OTP_ATTEMPTS_PER_IP = 30;

    private static final int MAX_RESEND_ATTEMPTS = 3;
    private static final long RESEND_WINDOW_MS = 10 * 60 * 1_000L;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final OtpService otpService;
    private final OtpDeliveryService otpDeliveryService;
    private final RateLimiterService rateLimiter;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            OtpService otpService,
            OtpDeliveryService otpDeliveryService,
            RateLimiterService rateLimiter
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.otpService = otpService;
        this.otpDeliveryService = otpDeliveryService;
        this.rateLimiter = rateLimiter;
    }

    public MessageResponse register(RegisterRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registration details are required.");
        }

        String name = normalizeText(request.name());
        String email = normalizeEmail(request.email());
        String password = normalizeText(request.password());

        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Name must not exceed " + MAX_NAME_LENGTH + " characters.");
        }
        validateEmail(email);
        validatePassword(password);

        // Throttle per-email (like forgot-password) so this endpoint can't be used to spam
        // verification / account-exists mail at a chosen address. No reset(): registration has
        // no success-clears-bucket semantics; the window simply expires.
        rateLimiter.check("register:" + email, 3, 15 * 60 * 1_000L,
                "Too many registration attempts. Try again later.");

        UserEntity user = new UserEntity();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("USER");
        user.setVerifiedAccount(false);
        String otp = otpService.assignVerificationOtp(user);

        try {
            UserEntity savedUser = userRepository.save(user);
            otpDeliveryService.sendVerificationOtp(savedUser.getEmail(), otp);
            return new MessageResponse("Account created. Enter the verification code to activate it.", savedUser.getEmail());
        } catch (DataIntegrityViolationException e) {
            // Respond identically to a fresh registration so this endpoint can't be used to
            // discover which emails are registered. Notify the real owner out-of-band.
            otpDeliveryService.sendAccountExists(email);
            return new MessageResponse("Account created. Enter the verification code to activate it.", email);
        }
    }

    public AuthResponse login(LoginRequest request, String clientIp) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Login details are required.");
        }

        String email = normalizeEmail(request.email());
        String password = normalizeText(request.password());

        // Bound both inputs before they become rate-limiter map keys or reach BCrypt: an
        // unbounded email would be stored verbatim as a limiter key (memory abuse) and an
        // unbounded password fed to the hasher. Length-only (not format) so an oversized value
        // returns the same generic 401 as any wrong credential and stays enumeration-safe.
        if (email.length() > MAX_EMAIL_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        // Per-IP first (broad spray defence), then per-email (single-account brute force).
        // The IP bucket is never reset on success: interleaving valid logins must not let
        // an attacker clear their spray budget.
        if (clientIp != null && !clientIp.isBlank()) {
            rateLimiter.check("login-ip:" + clientIp, MAX_LOGIN_ATTEMPTS_PER_IP, LOGIN_WINDOW_MS,
                    "Too many login attempts. Try again later.");
        }
        rateLimiter.check("login:" + email, MAX_LOGIN_ATTEMPTS, LOGIN_WINDOW_MS,
                "Too many login attempts. Try again later.");

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        if (!Boolean.TRUE.equals(user.getVerifiedAccount())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not verified.");
        }

        rateLimiter.reset("login:" + email);
        return new AuthResponse(tokenService.createToken(user), UserResponse.from(user));
    }

    public AuthResponse verifyAccount(VerifyAccountRequest request, String clientIp) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification details are required.");
        }

        String email = normalizeEmail(request.email());
        String otp = normalizeText(request.otp());
        validateEmail(email);

        // Per-IP first (spray across accounts), then per-email (guesses at one account).
        if (clientIp != null && !clientIp.isBlank()) {
            rateLimiter.check("otp-verify-ip:" + clientIp, MAX_OTP_ATTEMPTS_PER_IP, OTP_WINDOW_MS,
                    "Too many verification attempts. Try again later.");
        }
        rateLimiter.check("otp-verify:" + email, MAX_OTP_ATTEMPTS, OTP_WINDOW_MS,
                "Too many verification attempts. Request a new code.");

        UserEntity user = userRepository.findByEmail(email).orElse(null);

        // Every failure (no such account, already verified, wrong/expired/malformed code) returns
        // the same status and body so verify-account can't be used to probe which emails exist or
        // are unverified. An already-verified account is treated like a missing one (no live OTP),
        // so it can't be a candidate. matchesVerificationOtp spends exactly one BCrypt compare on
        // every path — real hash for a live candidate, dummy hash otherwise — so the branches are
        // also indistinguishable by timing.
        UserEntity candidate = (user != null && !Boolean.TRUE.equals(user.getVerifiedAccount())) ? user : null;
        if (!otpService.matchesVerificationOtp(candidate, otp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification code.");
        }

        otpService.markVerified(user);
        UserEntity savedUser = userRepository.save(user);

        rateLimiter.reset("otp-verify:" + email);
        return new AuthResponse(tokenService.createToken(savedUser), UserResponse.from(savedUser));
    }

    public MessageResponse resendVerificationOtp(ResendVerificationOtpRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required.");
        }

        String email = normalizeEmail(request.email());
        validateEmail(email);

        rateLimiter.check("otp-resend:" + email, MAX_RESEND_ATTEMPTS, RESEND_WINDOW_MS,
                "Too many resend requests. Try again later.");

        userRepository.findByEmail(email)
                .filter(user -> !Boolean.TRUE.equals(user.getVerifiedAccount()))
                .ifPresent(user -> {
                    String otp = otpService.assignVerificationOtp(user);
                    UserEntity savedUser = userRepository.save(user);
                    otpDeliveryService.sendVerificationOtp(savedUser.getEmail(), otp);
                });

        return new MessageResponse("If the account requires verification, a new code was sent.", email);
    }

    public MessageResponse forgotPassword(ForgotPasswordRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required.");
        }

        String email = normalizeEmail(request.email());
        validateEmail(email);

        rateLimiter.check("forgot-password:" + email, 3, 15 * 60 * 1_000L,
                "Too many password reset requests. Try again later.");

        // Email a one-time reset code and leave the password untouched. The password only
        // changes in resetPassword once the caller proves inbox control by returning the code,
        // so knowing an email address can no longer lock another user out.
        userRepository.findByEmail(email)
                .filter(user -> Boolean.TRUE.equals(user.getVerifiedAccount()))
                .ifPresent(user -> {
                    String otp = otpService.assignResetOtp(user);
                    userRepository.save(user);
                    otpDeliveryService.sendPasswordResetCode(email, otp);
                });

        return new MessageResponse("If an account with that email exists, a reset code has been sent.", email);
    }

    public MessageResponse resetPassword(ResetPasswordRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset details are required.");
        }

        String email = normalizeEmail(request.email());
        String otp = normalizeText(request.otp());
        String newPassword = normalizeText(request.newPassword());
        validateEmail(email);

        rateLimiter.check("reset-password:" + email, MAX_OTP_ATTEMPTS, OTP_WINDOW_MS,
                "Too many reset attempts. Request a new code.");

        UserEntity user = userRepository.findByEmail(email)
                .filter(u -> Boolean.TRUE.equals(u.getVerifiedAccount()))
                .orElse(null);

        if (user == null || !otpService.isValidResetOtp(user, otp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset code.");
        }

        validatePassword(newPassword);

        user.setPassword(passwordEncoder.encode(newPassword));
        otpService.clearResetOtp(user);
        // Invalidate every auth token issued before now (see TokenAuthenticationFilter).
        user.setPasswordChangedAt(Instant.now().toEpochMilli());
        userRepository.save(user);
        rateLimiter.reset("reset-password:" + email);

        return new MessageResponse("Your password has been reset. You can now sign in.", email);
    }

    public MessageResponse changePassword(ChangePasswordRequest request, String email) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Change password details are required.");
        }

        String currentPassword = normalizeText(request.currentPassword());
        String newPassword = normalizeText(request.newPassword());

        rateLimiter.check("change-password:" + email, 5, 15 * 60 * 1_000L,
                "Too many password change attempts. Try again later.");

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists."));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }

        validatePassword(newPassword);

        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must differ from the current password.");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        // Kill any still-valid reset code so a password change closes that second takeover path.
        otpService.clearResetOtp(user);
        // Invalidate every auth token issued before now (see TokenAuthenticationFilter); this
        // logs out all sessions, including the one making this request (force re-login).
        user.setPasswordChangedAt(Instant.now().toEpochMilli());
        userRepository.save(user);
        rateLimiter.reset("change-password:" + email);

        return new MessageResponse("Password changed successfully.", email);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeEmail(String value) {
        return normalizeText(value).toLowerCase(Locale.ROOT);
    }

    private void validateEmail(String email) {
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required.");
        }
    }

    private void validatePassword(String password) {
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters.");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must not exceed " + MAX_PASSWORD_LENGTH + " characters.");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must contain at least one letter and one digit.");
        }
    }
}
