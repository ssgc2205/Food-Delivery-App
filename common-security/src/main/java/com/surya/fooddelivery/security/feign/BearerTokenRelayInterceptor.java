package com.surya.fooddelivery.security.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Copies the caller's bearer token onto outbound Feign calls.
 *
 * <p>Without this, order-service's own request is authenticated but the calls it makes to
 * user-service and restaurant-service arrive with no {@code Authorization} header at all — and
 * since those services default-deny, every order would fail with a 401 from a dependency.
 *
 * <p>Relaying the end user's token (rather than giving order-service a service account) means
 * downstream services see <em>who</em> is really behind the call and can apply the same ownership
 * rules they would for a direct request. The trade-off is that order-service can act with the full
 * authority of whoever called it for the lifetime of that token; the stricter alternative is token
 * exchange, where order-service swaps the user's token for a narrowly-scoped one.
 */
public class BearerTokenRelayInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.getToken().getTokenValue());
        }
        // Anonymous calls (a Kafka listener triggering an outbound call, say) go out unauthenticated
        // and are rejected downstream — which is the correct outcome, not something to paper over.
    }
}
