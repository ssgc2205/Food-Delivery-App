package com.surya.fooddelivery.notification;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationStore store;

    public NotificationController(NotificationStore store) {
        this.store = store;
    }

    /**
     * Admin-only, because this returns every notification for every customer — order totals,
     * addresses and all. The per-customer version is the obvious next feature, and needs the store
     * to be queryable by account id rather than dumped whole.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<NotificationStore.Notification> all() {
        return store.all();
    }
}
