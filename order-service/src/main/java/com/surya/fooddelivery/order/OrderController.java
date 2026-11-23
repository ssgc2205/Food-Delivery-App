package com.surya.fooddelivery.order;

import com.surya.fooddelivery.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;
    private final OrderRepository repository;

    public OrderController(OrderService service, OrderRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /**
     * Admins see everything; everyone else sees their own orders.
     *
     * <p>Written as one endpoint with a branch rather than {@code @PreAuthorize("hasRole('ADMIN')")},
     * because "list my orders" is a perfectly reasonable thing for a customer to do — it just has
     * to mean something narrower for them. The narrowing is a <em>query</em>, not a filter applied
     * after loading: rows the caller may not see never enter memory at all.
     */
    @GetMapping
    public List<FoodOrder> all() {
        if (CurrentUser.isAdmin()) {
            return repository.findAll();
        }
        return repository.findByUserId(requireAccountId());
    }

    /**
     * Deliberately answers 404, not 403, for somebody else's order.
     *
     * <p>403 would confirm that order 42 exists — enough to count a competitor's volume by walking
     * ids. If a caller may not see a resource, the honest answer is that, as far as they are
     * concerned, it is not there.
     */
    @GetMapping("/{id}")
    public FoodOrder byId(@PathVariable Long id) {
        FoodOrder order = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + id + " not found"));
        if (!CurrentUser.isSelfOrAdmin(order.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + id + " not found");
        }
        return order;
    }

    /**
     * Places an order <em>for the authenticated account</em>.
     *
     * <p>The customer id comes from the signed token, never from the request body. This endpoint
     * used to take {@code userId} as a field, which meant anyone could place an order billed to
     * any account by changing one number. A request body is attacker-controlled input; treating it
     * as identity is what broken access control usually looks like in practice.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public FoodOrder create(@Valid @RequestBody PlaceOrderRequest request) {
        List<OrderItem> items = request.items().stream()
                .map(i -> new OrderItem(i.name(), i.quantity(), i.price()))
                .toList();
        return service.placeOrder(requireAccountId(), request.restaurantId(), request.deliveryAddress(), items);
    }

    private Long requireAccountId() {
        return CurrentUser.id().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }

    public record PlaceOrderRequest(
            @NotNull Long restaurantId,
            String deliveryAddress,
            @NotEmpty List<@Valid ItemRequest> items
    ) {
    }

    public record ItemRequest(
            String name,
            @Positive int quantity,
            @NotNull @Positive BigDecimal price
    ) {
    }
}
