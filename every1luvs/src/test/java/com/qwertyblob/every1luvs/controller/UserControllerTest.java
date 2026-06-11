package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.ChangePasswordRequest;
import com.qwertyblob.every1luvs.dto.MessageResponse;
import com.qwertyblob.every1luvs.dto.PageResponse;
import com.qwertyblob.every1luvs.dto.UserResponse;
import com.qwertyblob.every1luvs.service.AuthCookieService;
import com.qwertyblob.every1luvs.service.AuthService;
import com.qwertyblob.every1luvs.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock UserService userService;
    @Mock AuthService authService;
    @Mock AuthCookieService authCookieService;

    @InjectMocks UserController userController;

    // ─── me ──────────────────────────────────────────────────────────────────────

    @Test
    void me_authenticatedUser_returnsUserResponse() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("alice@example.com");
        UserResponse expected = new UserResponse(1L, "Alice", "alice@example.com", "USER");
        when(userService.getByEmail("alice@example.com")).thenReturn(expected);

        UserResponse result = userController.me(auth);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void me_nullAuthentication_throwsUnauthorized() {
        assertThatThrownBy(() -> userController.me(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(userService);
    }

    @Test
    void me_notAuthenticated_throwsUnauthorized() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(false);

        assertThatThrownBy(() -> userController.me(auth))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(userService);
    }

    @Test
    void me_serviceRejectsMissingUser_propagatesUnauthorized() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("ghost@example.com");
        when(userService.getByEmail("ghost@example.com"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists."));

        assertThatThrownBy(() -> userController.me(auth))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("User no longer exists.");
                });
    }

    // ─── changePassword ──────────────────────────────────────────────────────────

    @Test
    void changePassword_delegatesToAuthServiceAndClearsCookie() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("alice@example.com");
        ChangePasswordRequest request = new ChangePasswordRequest("OldPass1", "NewPass1");
        MessageResponse expected = new MessageResponse("Password changed.", "alice@example.com");
        when(authService.changePassword(request, "alice@example.com")).thenReturn(expected);
        when(authCookieService.clear()).thenReturn(ResponseCookie.from("auth_token", "").maxAge(0).build());
        HttpServletResponse response = mock(HttpServletResponse.class);

        MessageResponse result = userController.changePassword(request, auth, response);

        assertThat(result).isSameAs(expected);
        // Force re-login: the now-invalid session cookie is cleared in the response.
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void changePassword_nullAuthentication_throwsUnauthorized() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        assertThatThrownBy(() -> userController.changePassword(
                new ChangePasswordRequest("OldPass1", "NewPass1"), null, response))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(authService);
    }

    // ─── users (admin) ───────────────────────────────────────────────────────────

    @Test
    void users_returnsPageFromService() {
        List<UserResponse> users = List.of(
                new UserResponse(1L, "Alice", "alice@example.com", "USER"),
                new UserResponse(2L, "Bob", "bob@example.com", "ADMIN")
        );
        Pageable pageable = PageRequest.of(0, 50);
        Page<UserResponse> page = new PageImpl<>(users, pageable, 2);
        when(userService.listAll(pageable)).thenReturn(page);

        PageResponse<UserResponse> result = userController.users(pageable);

        assertThat(result.content()).isEqualTo(users);
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    void users_emptyPage_returnsEmptyContent() {
        Pageable pageable = PageRequest.of(0, 50);
        when(userService.listAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(userController.users(pageable).content()).isEmpty();
    }
}
