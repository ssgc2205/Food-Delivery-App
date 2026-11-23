package com.surya.fooddelivery.order;

import com.surya.fooddelivery.events.OrderPlacedEvent;
import com.surya.fooddelivery.events.Topics;
import com.surya.fooddelivery.order.client.RestaurantClient;
import com.surya.fooddelivery.order.client.UserClient;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final UserClient userClient;
    private final RestaurantClient restaurantClient;

    public OrderService(OrderRepository repository,
                        KafkaTemplate<String, Object> kafkaTemplate,
                        UserClient userClient,
                        RestaurantClient restaurantClient) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.userClient = userClient;
        this.restaurantClient = restaurantClient;
    }

    @Transactional
    public FoodOrder placeOrder(Long userId, Long restaurantId, String deliveryAddress, List<OrderItem> items) {
        validateUser(userId);
        validateRestaurant(restaurantId);

        BigDecimal total = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        FoodOrder order = new FoodOrder();
        order.setUserId(userId);
        order.setRestaurantId(restaurantId);
        order.setDeliveryAddress(deliveryAddress);
        order.setItems(items);
        order.setTotalAmount(total);
        order.setStatus(OrderStatus.PLACED.name());
        FoodOrder saved = repository.save(order);

        OrderPlacedEvent event = new OrderPlacedEvent(
                saved.getId(), userId, restaurantId, total, deliveryAddress);
        kafkaTemplate.send(Topics.ORDER_PLACED, String.valueOf(saved.getId()), event);
        log.info("Placed order {} for user {} (total {}), published to {}",
                saved.getId(), userId, total, Topics.ORDER_PLACED);
        return saved;
    }

    /**
     * Rejects the order when user-service positively reports the user does not exist.
     *
     * <p>When the client falls back (service down / circuit open) it returns {@code null} and we
     * accept the order anyway: a dependency outage should degrade validation, not stop the
     * business from taking orders. The trade-off is deliberate — availability over strict
     * consistency, with the invalid case caught downstream.
     */
    private void validateUser(Long userId) {
        try {
            if (userClient.getUser(userId) == null) {
                log.warn("Skipping user validation for {} - user-service unavailable", userId);
            }
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User " + userId + " does not exist");
        }
    }

    /** Same policy as {@link #validateUser}: reject on a definitive 404, degrade on an outage. */
    private void validateRestaurant(Long restaurantId) {
        try {
            if (restaurantClient.getRestaurant(restaurantId) == null) {
                log.warn("Skipping restaurant validation for {} - restaurant-service unavailable", restaurantId);
            }
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Restaurant " + restaurantId + " does not exist");
        }
    }

    @Transactional
    public void updateStatus(Long orderId, String status) {
        repository.findById(orderId).ifPresentOrElse(order -> {
            order.setStatus(status);
            repository.save(order);
            log.info("Order {} status -> {}", orderId, status);
        }, () -> log.warn("Received status update for unknown order {}", orderId));
    }
}
