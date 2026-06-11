package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.UserResponse;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;

    @InjectMocks UserService userService;

    private static UserEntity user(Long id, String name, String email, String role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    @Test
    void getByEmail_existingUser_returnsUserResponse() {
        when(userRepository.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(user(1L, "Alice", "alice@example.com", "USER")));

        assertThat(userService.getByEmail("alice@example.com"))
                .isEqualTo(new UserResponse(1L, "Alice", "alice@example.com", "USER"));
    }

    @Test
    void getByEmail_missingUser_throwsUnauthorized() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByEmail("ghost@example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("User no longer exists.");
                });
    }

    @Test
    void listAll_mapsAllUsersToResponses() {
        Pageable pageable = PageRequest.of(0, 50);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(
                user(1L, "Alice", "alice@example.com", "USER"),
                user(2L, "Bob", "bob@example.com", "ADMIN")
        ), pageable, 2));

        Page<UserResponse> result = userService.listAll(pageable);

        assertThat(result.getContent()).containsExactly(
                new UserResponse(1L, "Alice", "alice@example.com", "USER"),
                new UserResponse(2L, "Bob", "bob@example.com", "ADMIN")
        );
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void listAll_emptyRepository_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 50);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(userService.listAll(pageable)).isEmpty();
    }
}
