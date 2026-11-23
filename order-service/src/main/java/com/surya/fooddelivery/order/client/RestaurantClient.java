package com.surya.fooddelivery.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Synchronous, load-balanced call to restaurant-service, resolved through Eureka.
 * Guarded by a circuit breaker — see {@link RestaurantClientFallback}.
 */
@FeignClient(name = "restaurant-service", fallback = RestaurantClientFallback.class)
public interface RestaurantClient {

    @GetMapping("/api/restaurants/{id}")
    RestaurantSummary getRestaurant(@PathVariable("id") Long id);

    record RestaurantSummary(Long id, String name, String cuisine, String address, boolean open) {
    }
}
