package com.surya.fooddelivery.delivery;

import com.surya.fooddelivery.events.PaymentProcessedEvent;
import com.surya.fooddelivery.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryListener.class);

    private final DeliveryService service;

    public DeliveryListener(DeliveryService service) {
        this.service = service;
    }

    @KafkaListener(topics = Topics.PAYMENT_PROCESSED, groupId = "delivery-service")
    public void onPayment(PaymentProcessedEvent event) {
        if (!PaymentProcessedEvent.SUCCESS.equals(event.status())) {
            log.info("Skipping delivery for order {} - payment {}", event.orderId(), event.status());
            return;
        }
        service.assign(event.orderId(), event.userId(), event.deliveryAddress());
    }
}
