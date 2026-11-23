package com.surya.fooddelivery.notification;

import com.surya.fooddelivery.events.DeliveryStatusEvent;
import com.surya.fooddelivery.events.OrderPlacedEvent;
import com.surya.fooddelivery.events.PaymentProcessedEvent;
import com.surya.fooddelivery.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationStore store;

    public NotificationListener(NotificationStore store) {
        this.store = store;
    }

    @KafkaListener(topics = Topics.ORDER_PLACED, groupId = "notification-service")
    public void onOrderPlaced(OrderPlacedEvent event) {
        notify(event.userId(), event.orderId(),
                "Your order #" + event.orderId() + " has been placed. Total: " + event.totalAmount());
    }

    @KafkaListener(topics = Topics.PAYMENT_PROCESSED, groupId = "notification-service")
    public void onPayment(PaymentProcessedEvent event) {
        String message = PaymentProcessedEvent.SUCCESS.equals(event.status())
                ? "Payment received for order #" + event.orderId() + "."
                : "Payment failed for order #" + event.orderId() + ". Please try again.";
        notify(event.userId(), event.orderId(), message);
    }

    @KafkaListener(topics = Topics.DELIVERY_STATUS, groupId = "notification-service")
    public void onDelivery(DeliveryStatusEvent event) {
        notify(event.userId(), event.orderId(),
                "Order #" + event.orderId() + " is " + event.status() + " (driver: " + event.driverName() + ").");
    }

    private void notify(Long userId, Long orderId, String message) {
        store.add(userId, orderId, message);
        log.info("NOTIFY user {} | order {} | {}", userId, orderId, message);
    }
}
