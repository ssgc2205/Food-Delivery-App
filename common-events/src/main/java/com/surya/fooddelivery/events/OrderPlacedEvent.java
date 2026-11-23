package com.surya.fooddelivery.events;

import java.math.BigDecimal;

/** Emitted by order-service when a customer places an order. */
public record OrderPlacedEvent(
        Long orderId,
        Long userId,
        Long restaurantId,
        BigDecimal totalAmount,
        String deliveryAddress
) {
}
