package com.surya.fooddelivery.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;

/**
 * Reads the caller's identity out of the verified JWT on the current request.
 *
 * <p>Everything here comes from a token whose signature Spring Security already checked, so it is
 * safe to authorize on. Values sent in the request body are not — which is exactly why
 * order-service compares {@code request.userId()} against {@link #id()} instead of believing it.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The authenticated account's id, or empty when the request is anonymous. */
    public static Optional<Long> id() {
        return jwt().map(jwt -> jwt.getClaim(JwtClaims.USER_ID))
                .filter(Number.class::isInstance)
                .map(claim -> ((Number) claim).longValue());
    }

    /** The authenticated username (the token's {@code sub}), or empty when anonymous. */
    public static Optional<String> username() {
        return jwt().map(Jwt::getSubject);
    }

    /** Roles held by the caller, as bare names. */
    public static List<String> roles() {
        return jwt()
                .map(jwt -> jwt.getClaimAsStringList(JwtClaims.ROLES))
                .orElseGet(List::of);
    }

    public static boolean hasRole(Role role) {
        return roles().contains(role.name());
    }

    public static boolean isAdmin() {
        return hasRole(Role.ADMIN);
    }

    /**
     * True when the caller is the owner of {@code ownerId}, or an admin acting on someone's behalf.
     * The single predicate behind every "you can only see your own data" rule in the project.
     */
    public static boolean isSelfOrAdmin(Long ownerId) {
        return isAdmin() || id().filter(id -> id.equals(ownerId)).isPresent();
    }

    private static Optional<Jwt> jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token
                ? Optional.of(token.getToken())
                : Optional.empty();
    }
}
