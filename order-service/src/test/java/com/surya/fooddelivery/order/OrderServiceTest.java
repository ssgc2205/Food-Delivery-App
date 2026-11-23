package com.surya.fooddelivery.order;

import com.surya.fooddelivery.events.OrderPlacedEvent;
import com.surya.fooddelivery.events.Topics;
import com.surya.fooddelivery.order.client.RestaurantClient;
import com.surya.fooddelivery.order.client.UserClient;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository repository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private UserClient userClient;
    @Mock
    private RestaurantClient restaurantClient;

    @InjectMocks
    private OrderService orderService;

    private List<OrderItem> items;

    @BeforeEach
    void setUp() {
        // 2 x 12.50 + 1 x 5.00 = 30.00
        items = List.of(
                new OrderItem("Butter Chicken", 2, new BigDecimal("12.50")),
                new OrderItem("Naan", 1, new BigDecimal("5.00")));
    }

    private void stubDependenciesAvailable() {
        when(userClient.getUser(anyLong()))
                .thenReturn(new UserClient.UserSummary(1L, "Surya", "s@example.com", "221B"));
        when(restaurantClient.getRestaurant(anyLong()))
                .thenReturn(new RestaurantClient.RestaurantSummary(1L, "Curry House", "Indian", "12 Spice Rd", true));
    }

    private void returnSavedOrderWithId(long id) {
        when(repository.save(any(FoodOrder.class))).thenAnswer(invocation -> {
            FoodOrder order = invocation.getArgument(0);
            // Simulate the DB assigning a primary key.
            java.lang.reflect.Field idField = FoodOrder.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, id);
            return order;
        });
    }

    @Test
    @DisplayName("computes the order total from item quantity x price")
    void computesTotal() {
        stubDependenciesAvailable();
        returnSavedOrderWithId(1L);

        FoodOrder order = orderService.placeOrder(1L, 1L, "221B Baker St", items);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("30.00");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED.name());
    }

    @Test
    @DisplayName("publishes an OrderPlacedEvent keyed by order id")
    void publishesEvent() {
        stubDependenciesAvailable();
        returnSavedOrderWithId(42L);

        orderService.placeOrder(7L, 3L, "221B Baker St", items);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(Topics.ORDER_PLACED), eq("42"), payload.capture());

        assertThat(payload.getValue()).isInstanceOfSatisfying(OrderPlacedEvent.class, event -> {
            assertThat(event.orderId()).isEqualTo(42L);
            assertThat(event.userId()).isEqualTo(7L);
            assertThat(event.restaurantId()).isEqualTo(3L);
            assertThat(event.totalAmount()).isEqualByComparingTo("30.00");
        });
    }

    @Test
    @DisplayName("rejects the order when user-service confirms the user does not exist")
    void rejectsUnknownUser() {
        when(userClient.getUser(anyLong())).thenThrow(notFound());

        assertThatThrownBy(() -> orderService.placeOrder(99L, 1L, "221B", items))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("User 99 does not exist");

        // Nothing persisted and nothing published when validation fails.
        verify(repository, never()).save(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("rejects the order when restaurant-service confirms the restaurant does not exist")
    void rejectsUnknownRestaurant() {
        when(userClient.getUser(anyLong()))
                .thenReturn(new UserClient.UserSummary(1L, "Surya", "s@example.com", "221B"));
        when(restaurantClient.getRestaurant(anyLong())).thenThrow(notFound());

        assertThatThrownBy(() -> orderService.placeOrder(1L, 99L, "221B", items))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Restaurant 99 does not exist");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("still accepts the order when a dependency is down and the fallback returns null")
    void degradesGracefullyWhenDependencyDown() {
        // Both clients hit their circuit-breaker fallback, which returns null.
        when(userClient.getUser(anyLong())).thenReturn(null);
        when(restaurantClient.getRestaurant(anyLong())).thenReturn(null);
        returnSavedOrderWithId(5L);

        FoodOrder order = orderService.placeOrder(1L, 1L, "221B Baker St", items);

        assertThat(order.getId()).isEqualTo(5L);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("30.00");
        verify(kafkaTemplate).send(eq(Topics.ORDER_PLACED), eq("5"), any(OrderPlacedEvent.class));
    }

    private static FeignException.NotFound notFound() {
        Request request = Request.create(Request.HttpMethod.GET, "/api/users/99",
                Collections.emptyMap(), null, new RequestTemplate());
        return new FeignException.NotFound("not found", request,
                "not found".getBytes(StandardCharsets.UTF_8), Map.of());
    }
}
