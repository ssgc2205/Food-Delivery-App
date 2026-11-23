package com.surya.fooddelivery.delivery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByOrderId(Long orderId);

    /** Backs a customer's view of the deliveries for their own orders. */
    List<Delivery> findByUserId(Long userId);
}
