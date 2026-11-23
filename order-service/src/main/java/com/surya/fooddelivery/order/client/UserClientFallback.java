package com.surya.fooddelivery.order.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Invoked when user-service is down or the circuit is open. Returning {@code null}
 * lets {@link com.surya.fooddelivery.order.OrderService} decide the degradation policy
 * rather than failing the request with an opaque connection error.
 */
@Component
public class UserClientFallback implements UserClient {

    private static final Logger log = LoggerFactory.getLogger(UserClientFallback.class);

    @Override
    public UserSummary getUser(Long id) {
        log.warn("user-service unavailable (circuit open or error); skipping validation for user {}", id);
        return null;
    }
}
