package com.surya.fooddelivery.payment;

import com.surya.fooddelivery.events.OrderPlacedEvent;
import com.surya.fooddelivery.events.PaymentProcessedEvent;
import com.surya.fooddelivery.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PaymentListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentListener.class);

    private final PaymentRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentListener(PaymentRepository repository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = Topics.ORDER_PLACED, groupId = "payment-service")
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("Processing payment for order {} amount {}", event.orderId(), event.totalAmount());

        // Simulated gateway: charge succeeds when the amount is positive.
        boolean approved = event.totalAmount() != null && event.totalAmount().compareTo(BigDecimal.ZERO) > 0;
        String status = approved ? PaymentProcessedEvent.SUCCESS : PaymentProcessedEvent.FAILED;

        Payment payment = new Payment();
        payment.setOrderId(event.orderId());
        payment.setAmount(event.totalAmount());
        payment.setStatus(status);
        Payment saved = repository.save(payment);

        PaymentProcessedEvent result = new PaymentProcessedEvent(
                event.orderId(),
                saved.getId(),
                event.userId(),
                event.restaurantId(),
                event.totalAmount(),
                event.deliveryAddress(),
                status);
        kafkaTemplate.send(Topics.PAYMENT_PROCESSED, String.valueOf(event.orderId()), result);
        log.info("Payment {} for order {} -> {}", saved.getId(), event.orderId(), status);
    }
}
