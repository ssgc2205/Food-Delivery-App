package com.surya.fooddelivery.auth;

import com.surya.fooddelivery.security.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long> {

    Optional<AuthUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Used at startup to decide whether the bootstrap admin still needs creating. */
    boolean existsByRolesContaining(Role role);
}
