package com.surya.fooddelivery.order.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Invoked when restaurant-service is down or the circuit is open. Returns {@code null}
 * so the caller can choose to degrade gracefully instead of hard-failing the order.
 */
@Component
public class RestaurantClientFallback implements RestaurantClient {

    private static final Logger log = LoggerFactory.getLogger(RestaurantClientFallback.class);

    @Override
    public RestaurantSummary getRestaurant(Long id) {
        log.warn("restaurant-service unavailable (circuit open or error); skipping validation for restaurant {}", id);
        return null;
    }
}
