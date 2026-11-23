package com.surya.fooddelivery.auth;

import com.surya.fooddelivery.security.JwtClaims;
import com.surya.fooddelivery.security.JwtProperties;
import com.surya.fooddelivery.security.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Mints the access tokens every other service trusts. This is the only class in the system that
 * signs anything.
 *
 * <p>The token is self-contained: it carries the account id and roles, signed, so a service can
 * authorize a request without calling auth-service at all. That is the whole point — auth-service
 * being down does not stop already-issued tokens from working, and there is no per-request network
 * hop on the hot path.
 *
 * <p>The cost of that design is revocation. A stateless token stays valid until it expires, so
 * disabling an account does not immediately kill its sessions. That is why the TTL is short
 * (2 hours by default) rather than indefinite: it bounds how long a stolen or stale token is
 * useful. A production system pairs this with refresh tokens and a revocation list.
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public TokenService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public IssuedToken issue(AuthUser user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject(user.getUsername())
                .claim(JwtClaims.USER_ID, user.getId())
                .claim(JwtClaims.ROLES, roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(value, "Bearer",
                properties.accessTokenTtl().toSeconds(), user.getUsername(), roles);
    }

    /**
     * The login response body.
     *
     * @param accessToken the signed JWT, sent back on later requests as {@code Authorization: Bearer <token>}
     * @param tokenType   always {@code Bearer}; included so clients can build the header generically
     * @param expiresIn   lifetime in seconds, so a client can refresh before it lapses
     */
    public record IssuedToken(String accessToken, String tokenType, long expiresIn,
                              String username, List<String> roles) {
    }
}
