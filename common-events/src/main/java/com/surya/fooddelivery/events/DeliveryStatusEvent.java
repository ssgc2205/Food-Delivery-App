package com.surya.fooddelivery.events;

/** Emitted by delivery-service as a delivery moves through its lifecycle. */
public record DeliveryStatusEvent(
        Long orderId,
        Long deliveryId,
        Long userId,
        String driverName,
        String status
) {
    public static final String ASSIGNED = "ASSIGNED";
    public static final String OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
    public static final String DELIVERED = "DELIVERED";
}
