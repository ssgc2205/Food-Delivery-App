package com.surya.fooddelivery.auth;

import com.surya.fooddelivery.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.Set;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * Roles a stranger is allowed to claim at the public registration endpoint.
     *
     * <p>{@link Role#ADMIN} is absent on purpose. Accepting a role name from an unauthenticated
     * request body and trusting it is privilege escalation in one line — the classic broken-access-
     * control bug. Admins are created only by another admin, or by the bootstrap seeder on an empty
     * database.
     *
     * <p>Letting anyone self-register as a restaurant owner or a driver is a demo convenience.
     * A real deployment would create those accounts through an onboarding flow with document
     * checks; this project keeps them open so the whole system can be exercised with curl.
     */
    private static final Set<Role> SELF_SERVICE_ROLES =
            EnumSet.of(Role.CUSTOMER, Role.RESTAURANT_OWNER, Role.DELIVERY_AGENT);

    private final AuthUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AuthUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthUser register(String username, String rawPassword, Set<Role> requestedRoles) {
        Set<Role> roles = requestedRoles == null || requestedRoles.isEmpty()
                ? EnumSet.of(Role.CUSTOMER)
                : EnumSet.copyOf(requestedRoles);

        Set<Role> forbidden = EnumSet.copyOf(roles);
        forbidden.removeAll(SELF_SERVICE_ROLES);
        if (!forbidden.isEmpty()) {
            log.warn("Rejected self-registration of '{}' requesting privileged roles {}", username, forbidden);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Roles " + forbidden + " cannot be self-assigned; an administrator must grant them.");
        }

        return create(username, rawPassword, roles);
    }

    /** Creates an account with any roles at all. Reachable only from admin-guarded callers. */
    @Transactional
    public AuthUser create(String username, String rawPassword, Set<Role> roles) {
        if (repository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username '" + username + "' is already taken");
        }

        AuthUser user = new AuthUser();
        user.setUsername(username);
        // Hash once, here. The raw password is never stored, logged, or returned.
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRoles(EnumSet.copyOf(roles));
        AuthUser saved = repository.save(user);

        log.info("Registered account '{}' (id {}) with roles {}", username, saved.getId(), roles);
        return saved;
    }

    /**
     * Verifies credentials, or fails with a deliberately uninformative 401.
     *
     * <p>Two details that look like over-engineering and are not:
     *
     * <ul>
     *   <li><b>Same message for "no such user" and "wrong password."</b> Distinguishing them turns
     *       the login endpoint into a username oracle: an attacker can harvest valid accounts
     *       before ever guessing a password.</li>
     *   <li><b>The hash comparison runs even when the user does not exist.</b> bcrypt is
     *       intentionally slow, so skipping it would make "unknown user" measurably faster to
     *       answer than "wrong password" and leak the same information through timing.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public AuthUser authenticate(String username, String rawPassword) {
        AuthUser user = repository.findByUsername(username).orElse(null);

        String hashToCheck = user != null ? user.getPasswordHash() : DUMMY_HASH;
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCheck);

        if (user == null || !passwordMatches) {
            log.info("Failed login attempt for '{}'", username);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been disabled");
        }
        return user;
    }

    @Transactional
    public AuthUser replaceRoles(Long userId, Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An account must keep at least one role; disable it instead of stripping them all.");
        }
        AuthUser user = repository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account " + userId + " not found"));
        user.setRoles(EnumSet.copyOf(roles));
        log.info("Roles for account {} changed to {}", userId, roles);
        return repository.save(user);
    }

    /**
     * A well-formed bcrypt hash used only to burn the same CPU time as a real comparison when the
     * username does not exist. Whether it happens to match is irrelevant — the {@code user == null}
     * check rejects the request regardless; the point is purely that the two paths take equally long.
     */
    private static final String DUMMY_HASH =
            "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
