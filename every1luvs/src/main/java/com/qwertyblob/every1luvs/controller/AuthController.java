package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.AuthResponse;
import com.qwertyblob.every1luvs.dto.ForgotPasswordRequest;
import com.qwertyblob.every1luvs.dto.LoginRequest;
import com.qwertyblob.every1luvs.dto.MessageResponse;
import com.qwertyblob.every1luvs.dto.ResendVerificationOtpRequest;
import com.qwertyblob.every1luvs.dto.ResetPasswordRequest;
import com.qwertyblob.every1luvs.dto.RegisterRequest;
import com.qwertyblob.every1luvs.dto.UserResponse;
import com.qwertyblob.every1luvs.dto.VerifyAccountRequest;
import com.qwertyblob.every1luvs.security.ClientIpResolver;
import com.qwertyblob.every1luvs.service.AuthCookieService;
import com.qwertyblob.every1luvs.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthCookieService authCookieService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService, AuthCookieService authCookieService,
                          ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.authCookieService = authCookieService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/register")
    public MessageResponse register(@RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public UserResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
                              HttpServletResponse response) {
        // Client IP feeds the per-IP spray limiter alongside the per-email bucket.
        return issueSession(authService.login(request, clientIpResolver.resolve(httpRequest)), response);
    }

    @PostMapping("/verify-account")
    public UserResponse verifyAccount(@RequestBody VerifyAccountRequest request, HttpServletRequest httpRequest,
                                      HttpServletResponse response) {
        // Client IP feeds the per-IP verify limiter alongside the per-email bucket.
        return issueSession(authService.verifyAccount(request, clientIpResolver.resolve(httpRequest)), response);
    }

    @PostMapping("/logout")
    public MessageResponse logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clear().toString());
        return new MessageResponse("Logged out.", null);
    }

    /**
     * Writes the JWT into the httpOnly auth cookie and returns only the user — the
     * token is never placed in the JSON body, so client-side JS (and any XSS) can't read it.
     */
    private UserResponse issueSession(AuthResponse authResult, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.build(authResult.token()).toString());
        return authResult.user();
    }

    @PostMapping("/resend-verification-otp")
    public MessageResponse resendVerificationOtp(@RequestBody ResendVerificationOtpRequest request) {
        return authService.resendVerificationOtp(request);
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }
}
