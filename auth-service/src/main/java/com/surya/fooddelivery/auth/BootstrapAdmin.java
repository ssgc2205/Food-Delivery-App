package com.surya.fooddelivery.auth;

import com.surya.fooddelivery.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * Solves the chicken-and-egg problem: creating an admin requires being an admin, and a fresh
 * database has none. On an empty account store this seeds exactly one, then never runs again.
 *
 * <p>Guarded by {@code security.bootstrap-admin.enabled}, which should be false in any real
 * deployment — a hard-coded starting password is a well-known way for demo systems to get
 * compromised. It exists here so the project can be exercised end to end straight after
 * {@code docker compose up}.
 */
@Component
@ConditionalOnProperty(prefix = "security.bootstrap-admin", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "security.bootstrap-admin")
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AuthUserRepository repository;
    private final AuthService authService;

    private String username = "admin";
    private String password;

    public BootstrapAdmin(AuthUserRepository repository, AuthService authService) {
        this.repository = repository;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Keyed on "does any admin exist", not "does this username exist", so renaming the
        // bootstrap account does not silently create a second one.
        if (repository.existsByRolesContaining(Role.ADMIN)) {
            return;
        }
        if (password == null || password.isBlank()) {
            log.warn("Bootstrap admin is enabled but no password is set; skipping. "
                    + "Set security.bootstrap-admin.password (or BOOTSTRAP_ADMIN_PASSWORD).");
            return;
        }

        authService.create(username, password, EnumSet.of(Role.ADMIN));
        log.warn("Created bootstrap admin '{}'. Change this password and disable "
                + "security.bootstrap-admin.enabled outside local development.", username);
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
