package com.weatherplatform.authservice.bootstrap;

import com.weatherplatform.authservice.entity.User;
import com.weatherplatform.authservice.entity.UserRole;
import com.weatherplatform.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator, once, from the environment.
 *
 * Self-registration deliberately only ever grants USER, which left no way to
 * obtain an ADMIN account at all. Rather than hardcode a default credential —
 * which would ship a known password in the image — the bootstrap account is
 * supplied through the environment (in production, AWS Secrets Manager) and is
 * created only when the platform has no administrator yet.
 *
 * Running with no configuration is a valid, safe outcome: the service logs how
 * to provision one and starts normally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${auth.bootstrap-admin.email:}")
    private String bootstrapEmail;

    @Value("${auth.bootstrap-admin.password:}")
    private String bootstrapPassword;

    @Value("${auth.bootstrap-admin.name:Platform Administrator}")
    private String bootstrapName;

    /** Refuses anything a credential-stuffing list would already contain. */
    private static final int MIN_PASSWORD_LENGTH = 12;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(UserRole.ADMIN)) {
            log.debug("An administrator already exists; skipping bootstrap");
            return;
        }

        if (bootstrapEmail.isBlank() || bootstrapPassword.isBlank()) {
            log.warn("No administrator exists and AUTH_BOOTSTRAP_ADMIN_EMAIL / "
                    + "AUTH_BOOTSTRAP_ADMIN_PASSWORD are not set. The admin console cannot be "
                    + "accessed until one is provisioned. Set both and restart this service.");
            return;
        }

        if (bootstrapPassword.length() < MIN_PASSWORD_LENGTH) {
            log.error("Refusing to create the bootstrap administrator: the password is shorter "
                    + "than {} characters. Choose a stronger one and restart.", MIN_PASSWORD_LENGTH);
            return;
        }

        if (userRepository.existsByEmail(bootstrapEmail)) {
            log.warn("Bootstrap email is already registered as a non-admin account. "
                    + "Promote it through the user administration API instead.");
            return;
        }

        User admin = User.builder()
                .name(bootstrapName)
                .email(bootstrapEmail)
                .passwordHash(passwordEncoder.encode(bootstrapPassword))
                .role(UserRole.ADMIN)
                .build();

        userRepository.save(admin);

        // Log that it happened and who, never the credential itself.
        log.info("Bootstrap administrator created for {}. Change this password after first sign-in.",
                bootstrapEmail);
    }
}
