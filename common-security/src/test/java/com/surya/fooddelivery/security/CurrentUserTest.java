package com.surya.fooddelivery.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CurrentUser} is the input to every ownership check in the project, so its failure modes
 * matter more than its happy path — a bug that quietly returns "no id" would turn an ownership
 * check into an open door.
 */
class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(long accountId, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("surya")
                .claim(JwtClaims.USER_ID, accountId)
                .claim(JwtClaims.ROLES, List.of(roles))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_" + roles[0]))));
    }

    @Test
    @DisplayName("reads the account id and roles out of the verified token")
    void readsClaims() {
        authenticateAs(7L, "CUSTOMER");

        assertThat(CurrentUser.id()).contains(7L);
        assertThat(CurrentUser.username()).contains("surya");
        assertThat(CurrentUser.roles()).containsExactly("CUSTOMER");
        assertThat(CurrentUser.hasRole(Role.CUSTOMER)).isTrue();
        assertThat(CurrentUser.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("a customer owns their own records and nobody else's")
    void ownershipIsExact() {
        authenticateAs(7L, "CUSTOMER");

        assertThat(CurrentUser.isSelfOrAdmin(7L)).isTrue();
        assertThat(CurrentUser.isSelfOrAdmin(8L)).isFalse();
    }

    @Test
    @DisplayName("an admin passes the ownership check for anyone")
    void adminOverridesOwnership() {
        authenticateAs(1L, "ADMIN");

        assertThat(CurrentUser.isSelfOrAdmin(999L)).isTrue();
    }

    @Test
    @DisplayName("a null owner id never matches — an unowned record is not everyone's")
    void nullOwnerNeverMatches() {
        authenticateAs(7L, "CUSTOMER");

        assertThat(CurrentUser.isSelfOrAdmin(null)).isFalse();
    }

    @Test
    @DisplayName("an anonymous request has no identity and owns nothing")
    void anonymousOwnsNothing() {
        assertThat(CurrentUser.id()).isEmpty();
        assertThat(CurrentUser.roles()).isEmpty();
        assertThat(CurrentUser.isAdmin()).isFalse();
        assertThat(CurrentUser.isSelfOrAdmin(7L)).isFalse();
    }

    @Test
    @DisplayName("a non-JWT authentication is ignored rather than half-trusted")
    void nonJwtAuthenticationIsIgnored() {
        // Anything that is not a verified JWT (a leftover session, a test stub) must not be able
        // to assert an account id, since nothing has checked a signature over it.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("surya", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(CurrentUser.id()).isEmpty();
        assertThat(CurrentUser.isAdmin()).isFalse();
    }
}
