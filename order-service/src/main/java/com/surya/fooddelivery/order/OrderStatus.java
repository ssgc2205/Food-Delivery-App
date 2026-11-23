package com.surya.fooddelivery.order;

/**
 * Lifecycle of an order. Statuses after PLACED are driven by events from
 * payment-service and delivery-service rather than by direct calls.
 */
public enum OrderStatus {

    PLACED,
    PAID,
    PAYMENT_FAILED,
    ASSIGNED,
    OUT_FOR_DELIVERY,
    DELIVERED
}
