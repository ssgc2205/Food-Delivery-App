package com.surya.fooddelivery.order;

import com.surya.fooddelivery.events.DeliveryStatusEvent;
import com.surya.fooddelivery.events.PaymentProcessedEvent;
import com.surya.fooddelivery.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Keeps the local order status in sync with downstream payment and delivery events. */
@Component
public class OrderEventsListener {

    private final OrderService service;

    public OrderEventsListener(OrderService service) {
        this.service = service;
    }

    @KafkaListener(topics = Topics.PAYMENT_PROCESSED, groupId = "order-service")
    public void onPayment(PaymentProcessedEvent event) {
        OrderStatus status = PaymentProcessedEvent.SUCCESS.equals(event.status())
                ? OrderStatus.PAID
                : OrderStatus.PAYMENT_FAILED;
        service.updateStatus(event.orderId(), status.name());
    }

    @KafkaListener(topics = Topics.DELIVERY_STATUS, groupId = "order-service")
    public void onDelivery(DeliveryStatusEvent event) {
        service.updateStatus(event.orderId(), event.status());
    }
}
