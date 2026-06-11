package com.qwertyblob.every1luvs.security;

public record TokenClaims(String email, String role, long issuedAt) {
}
