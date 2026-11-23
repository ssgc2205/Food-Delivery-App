package com.surya.fooddelivery.gateway;

import com.surya.fooddelivery.security.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * The perimeter. Every request from outside enters here, so this is where authentication is
 * checked first and where coarse, route-shaped authorization lives.
 *
 * <p><b>What belongs here and what does not.</b> The gateway can see the HTTP method and the URL,
 * and nothing else — so it can answer "may a delivery driver call this route at all?" but not "is
 * this particular order yours?", which needs the row. Rules that depend only on the route go here,
 * where they are visible in one place and reject bad traffic before it costs a downstream service
 * anything. Rules that depend on data stay in the owning service. Both layers run; the gateway is
 * not a substitute for service-side checks, because a request that reaches a service by any other
 * path must still be authorized.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    /**
     * Reachable without a token: health checks, and the API documentation.
     *
     * <p>Leaving the OpenAPI documents public is a judgement call — it lists every endpoint, which
     * is a map for anyone probing the system. It stays open here because being able to open Swagger
     * UI and try the API is the point of a portfolio project; a real deployment would put these
     * behind the same authentication as everything else.
     */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**", "/actuator/info",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/docs/*/v3/api-docs/**"
    };

    /**
     * Allows a browser on another origin to call this API — needed only during development.
     *
     * <p>A browser refuses to let a page on {@code localhost:3000} read a response from
     * {@code localhost:8080} unless the server explicitly says it may. That is the same-origin
     * policy, and it is protecting the user, not the server: without it, any site you visited
     * could read your responses from any API you happen to be logged into.
     *
     * <p>In Docker this configuration is never exercised. nginx serves the frontend <em>and</em>
     * reverse-proxies {@code /api} to this gateway, so the browser only ever sees one origin and
     * there is no cross-origin request to permit. The list below exists for the workflow in
     * TESTING.md, where the services run in an IDE and the frontend is served separately.
     *
     * <p>Two deliberate details: the origins are enumerated rather than {@code "*"}, and
     * credentials are <em>not</em> allowed. We authenticate with an {@code Authorization} header
     * that JavaScript attaches by hand, not with cookies — so the browser never needs permission
     * to send credentials automatically, and turning that on would only widen what a hostile page
     * could attempt.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));   // cache the preflight, don't repeat it per request

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityWebFilterChain gatewaySecurityFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter,
            CorsConfigurationSource corsConfigurationSource,
            GatewaySecurityErrorHandlers errorHandlers) {

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()

                        // Anyone signed in may browse restaurants and menus; only an owner (or an
                        // admin) may change them.
                        .pathMatchers(HttpMethod.GET, "/api/restaurants/**").authenticated()
                        .pathMatchers("/api/restaurants/**")
                        .hasAnyRole(Role.RESTAURANT_OWNER.name(), Role.ADMIN.name())

                        // Placing an order is a customer action. Reads are left to order-service,
                        // which checks that the order actually belongs to the caller.
                        .pathMatchers(HttpMethod.POST, "/api/orders/**")
                        .hasAnyRole(Role.CUSTOMER.name(), Role.ADMIN.name())

                        // Drivers advance deliveries; nobody else does.
                        .pathMatchers(HttpMethod.PUT, "/api/deliveries/*/status")
                        .hasAnyRole(Role.DELIVERY_AGENT.name(), Role.ADMIN.name())

                        // Payment records and the account list are administrative data.
                        .pathMatchers("/api/payments/**").hasRole(Role.ADMIN.name())
                        .pathMatchers(HttpMethod.GET, "/api/users").hasRole(Role.ADMIN.name())

                        // Default deny. A route added to the gateway tomorrow requires a token
                        // today, without anyone having to remember to add it here.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .jwtDecoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .build();
    }
}
