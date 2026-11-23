package com.surya.fooddelivery.delivery;

import com.surya.fooddelivery.security.CurrentUser;
import com.surya.fooddelivery.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private final DeliveryService service;
    private final DeliveryRepository repository;

    public DeliveryController(DeliveryService service, DeliveryRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /** Drivers and admins see the whole board; a customer sees only deliveries of their orders. */
    @GetMapping
    public List<Delivery> all() {
        if (CurrentUser.isAdmin() || CurrentUser.hasRole(Role.DELIVERY_AGENT)) {
            return repository.findAll();
        }
        return repository.findByUserId(requireAccountId());
    }

    /** Same 404-rather-than-403 reasoning as order-service: no confirming ids you may not read. */
    @GetMapping("/{id}")
    public Delivery byId(@PathVariable Long id) {
        Delivery delivery = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery " + id + " not found"));
        boolean staff = CurrentUser.isAdmin() || CurrentUser.hasRole(Role.DELIVERY_AGENT);
        if (!staff && !CurrentUser.isSelfOrAdmin(delivery.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery " + id + " not found");
        }
        return delivery;
    }

    /**
     * Advance a delivery, e.g. to OUT_FOR_DELIVERY or DELIVERED; re-publishes a status event.
     *
     * <p>Restricted to drivers because this writes a fact about the physical world that customers
     * must not be able to assert: marking your own order DELIVERED is how you get a refund dispute
     * to go your way.
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('DELIVERY_AGENT', 'ADMIN')")
    public Delivery updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        return service.advance(id, request.status());
    }

    private Long requireAccountId() {
        return CurrentUser.id().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }

    public record UpdateStatusRequest(@NotBlank String status) {
    }
}
