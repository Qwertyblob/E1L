package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.ChangePasswordRequest;
import com.qwertyblob.every1luvs.dto.MessageResponse;
import com.qwertyblob.every1luvs.dto.PageResponse;
import com.qwertyblob.every1luvs.dto.UserResponse;
import com.qwertyblob.every1luvs.service.AuthCookieService;
import com.qwertyblob.every1luvs.service.AuthService;
import com.qwertyblob.every1luvs.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class UserController {
    private final UserService userService;
    private final AuthService authService;
    private final AuthCookieService authCookieService;

    public UserController(UserService userService, AuthService authService,
                          AuthCookieService authCookieService) {
        this.userService = userService;
        this.authService = authService;
        this.authCookieService = authCookieService;
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userService.getByEmail(authentication.getName());
    }

    @PostMapping("/me/change-password")
    public MessageResponse changePassword(@RequestBody ChangePasswordRequest request, Authentication authentication,
                                          HttpServletResponse response) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        MessageResponse result = authService.changePassword(request, authentication.getName());

        // The change invalidates this session's token too; clear the cookie so the client is
        // logged out immediately instead of on its next (now-401) request.
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clear().toString());
        return result;
    }

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserResponse> users(@PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(userService.listAll(pageable));
    }
}
