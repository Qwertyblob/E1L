package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.AuthResponse;
import com.qwertyblob.every1luvs.dto.ChangePasswordRequest;
import com.qwertyblob.every1luvs.dto.ForgotPasswordRequest;
import com.qwertyblob.every1luvs.dto.LoginRequest;
import com.qwertyblob.every1luvs.dto.MessageResponse;
import com.qwertyblob.every1luvs.dto.RegisterRequest;
import com.qwertyblob.every1luvs.dto.ResendVerificationOtpRequest;
import com.qwertyblob.every1luvs.dto.ResetPasswordRequest;
import com.qwertyblob.every1luvs.dto.VerifyAccountRequest;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TokenService tokenService;
    @Mock OtpService otpService;
    @Mock OtpDeliveryService otpDeliveryService;
    @Mock RateLimiterService rateLimiter;

    @InjectMocks AuthService authService;

    // ─── register ───────────────────────────────────────────────────────────────

    @Test
    void register_happyPath_savesUserAndSendsOtp() {
        when(otpService.assignVerificationOtp(any())).thenReturn("123456");
        when(userRepository.save(any())).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });

        MessageResponse response = authService.register(new RegisterRequest("Alice", "alice@example.com", "Password1"));

        assertThat(response.email()).isEqualTo("alice@example.com");
        verify(userRepository).save(any());
        verify(otpDeliveryService).sendVerificationOtp("alice@example.com", "123456");
    }

    @Test
    void register_emailNormalized() {
        when(otpService.assignVerificationOtp(any())).thenReturn("123456");
        when(userRepository.save(any())).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });

        MessageResponse response = authService.register(new RegisterRequest("Alice", "  ALICE@Example.COM  ", "Password1"));

        assertThat(response.email()).isEqualTo("alice@example.com");
    }

    @Test
    void register_nullRequest_throws400() {
        var ex = catchThrowableOfType(() -> authService.register(null), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_blankName_throws400() {
        var ex = catchThrowableOfType(
                () -> authService.register(new RegisterRequest("  ", "alice@example.com", "Password1")),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_nameTooLong_throws400() {
        String longName = "A".repeat(101);
        var ex = catchThrowableOfType(
                () -> authService.register(new RegisterRequest(longName, "alice@example.com", "Password1")),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_invalidEmail_throws400() {
        var ex = catchThrowableOfType(
                () -> authService.register(new RegisterRequest("Alice", "not-an-email", "Password1")),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_passwordTooShort_throws400() {
        var ex = catchThrowableOfType(
                () -> authService.register(new RegisterRequest("Alice", "alice@example.com", "Pass1")),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_passwordNoDigit_throws400() {
        var ex = catchThrowableOfType(
                () -> authService.register(new RegisterRequest("Alice", "alice@example.com", "PasswordOnly")),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_passwordNoLetter_throws400() {
        var ex = catchThrowableOfType(
                () -> authService.register(new RegisterRequest("Alice", "alice@example.com", "12345678")),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_duplicateEmail_returnsNeutralResponseAndNotifiesOwner() {
        when(otpService.assignVerificationOtp(any())).thenReturn("123456");
        when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        // Account enumeration defence: respond as if registration succeeded, notify owner out-of-band.
        MessageResponse response = authService.register(new RegisterRequest("Alice", "alice@example.com", "Password1"));

        assertThat(response.email()).isEqualTo("alice@example.com");
        verify(otpDeliveryService).sendAccountExists("alice@example.com");
        verify(otpDeliveryService, never()).sendVerificationOtp(anyString(), anyString());
    }

    @Test
    void register_rateLimited_throws429AndSendsNoMail() {
        // Once the per-email register bucket is exhausted, the limiter throws 429 and register
        // must propagate it without persisting the user or sending any mail.
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many registration attempts. Try again later."))
                .when(rateLimiter).check(eq("register:alice@example.com"), anyInt(), anyLong(), anyString());

        var ex = catchThrowableOfType(
                () -> authService.register(new RegisterRequest("Alice", "alice@example.com", "Password1")),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(userRepository, never()).save(any());
        verify(otpDeliveryService, never()).sendVerificationOtp(anyString(), anyString());
        verify(otpDeliveryService, never()).sendAccountExists(anyString());
    }

    // ─── login ──────────────────────────────────────────────────────────────────

    @Test
    void login_happyPath_returnsAuthResponse() {
        UserEntity user = verifiedUser();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1", "hashed")).thenReturn(true);
        when(tokenService.createToken(user)).thenReturn("jwt-token");

        AuthResponse response = authService.login(
                new LoginRequest("alice@example.com", "Password1"), "203.0.113.5");

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.user().email()).isEqualTo("alice@example.com");
        // Per-IP and per-email buckets are both consulted; only the email bucket resets on
        // success (clearing the IP bucket would let an attacker interleave valid logins to
        // refill their spray budget).
        verify(rateLimiter).check(eq("login-ip:203.0.113.5"), anyInt(), anyLong(), anyString());
        verify(rateLimiter).check(eq("login:alice@example.com"), anyInt(), anyLong(), anyString());
        verify(rateLimiter).reset("login:alice@example.com");
        verify(rateLimiter, never()).reset("login-ip:203.0.113.5");
    }

    @Test
    void login_nullRequest_throws400() {
        var ex = catchThrowableOfType(() -> authService.login(null, "203.0.113.5"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void login_unknownEmail_throws401() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(
                () -> authService.login(new LoginRequest("unknown@example.com", "Password1"), "203.0.113.5"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_oversizedCredentials_rejectedBeforeKeysBucketsOrBcrypt() {
        // An unbounded email would be stored verbatim as a rate-limiter map key and an unbounded
        // password fed to BCrypt. The length guard runs first, so neither the limiter, the
        // repository, nor the hasher is ever touched — and it returns the same generic 401.
        String hugeEmail = "a".repeat(300) + "@example.com";
        var ex = catchThrowableOfType(
                () -> authService.login(new LoginRequest(hugeEmail, "Password1"), "203.0.113.5"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo("Invalid email or password.");
        verifyNoInteractions(rateLimiter, userRepository);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void login_wrongPassword_throws401() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(verifiedUser()));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        var ex = catchThrowableOfType(
                () -> authService.login(new LoginRequest("alice@example.com", "WrongPass1"), "203.0.113.5"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_unverifiedAccount_throws403() {
        UserEntity user = verifiedUser();
        user.setVerifiedAccount(false);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        var ex = catchThrowableOfType(
                () -> authService.login(new LoginRequest("alice@example.com", "Password1"), "203.0.113.5"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void login_ipRateLimited_throws429BeforeTouchingEmailBucketOrRepository() {
        // A sprayed IP is cut off regardless of which email it targets — and before the
        // attempt consumes the victim's per-email budget or hits the database.
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Try again later."))
                .when(rateLimiter).check(eq("login-ip:203.0.113.5"), anyInt(), anyLong(), anyString());

        var ex = catchThrowableOfType(
                () -> authService.login(new LoginRequest("alice@example.com", "Password1"), "203.0.113.5"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(rateLimiter, never()).check(eq("login:alice@example.com"), anyInt(), anyLong(), anyString());
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void login_nullClientIp_skipsIpBucket() {
        // Non-web callers (or tests) without a resolvable IP still get per-email limiting.
        UserEntity user = verifiedUser();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1", "hashed")).thenReturn(true);
        when(tokenService.createToken(user)).thenReturn("jwt-token");

        AuthResponse response = authService.login(new LoginRequest("alice@example.com", "Password1"), null);

        assertThat(response.token()).isEqualTo("jwt-token");
        verify(rateLimiter, never()).check(startsWith("login-ip:"), anyInt(), anyLong(), anyString());
        verify(rateLimiter).check(eq("login:alice@example.com"), anyInt(), anyLong(), anyString());
    }

    // ─── verifyAccount ──────────────────────────────────────────────────────────

    @Test
    void verifyAccount_happyPath_returnsAuthResponse() {
        UserEntity user = unverifiedUser();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(otpService.isValidVerificationOtp(user, "123456")).thenReturn(true);
        when(userRepository.save(user)).thenReturn(user);
        when(tokenService.createToken(user)).thenReturn("jwt-token");

        AuthResponse response = authService.verifyAccount(new VerifyAccountRequest("alice@example.com", "123456"), "203.0.113.5");

        assertThat(response.token()).isEqualTo("jwt-token");
        verify(otpService).markVerified(user);
        verify(rateLimiter).reset("otp-verify:alice@example.com");
    }

    @Test
    void verifyAccount_nullRequest_throws400() {
        var ex = catchThrowableOfType(() -> authService.verifyAccount(null, "203.0.113.5"), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyAccount_userNotFound_returnsUniform400AndSpendsABcryptCompare() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(
                () -> authService.verifyAccount(new VerifyAccountRequest("ghost@example.com", "123456"), "203.0.113.5"),
                ResponseStatusException.class);
        // Same status/body as a wrong code — no signal that the account doesn't exist.
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo("Invalid or expired verification code.");
        // Timing equalization: the missing-user branch runs a dummy compare so it doesn't finish
        // measurably faster than a real unverified account (which runs the real OTP compare).
        verify(passwordEncoder).matches(eq("123456"), any());
        verify(otpService, never()).isValidVerificationOtp(any(), any());
    }

    @Test
    void verifyAccount_alreadyVerified_returnsUniform400AndSpendsABcryptCompare() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(verifiedUser()));
        var ex = catchThrowableOfType(
                () -> authService.verifyAccount(new VerifyAccountRequest("alice@example.com", "123456"), "203.0.113.5"),
                ResponseStatusException.class);
        // Already-verified is now indistinguishable from missing / wrong-code (was 409 before).
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo("Invalid or expired verification code.");
        verify(passwordEncoder).matches(eq("123456"), any());
    }

    @Test
    void verifyAccount_invalidOtp_throws400() {
        UserEntity user = unverifiedUser();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(otpService.isValidVerificationOtp(user, "000000")).thenReturn(false);

        var ex = catchThrowableOfType(
                () -> authService.verifyAccount(new VerifyAccountRequest("alice@example.com", "000000"), "203.0.113.5"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo("Invalid or expired verification code.");
    }

    // ─── resendVerificationOtp ───────────────────────────────────────────────────

    @Test
    void resendVerificationOtp_unverifiedUser_sendsOtp() {
        UserEntity user = unverifiedUser();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(otpService.assignVerificationOtp(user)).thenReturn("654321");
        when(userRepository.save(user)).thenReturn(user);

        MessageResponse response = authService.resendVerificationOtp(new ResendVerificationOtpRequest("alice@example.com"));

        assertThat(response.email()).isEqualTo("alice@example.com");
        verify(otpDeliveryService).sendVerificationOtp("alice@example.com", "654321");
    }

    @Test
    void resendVerificationOtp_alreadyVerifiedUser_silentNoOp() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(verifiedUser()));

        MessageResponse response = authService.resendVerificationOtp(new ResendVerificationOtpRequest("alice@example.com"));

        assertThat(response.email()).isEqualTo("alice@example.com");
        verifyNoInteractions(otpDeliveryService);
    }

    @Test
    void resendVerificationOtp_nullRequest_throws400() {
        var ex = catchThrowableOfType(() -> authService.resendVerificationOtp(null), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── forgotPassword ─────────────────────────────────────────────────────────

    @Test
    void forgotPassword_verifiedUser_assignsResetOtpAndSendsCodeWithoutChangingPassword() {
        UserEntity user = verifiedUser();
        String originalHash = user.getPassword();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(otpService.assignResetOtp(user)).thenReturn("123456");
        when(userRepository.save(user)).thenReturn(user);

        MessageResponse response = authService.forgotPassword(new ForgotPasswordRequest("alice@example.com"));

        assertThat(response.email()).isEqualTo("alice@example.com");
        verify(otpService).assignResetOtp(user);
        verify(otpDeliveryService).sendPasswordResetCode("alice@example.com", "123456");
        // The password must NOT change just because someone requested a reset. (Exclude the
        // fixed "000000" the constructor encodes once to build the verify-account dummy hash.)
        verify(passwordEncoder, never()).encode(argThat(pw -> !"000000".equals(pw)));
        assertThat(user.getPassword()).isEqualTo(originalHash);
    }

    @Test
    void forgotPassword_unverifiedUser_silentNoOp() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(unverifiedUser()));

        authService.forgotPassword(new ForgotPasswordRequest("alice@example.com"));

        verifyNoInteractions(otpDeliveryService);
    }

    @Test
    void forgotPassword_nullRequest_throws400() {
        var ex = catchThrowableOfType(() -> authService.forgotPassword(null), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── resetPassword ──────────────────────────────────────────────────────────

    @Test
    void resetPassword_validCode_updatesPasswordAndClearsOtp() {
        UserEntity user = verifiedUser();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(otpService.isValidResetOtp(user, "123456")).thenReturn(true);
        when(passwordEncoder.encode("NewPass1")).thenReturn("new-hashed");
        when(userRepository.save(user)).thenReturn(user);

        MessageResponse response = authService.resetPassword(
                new ResetPasswordRequest("alice@example.com", "123456", "NewPass1"));

        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(user.getPassword()).isEqualTo("new-hashed");
        verify(otpService).clearResetOtp(user);
        verify(rateLimiter).reset("reset-password:alice@example.com");
    }

    @Test
    void resetPassword_invalidCode_throws400AndKeepsPassword() {
        UserEntity user = verifiedUser();
        String originalHash = user.getPassword();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(otpService.isValidResetOtp(user, "000000")).thenReturn(false);

        var ex = catchThrowableOfType(
                () -> authService.resetPassword(new ResetPasswordRequest("alice@example.com", "000000", "NewPass1")),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(user.getPassword()).isEqualTo(originalHash);
        verify(otpService, never()).clearResetOtp(any());
    }

    @Test
    void resetPassword_nullRequest_throws400() {
        var ex = catchThrowableOfType(() -> authService.resetPassword(null), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── changePassword ─────────────────────────────────────────────────────────

    @Test
    void changePassword_happyPath_updatesPasswordAndReturnsMessage() {
        UserEntity user = verifiedUser();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass1", "hashed")).thenReturn(true);
        when(passwordEncoder.matches("NewPass1", "hashed")).thenReturn(false);
        when(passwordEncoder.encode("NewPass1")).thenReturn("new-hashed");
        when(userRepository.save(user)).thenReturn(user);

        MessageResponse response = authService.changePassword(
                new ChangePasswordRequest("OldPass1", "NewPass1"), "alice@example.com");

        assertThat(response.message()).contains("Password changed");
        assertThat(user.getPassword()).isEqualTo("new-hashed");
        verify(rateLimiter).reset("change-password:alice@example.com");
        // P2: a change must clear any pending reset OTP and bump passwordChangedAt so
        // outstanding reset codes and previously-issued tokens are both invalidated.
        verify(otpService).clearResetOtp(user);
        assertThat(user.getPasswordChangedAt()).isPositive();
    }

    @Test
    void changePassword_nullRequest_throws400() {
        var ex = catchThrowableOfType(
                () -> authService.changePassword(null, "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changePassword_userNotFound_throws401() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(
                () -> authService.changePassword(
                        new ChangePasswordRequest("OldPass1", "NewPass1"), "ghost@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changePassword_wrongCurrentPassword_throws401() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(verifiedUser()));
        when(passwordEncoder.matches("WrongPass1", "hashed")).thenReturn(false);

        var ex = catchThrowableOfType(
                () -> authService.changePassword(
                        new ChangePasswordRequest("WrongPass1", "NewPass1"), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changePassword_newPasswordSameAsCurrent_throws400() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(verifiedUser()));
        when(passwordEncoder.matches("OldPass1", "hashed")).thenReturn(true);
        when(passwordEncoder.matches("OldPass1", "hashed")).thenReturn(true); // same check for "new == current"

        var ex = catchThrowableOfType(
                () -> authService.changePassword(
                        new ChangePasswordRequest("OldPass1", "OldPass1"), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changePassword_newPasswordTooShort_throws400() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(verifiedUser()));
        when(passwordEncoder.matches("OldPass1", "hashed")).thenReturn(true);

        var ex = catchThrowableOfType(
                () -> authService.changePassword(
                        new ChangePasswordRequest("OldPass1", "Bad1"), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changePassword_newPasswordNoDigit_throws400() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(verifiedUser()));
        when(passwordEncoder.matches("OldPass1", "hashed")).thenReturn(true);

        var ex = catchThrowableOfType(
                () -> authService.changePassword(
                        new ChangePasswordRequest("OldPass1", "NoDigitPassword"), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private UserEntity verifiedUser() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("Alice");
        user.setEmail("alice@example.com");
        user.setPassword("hashed");
        user.setRole("USER");
        user.setVerifiedAccount(true);
        return user;
    }

    private UserEntity unverifiedUser() {
        UserEntity user = verifiedUser();
        user.setVerifiedAccount(false);
        return user;
    }
}
