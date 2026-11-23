package com.surya.fooddelivery.delivery;

import com.surya.fooddelivery.events.DeliveryStatusEvent;
import com.surya.fooddelivery.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final List<String> DRIVERS = List.of("Alex", "Priya", "Diego", "Mei", "Sam");

    private final DeliveryRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DeliveryService(DeliveryRepository repository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public Delivery assign(Long orderId, Long userId, String address) {
        Delivery delivery = repository.findByOrderId(orderId).orElseGet(Delivery::new);
        delivery.setOrderId(orderId);
        delivery.setUserId(userId);
        delivery.setAddress(address);
        delivery.setDriverName(DRIVERS.get(ThreadLocalRandom.current().nextInt(DRIVERS.size())));
        delivery.setStatus(DeliveryStatusEvent.ASSIGNED);
        Delivery saved = repository.save(delivery);
        publish(saved);
        log.info("Assigned driver {} to order {}", saved.getDriverName(), orderId);
        return saved;
    }

    @Transactional
    public Delivery advance(Long deliveryId, String status) {
        Delivery delivery = repository.findById(deliveryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery " + deliveryId + " not found"));
        delivery.setStatus(status);
        Delivery saved = repository.save(delivery);
        publish(saved);
        return saved;
    }

    private void publish(Delivery delivery) {
        DeliveryStatusEvent event = new DeliveryStatusEvent(
                delivery.getOrderId(),
                delivery.getId(),
                delivery.getUserId(),
                delivery.getDriverName(),
                delivery.getStatus());
        kafkaTemplate.send(Topics.DELIVERY_STATUS, String.valueOf(delivery.getOrderId()), event);
    }
}
