package com.surya.fooddelivery.restaurant;

import com.surya.fooddelivery.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/restaurants")
public class RestaurantController {

    private final RestaurantRepository restaurants;
    private final MenuItemRepository menuItems;

    public RestaurantController(RestaurantRepository restaurants, MenuItemRepository menuItems) {
        this.restaurants = restaurants;
        this.menuItems = menuItems;
    }

    @GetMapping
    public List<Restaurant> all() {
        return restaurants.findAll();
    }

    @GetMapping("/{id}")
    public Restaurant byId(@PathVariable Long id) {
        return restaurants.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurant " + id + " not found"));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('RESTAURANT_OWNER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Restaurant create(@Valid @RequestBody CreateRestaurantRequest request) {
        Restaurant restaurant = new Restaurant();
        restaurant.setName(request.name());
        restaurant.setCuisine(request.cuisine());
        restaurant.setAddress(request.address());
        // Ownership is recorded from the token, so the creator is the owner by construction.
        restaurant.setOwnerAccountId(CurrentUser.id().orElse(null));
        return restaurants.save(restaurant);
    }

    @GetMapping("/{id}/menu")
    public List<MenuItem> menu(@PathVariable Long id) {
        return menuItems.findByRestaurantId(id);
    }

    /**
     * Adding to a menu needs the owner role <em>and</em> ownership of this particular restaurant —
     * the role check alone would let any owner edit a competitor's menu.
     */
    @PostMapping("/{id}/menu")
    @PreAuthorize("hasAnyRole('RESTAURANT_OWNER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuItem addMenuItem(@PathVariable Long id, @Valid @RequestBody CreateMenuItemRequest request) {
        Restaurant restaurant = restaurants.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurant " + id + " not found"));
        if (!CurrentUser.isSelfOrAdmin(restaurant.getOwnerAccountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this restaurant");
        }

        MenuItem item = new MenuItem();
        item.setRestaurantId(id);
        item.setName(request.name());
        item.setPrice(request.price());
        return menuItems.save(item);
    }

    public record CreateRestaurantRequest(
            @NotBlank String name,
            String cuisine,
            String address
    ) {
    }

    public record CreateMenuItemRequest(
            @NotBlank String name,
            @NotNull @Positive BigDecimal price
    ) {
    }
}
