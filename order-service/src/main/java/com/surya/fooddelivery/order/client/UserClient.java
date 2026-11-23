package com.surya.fooddelivery.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Synchronous, load-balanced call to user-service, resolved through Eureka.
 * Guarded by a circuit breaker — see {@link UserClientFallback}.
 */
@FeignClient(name = "user-service", fallback = UserClientFallback.class)
public interface UserClient {

    @GetMapping("/api/users/{id}")
    UserSummary getUser(@PathVariable("id") Long id);

    record UserSummary(Long id, String name, String email, String address) {
    }
}
