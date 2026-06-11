package com.qwertyblob.every1luvs.dto;

public record ResetPasswordRequest(String email, String otp, String newPassword) {
}
