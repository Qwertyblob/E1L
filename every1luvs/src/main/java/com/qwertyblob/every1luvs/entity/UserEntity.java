package com.qwertyblob.every1luvs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "tbl_users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;

    private String verifyOtp;

    @Column(name = "is_verified_account", nullable = false)
    private Boolean isVerifiedAccount;

    private long verifyOtpExpireAt;

    private String resetOtp;

    private long resetOtpExpireAt;

    // Epoch seconds of the last password change/reset. Tokens issued before this instant
    // are rejected by TokenAuthenticationFilter, so a password change logs out all sessions.
    private long passwordChangedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @PrePersist
    void applyDefaults() {
        if (role == null || role.isBlank()) {
            role = "USER";
        }
        if (isVerifiedAccount == null) {
            isVerifiedAccount = false;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getVerifyOtp() {
        return verifyOtp;
    }

    public void setVerifyOtp(String verifyOtp) {
        this.verifyOtp = verifyOtp;
    }

    public Boolean getVerifiedAccount() {
        return isVerifiedAccount;
    }

    public void setVerifiedAccount(Boolean verifiedAccount) {
        isVerifiedAccount = verifiedAccount;
    }

    public long getVerifyOtpExpireAt() {
        return verifyOtpExpireAt;
    }

    public void setVerifyOtpExpireAt(long verifyOtpExpireAt) {
        this.verifyOtpExpireAt = verifyOtpExpireAt;
    }

    public String getResetOtp() {
        return resetOtp;
    }

    public void setResetOtp(String resetOtp) {
        this.resetOtp = resetOtp;
    }

    public long getResetOtpExpireAt() {
        return resetOtpExpireAt;
    }

    public void setResetOtpExpireAt(long resetOtpExpireAt) {
        this.resetOtpExpireAt = resetOtpExpireAt;
    }

    public long getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(long passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

}
