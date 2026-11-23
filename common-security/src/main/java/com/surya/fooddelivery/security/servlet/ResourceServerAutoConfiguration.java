package com.surya.fooddelivery.security.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surya.fooddelivery.security.JwtClaims;
import com.surya.fooddelivery.security.JwtProperties;
import com.surya.fooddelivery.web.ApiError;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Turns every servlet service in this project into an OAuth2 resource server with one consistent
 * policy: no session, no login form, and a valid signed JWT required on every business endpoint.
 *
 * <p><b>Why each service verifies the token itself, when the gateway already did.</b> The gateway
 * is the front door, not the only door — anything able to reach a service's port directly (a
 * misconfigured network policy, another compromised container, a developer with a port-forward)
 * would otherwise bypass authorization completely. Verifying an HMAC signature costs microseconds,
 * and it means a service is safe to expose regardless of what sits in front of it. The gateway
 * does coarse route-level checks; services do fine-grained {@code @PreAuthorize} and ownership
 * checks, because only they know what "your own order" means.
 *
 * <p>A service needing different rules — auth-service, whose login endpoint must be public —
 * simply declares its own {@link SecurityFilterChain}; the {@link ConditionalOnMissingBean} below
 * then backs this default off.
 */
@AutoConfiguration(before = {SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HttpSecurity.class, ApiError.class})
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity
public class ResourceServerAutoConfiguration {

    /** Open by design: health checks (Docker probes these) and API documentation. */
    static final String[] PUBLIC_PATHS = {
            "/actuator/health/**", "/actuator/info",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Checks exp/nbf as well as the issuer, so a token minted by some other system is rejected
        // even if it happens to be signed with a matching key.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Maps the token's {@code roles} claim onto Spring Security authorities.
     *
     * <p>The default converter looks for OAuth2 {@code scope}/{@code scp} claims and produces
     * {@code SCOPE_*} authorities, which {@code hasRole(...)} would never match.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtClaims.ROLES);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityErrorHandlers securityErrorHandlers(ObjectMapper objectMapper) {
        return new SecurityErrorHandlers(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain resourceServerFilterChain(HttpSecurity http,
                                                         JwtAuthenticationConverter converter,
                                                         SecurityErrorHandlers errorHandlers) throws Exception {
        return http
                // No cookies and no server-side session, so there is no cross-site request forgery
                // to protect against: another site cannot make a browser attach our bearer token.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Default deny: a new endpoint is protected the moment it is written,
                        // rather than being public until someone remembers to secure it.
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
