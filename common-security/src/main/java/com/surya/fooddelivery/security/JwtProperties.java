package com.surya.fooddelivery.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Signing/verification settings, bound from {@code security.jwt.*} and identical in every service.
 *
 * <p><b>Why a shared secret (HS256) rather than a key pair (RS256)?</b> With HMAC there is one
 * secret: auth-service signs with it and everyone else verifies with the same value, which keeps
 * local setup to a single environment variable. The cost is that any service holding the secret
 * could also <em>mint</em> tokens, so a compromise anywhere is a compromise everywhere. The
 * production answer is RS256 — auth-service keeps a private key and publishes a public JWKS
 * endpoint that the others fetch — and switching is a decoder change, not an architecture change.
 *
 * @param secret         HMAC signing key. HS256 requires at least 256 bits (32 ASCII characters).
 * @param issuer         the {@code iss} claim written by auth-service and required by verifiers.
 * @param accessTokenTtl how long an issued token stays valid.
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    security.jwt.secret is not set. Export JWT_SECRET (at least 32 characters) \
                    before starting this service — see TESTING.md.""");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " characters for HS256; got " + secret.length() + ".");
        }
        issuer = (issuer == null || issuer.isBlank()) ? "food-delivery-auth" : issuer;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofHours(2) : accessTokenTtl;
    }

    /** The key used to sign tokens (auth-service) and verify them (everyone else). */
    public SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
