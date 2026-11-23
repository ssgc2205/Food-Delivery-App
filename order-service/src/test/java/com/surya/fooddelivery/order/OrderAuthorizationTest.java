package com.surya.fooddelivery.order;

import com.surya.fooddelivery.security.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ownership rules on {@link OrderController}, tested directly.
 *
 * <p>These are exercised as plain method calls with a security context in place rather than through
 * MockMvc, because the rules being checked are the controller's own logic, not Spring's filter
 * chain. The role annotations are Spring's job and are verified where they are declared; "can
 * customer 7 read customer 8's order" is ours.
 */
@ExtendWith(MockitoExtension.class)
class OrderAuthorizationTest {

    @Mock
    private OrderService orderService;
    @Mock
    private OrderRepository repository;

    private OrderController controller() {
        return new OrderController(orderService, repository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(long accountId, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("account-" + accountId)
                .claim(JwtClaims.USER_ID, accountId)
                .claim(JwtClaims.ROLES, List.of(role))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private FoodOrder orderOwnedBy(long userId) {
        FoodOrder order = new FoodOrder();
        order.setUserId(userId);
        order.setRestaurantId(1L);
        order.setStatus(OrderStatus.PLACED.name());
        return order;
    }

    @Test
    @DisplayName("the customer id comes from the token, not from the request body")
    void placesOrderForTheAuthenticatedAccount() {
        authenticateAs(7L, "CUSTOMER");
        when(orderService.placeOrder(anyLong(), anyLong(), anyString(), anyList()))
                .thenReturn(orderOwnedBy(7L));

        controller().create(new OrderController.PlaceOrderRequest(
                1L, "221B Baker St",
                List.of(new OrderController.ItemRequest("Butter Chicken", 2, new BigDecimal("12.50")))));

        // 7 is the token's account id. There is no longer any way for the caller to name a
        // different one — the field simply does not exist on the request.
        verify(orderService).placeOrder(eq(7L), eq(1L), eq("221B Baker St"), anyList());
    }

    @Test
    @DisplayName("a customer reading someone else's order gets 404, not 403")
    void hidesOtherPeoplesOrders() {
        authenticateAs(7L, "CUSTOMER");
        when(repository.findById(42L)).thenReturn(Optional.of(orderOwnedBy(8L)));

        assertThatThrownBy(() -> controller().byId(42L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                // 403 would confirm order 42 exists; 404 gives an outsider nothing.
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a customer can read their own order")
    void allowsOwnOrder() {
        authenticateAs(7L, "CUSTOMER");
        when(repository.findById(42L)).thenReturn(Optional.of(orderOwnedBy(7L)));

        assertThat(controller().byId(42L).getUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("an admin can read anyone's order")
    void allowsAdminToReadAnyOrder() {
        authenticateAs(1L, "ADMIN");
        when(repository.findById(42L)).thenReturn(Optional.of(orderOwnedBy(8L)));

        assertThat(controller().byId(42L).getUserId()).isEqualTo(8L);
    }

    @Test
    @DisplayName("listing orders as a customer queries only their own rows")
    void listingIsScopedToTheCaller() {
        authenticateAs(7L, "CUSTOMER");
        when(repository.findByUserId(7L)).thenReturn(List.of(orderOwnedBy(7L)));

        assertThat(controller().all()).hasSize(1);

        // The scoping must happen in the query. If findAll() were called and filtered afterwards,
        // every other customer's order would have been loaded into memory first.
        verify(repository).findByUserId(7L);
        verify(repository, org.mockito.Mockito.never()).findAll();
    }

    @Test
    @DisplayName("listing orders as an admin returns everything")
    void adminSeesAllOrders() {
        authenticateAs(1L, "ADMIN");
        when(repository.findAll()).thenReturn(List.of(orderOwnedBy(7L), orderOwnedBy(8L)));

        assertThat(controller().all()).hasSize(2);
    }

    @Test
    @DisplayName("an unauthenticated call cannot reach the service layer")
    void anonymousCannotPlaceOrders() {
        // Belt and braces: the filter chain would already have rejected this, but the controller
        // must not fall back to some default account if it is ever reached without one.
        assertThatThrownBy(() -> controller().create(new OrderController.PlaceOrderRequest(
                1L, "221B Baker St",
                List.of(new OrderController.ItemRequest("Butter Chicken", 2, new BigDecimal("12.50"))))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(orderService, org.mockito.Mockito.never()).placeOrder(any(), any(), any(), any());
    }
}
