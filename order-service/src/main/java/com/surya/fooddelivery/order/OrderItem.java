package com.surya.fooddelivery.order;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

@Embeddable
public class OrderItem {

    private String name;
    private int quantity;
    private BigDecimal price;

    protected OrderItem() {
    }

    public OrderItem(String name, int quantity, BigDecimal price) {
        this.name = name;
        this.quantity = quantity;
        this.price = price;
    }

    public BigDecimal lineTotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    public String getName() {
        return name;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }
}
