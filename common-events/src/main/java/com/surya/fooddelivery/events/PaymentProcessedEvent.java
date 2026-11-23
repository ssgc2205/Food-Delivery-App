package com.surya.fooddelivery.events;

import java.math.BigDecimal;

/** Emitted by payment-service after attempting to charge for an order. */
public record PaymentProcessedEvent(
        Long orderId,
        Long paymentId,
        Long userId,
        Long restaurantId,
        BigDecimal amount,
        String deliveryAddress,
        String status
) {
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
}
