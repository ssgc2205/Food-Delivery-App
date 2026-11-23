package com.surya.fooddelivery.notification;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Simple in-memory log of notifications. A real service would push email/SMS/push. */
@Component
public class NotificationStore {

    public record Notification(Instant at, Long userId, Long orderId, String message) {
    }

    private final List<Notification> notifications = new CopyOnWriteArrayList<>();

    public void add(Long userId, Long orderId, String message) {
        notifications.add(new Notification(Instant.now(), userId, orderId, message));
    }

    public List<Notification> all() {
        return List.copyOf(notifications);
    }
}
