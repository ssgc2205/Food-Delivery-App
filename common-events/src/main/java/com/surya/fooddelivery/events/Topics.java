package com.surya.fooddelivery.events;

/** Kafka topic names shared across services. */
public final class Topics {

    public static final String ORDER_PLACED = "order-placed";
    public static final String PAYMENT_PROCESSED = "payment-processed";
    public static final String DELIVERY_STATUS = "delivery-status";

    private Topics() {
    }
}
