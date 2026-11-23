package com.surya.fooddelivery.payment;

import com.surya.fooddelivery.events.OrderPlacedEvent;
import com.surya.fooddelivery.events.PaymentProcessedEvent;
import com.surya.fooddelivery.events.Topics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentListenerTest {

    @Mock
    private PaymentRepository repository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private PaymentListener listener;

    private void returnSavedPaymentWithId(long id) {
        when(repository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            java.lang.reflect.Field idField = Payment.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(payment, id);
            return payment;
        });
    }

    @Test
    @DisplayName("approves a positive-amount order and publishes SUCCESS")
    void approvesValidPayment() {
        returnSavedPaymentWithId(10L);
        OrderPlacedEvent order = new OrderPlacedEvent(1L, 2L, 3L, new BigDecimal("25.00"), "221B");

        listener.onOrderPlaced(order);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(Topics.PAYMENT_PROCESSED), eq("1"), payload.capture());

        assertThat(payload.getValue()).isInstanceOfSatisfying(PaymentProcessedEvent.class, event -> {
            assertThat(event.status()).isEqualTo(PaymentProcessedEvent.SUCCESS);
            assertThat(event.orderId()).isEqualTo(1L);
            assertThat(event.paymentId()).isEqualTo(10L);
            // Address is carried through so delivery-service needs no extra lookup.
            assertThat(event.deliveryAddress()).isEqualTo("221B");
        });
    }

    @Test
    @DisplayName("declines a zero-amount order and publishes FAILED")
    void declinesZeroAmount() {
        returnSavedPaymentWithId(11L);
        OrderPlacedEvent order = new OrderPlacedEvent(2L, 2L, 3L, BigDecimal.ZERO, "221B");

        listener.onOrderPlaced(order);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(Topics.PAYMENT_PROCESSED), eq("2"), payload.capture());

        assertThat(payload.getValue()).isInstanceOfSatisfying(PaymentProcessedEvent.class,
                event -> assertThat(event.status()).isEqualTo(PaymentProcessedEvent.FAILED));
    }
}
