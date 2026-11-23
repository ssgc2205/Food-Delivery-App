package com.surya.fooddelivery.payment;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Payment records are financial data, so both endpoints are admin-only.
 *
 * <p>A customer-facing "my payments" view would be a reasonable feature, but it would need the
 * same ownership filtering order-service does — and {@code Payment} does not currently carry the
 * account id needed to do it correctly. Locking the endpoints down is the honest response to that;
 * shipping a half-checked read of other people's payment rows is not.
 */
@RestController
@RequestMapping("/api/payments")
@PreAuthorize("hasRole('ADMIN')")
public class PaymentController {

    private final PaymentRepository repository;

    public PaymentController(PaymentRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Payment> all() {
        return repository.findAll();
    }

    @GetMapping("/order/{orderId}")
    public List<Payment> byOrder(@PathVariable Long orderId) {
        return repository.findByOrderId(orderId);
    }
}
