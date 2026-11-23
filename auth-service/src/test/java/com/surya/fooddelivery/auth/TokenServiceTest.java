package com.surya.fooddelivery.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.surya.fooddelivery.security.JwtClaims;
import com.surya.fooddelivery.security.JwtProperties;
import com.surya.fooddelivery.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signs a token with auth-service's encoder and verifies it with the very decoder the other
 * services use.
 *
 * <p>This is the contract the whole system rests on, and it spans two modules: if auth-service
 * writes {@code roles} and the resource-server config reads {@code authorities}, every service
 * would authenticate users and then find them holding no roles at all. Unit-testing each side
 * separately would not catch that; a round trip does.
 */
class TokenServiceTest {

    private static final String SECRET = "test-secret-that-is-definitely-long-enough-for-hs256";

    private final JwtProperties properties =
            new JwtProperties(SECRET, "food-delivery-auth", Duration.ofHours(2));
    private final TokenService tokenService =
            new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(properties.secretKey())), properties);

    private NimbusJwtDecoder decoder(String expectedIssuer, String secret) {
        JwtProperties props = new JwtProperties(secret, expectedIssuer, Duration.ofHours(2));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(props.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(expectedIssuer));
        return decoder;
    }

    private AuthUser user(Long id, String username, Role... roles) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setUsername(username);
        user.setRoles(EnumSet.copyOf(java.util.Set.of(roles)));
        return user;
    }

    @Test
    @DisplayName("a token issued here verifies against the shared decoder and carries id and roles")
    void roundTrips() {
        TokenService.IssuedToken issued =
                tokenService.issue(user(7L, "surya", Role.CUSTOMER, Role.RESTAURANT_OWNER));

        Jwt decoded = decoder("food-delivery-auth", SECRET).decode(issued.accessToken());

        assertThat(decoded.getSubject()).isEqualTo("surya");
        assertThat(decoded.<Number>getClaim(JwtClaims.USER_ID).longValue()).isEqualTo(7L);
        assertThat(decoded.getClaimAsStringList(JwtClaims.ROLES))
                .containsExactlyInAnyOrder("CUSTOMER", "RESTAURANT_OWNER");
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.expiresIn()).isEqualTo(Duration.ofHours(2).toSeconds());
    }

    @Test
    @DisplayName("the token is signed, so tampering with the payload invalidates it")
    void tamperedTokenIsRejected() {
        TokenService.IssuedToken issued = tokenService.issue(user(7L, "surya", Role.CUSTOMER));

        // Re-encode the payload claiming ADMIN, leaving the original signature in place — the
        // exact move a JWT's signature exists to defeat.
        String[] parts = issued.accessToken().split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String escalated = payload.replace("\"CUSTOMER\"", "\"ADMIN\"");
        String forged = parts[0] + "."
                + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(escalated.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> decoder("food-delivery-auth", SECRET).decode(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void wrongSecretIsRejected() {
        TokenService.IssuedToken issued = tokenService.issue(user(7L, "surya", Role.CUSTOMER));

        assertThatThrownBy(() ->
                decoder("food-delivery-auth", "a-completely-different-secret-also-long-enough")
                        .decode(issued.accessToken()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token from another issuer is rejected even when the signature checks out")
    void wrongIssuerIsRejected() {
        TokenService.IssuedToken issued = tokenService.issue(user(7L, "surya", Role.CUSTOMER));

        assertThatThrownBy(() -> decoder("some-other-system", SECRET).decode(issued.accessToken()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("stamps the configured TTL onto the token, bounding how long a stolen one is useful")
    void writesConfiguredExpiry() {
        TokenService.IssuedToken issued = tokenService.issue(user(7L, "surya", Role.CUSTOMER));

        Jwt decoded = decoder("food-delivery-auth", SECRET).decode(issued.accessToken());

        assertThat(decoded.getExpiresAt()).isNotNull();
        assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
                .isEqualTo(Duration.ofHours(2));
        // Expiry is not asserted by waiting one out: Nimbus allows 60 seconds of clock skew, so a
        // "just expired" token still decodes. The claim is the thing under this service's control.
    }

    @Test
    @DisplayName("a secret too short for HS256 fails fast at startup rather than weakening signatures")
    void rejectsWeakSecret() {
        assertThatThrownBy(() -> new JwtProperties("too-short", "food-delivery-auth", Duration.ofHours(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }
}
