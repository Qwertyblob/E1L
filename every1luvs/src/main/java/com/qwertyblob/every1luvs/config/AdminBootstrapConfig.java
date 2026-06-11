package com.qwertyblob.every1luvs.config;

import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

@Configuration
public class AdminBootstrapConfig {
    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrapConfig.class);

    @Bean
    public ApplicationRunner bootstrapAdmin(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.auth.bootstrap-admin-enabled}") boolean enabled,
            @Value("${app.auth.bootstrap-admin-name}") String name,
            @Value("${app.auth.bootstrap-admin-email}") String email,
            @Value("${app.auth.bootstrap-admin-password}") String password
    ) {
        return args -> {
            if (!enabled) {
                return;
            }

            String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            if (normalizedEmail.isBlank() || password == null || password.length() < 8) {
                throw new IllegalStateException("Bootstrap admin email and password are not valid.");
            }

            var existingAdmin = userRepository.findByEmail(normalizedEmail);
            if (existingAdmin.isPresent()) {
                UserEntity admin = existingAdmin.get();
                // Don't silently promote an account we can't prove the operator controls.
                // If someone pre-registered the bootstrap email, the configured password
                // won't match their stored hash, so skip promotion and warn.
                if (!passwordEncoder.matches(password, admin.getPassword())) {
                    logger.warn("Bootstrap admin email {} already belongs to an existing account "
                            + "whose password does not match the configured bootstrap password; "
                            + "skipping promotion.", normalizedEmail);
                    return;
                }
                admin.setRole("ADMIN");
                admin.setVerifiedAccount(true);
                admin.setVerifyOtp(null);
                admin.setVerifyOtpExpireAt(0);
                userRepository.save(admin);
                return;
            }

            UserEntity admin = new UserEntity();
            admin.setName(name == null || name.isBlank() ? "Admin" : name.trim());
            admin.setEmail(normalizedEmail);
            admin.setPassword(passwordEncoder.encode(password));
            admin.setRole("ADMIN");
            admin.setVerifiedAccount(true);

            userRepository.save(admin);
        };
    }
}
