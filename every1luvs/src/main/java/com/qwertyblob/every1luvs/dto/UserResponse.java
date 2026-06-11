package com.qwertyblob.every1luvs.dto;

import com.qwertyblob.every1luvs.entity.UserEntity;

public record UserResponse(Long id, String name, String email, String role) {
    public static UserResponse from(UserEntity user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
