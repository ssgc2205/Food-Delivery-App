package com.surya.fooddelivery.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<FoodOrder, Long> {

    /** Backs "my orders": the authorization rule pushed down into the WHERE clause. */
    List<FoodOrder> findByUserId(Long userId);
}
