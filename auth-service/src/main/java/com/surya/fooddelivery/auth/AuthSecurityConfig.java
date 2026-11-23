package com.surya.fooddelivery.auth;

import com.surya.fooddelivery.security.JwtProperties;
import com.surya.fooddelivery.security.servlet.SecurityErrorHandlers;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * auth-service is the one service that cannot use the shared default filter chain: if every
 * endpoint required a token, nobody could ever obtain one. Declaring a {@link SecurityFilterChain}
 * here backs the auto-configured default off (it is {@code @ConditionalOnMissingBean}) while the
 * decoder and role converter beans it provides are still used — so this service verifies tokens
 * exactly the way its callers do.
 */
@Configuration
public class AuthSecurityConfig {

    /**
     * Hashes passwords with bcrypt, storing the algorithm as a {@code {bcrypt}} prefix.
     *
     * <p>The prefix is the reason to use the delegating encoder rather than
     * {@code new BCryptPasswordEncoder()} directly: it records <em>how</em> each hash was made, so
     * the day bcrypt needs replacing, new passwords can use the new algorithm while existing users
     * still log in. Without the prefix, migrating means forcing a password reset on everyone.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Signs tokens with the same shared secret every other service verifies against. */
    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(properties.secretKey()));
    }

    @Bean
    public SecurityFilterChain authFilterChain(HttpSecurity http,
                                               JwtAuthenticationConverter converter,
                                               SecurityErrorHandlers errorHandlers) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // The two endpoints that exist precisely to be reachable without a token.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // Everything else — /me, account listing, role changes — needs one, and the
                        // @PreAuthorize annotations on the controller narrow it further from there.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .build();
    }
}
