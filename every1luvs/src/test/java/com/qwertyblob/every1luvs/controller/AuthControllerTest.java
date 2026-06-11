package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.AuthResponse;
import com.qwertyblob.every1luvs.dto.ForgotPasswordRequest;
import com.qwertyblob.every1luvs.dto.LoginRequest;
import com.qwertyblob.every1luvs.dto.MessageResponse;
import com.qwertyblob.every1luvs.dto.RegisterRequest;
import com.qwertyblob.every1luvs.dto.ResendVerificationOtpRequest;
import com.qwertyblob.every1luvs.dto.ResetPasswordRequest;
import com.qwertyblob.every1luvs.dto.UserResponse;
import com.qwertyblob.every1luvs.dto.VerifyAccountRequest;
import com.qwertyblob.every1luvs.security.ClientIpResolver;
import com.qwertyblob.every1luvs.service.AuthCookieService;
import com.qwertyblob.every1luvs.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock AuthService authService;
    @Mock AuthCookieService authCookieService;
    @Mock ClientIpResolver clientIpResolver;

    @InjectMocks AuthController authController;

    private static final UserResponse USER = new UserResponse(1L, "Alice", "alice@example.com", "USER");

    @Test
    void register_delegatesToService() {
        RegisterRequest request = new RegisterRequest("Alice", "alice@example.com", "Password1");
        MessageResponse expected = new MessageResponse("Registered.", "alice@example.com");
        when(authService.register(request)).thenReturn(expected);

        MessageResponse result = authController.register(request);

        assertThat(result).isSameAs(expected);
        verify(authService).register(request);
    }

    @Test
    void login_setsAuthCookieAndReturnsUserWithoutToken() {
        LoginRequest request = new LoginRequest("alice@example.com", "Password1");
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(clientIpResolver.resolve(httpRequest)).thenReturn("203.0.113.5");
        when(authService.login(request, "203.0.113.5")).thenReturn(new AuthResponse("jwt-token", USER));
        when(authCookieService.build("jwt-token"))
                .thenReturn(ResponseCookie.from("auth_token", "jwt-token").build());
        MockHttpServletResponse response = new MockHttpServletResponse();

        UserResponse result = authController.login(request, httpRequest, response);

        assertThat(result).isEqualTo(USER);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("auth_token=jwt-token");
        verify(authCookieService).build("jwt-token");
        // The resolved client IP must reach the service so the per-IP login limiter keys on it.
        verify(authService).login(request, "203.0.113.5");
    }

    @Test
    void verifyAccount_setsAuthCookieAndReturnsUser() {
        VerifyAccountRequest request = new VerifyAccountRequest("alice@example.com", "123456");
        when(authService.verifyAccount(request)).thenReturn(new AuthResponse("jwt-token", USER));
        when(authCookieService.build("jwt-token"))
                .thenReturn(ResponseCookie.from("auth_token", "jwt-token").build());
        MockHttpServletResponse response = new MockHttpServletResponse();

        UserResponse result = authController.verifyAccount(request, response);

        assertThat(result).isEqualTo(USER);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("auth_token=jwt-token");
    }

    @Test
    void logout_clearsAuthCookie() {
        when(authCookieService.clear())
                .thenReturn(ResponseCookie.from("auth_token", "").maxAge(0).build());
        MockHttpServletResponse response = new MockHttpServletResponse();

        MessageResponse result = authController.logout(response);

        assertThat(result.message()).isEqualTo("Logged out.");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("auth_token=");
        verify(authCookieService).clear();
        verifyNoInteractions(authService);
    }

    @Test
    void resendVerificationOtp_delegatesToService() {
        ResendVerificationOtpRequest request = new ResendVerificationOtpRequest("alice@example.com");
        MessageResponse expected = new MessageResponse("Sent.", "alice@example.com");
        when(authService.resendVerificationOtp(request)).thenReturn(expected);

        MessageResponse result = authController.resendVerificationOtp(request);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void forgotPassword_delegatesToService() {
        ForgotPasswordRequest request = new ForgotPasswordRequest("alice@example.com");
        MessageResponse expected = new MessageResponse("Reset.", "alice@example.com");
        when(authService.forgotPassword(request)).thenReturn(expected);

        MessageResponse result = authController.forgotPassword(request);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void resetPassword_delegatesToService() {
        ResetPasswordRequest request = new ResetPasswordRequest("alice@example.com", "123456", "NewPass1");
        MessageResponse expected = new MessageResponse("Reset done.", "alice@example.com");
        when(authService.resetPassword(request)).thenReturn(expected);

        MessageResponse result = authController.resetPassword(request);

        assertThat(result).isSameAs(expected);
    }
}
