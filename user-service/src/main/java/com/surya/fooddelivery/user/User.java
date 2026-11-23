package com.surya.fooddelivery.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A customer's profile: the delivery-relevant facts about a person, deliberately kept apart from
 * their credentials, which live in auth-service.
 */
@Entity
@Table(name = "users")
public class User {

    /**
     * The account id issued by auth-service — assigned, not generated.
     *
     * <p>Letting this table generate its own sequence was the original design and it was wrong:
     * a token says "you are account 7", an order row says "user 7", and if those two 7s come from
     * different sequences they refer to different people. Every ownership check in the system
     * would be comparing unrelated numbers, and would occasionally match by coincidence — the
     * worst possible failure mode for an authorization rule.
     *
     * <p>So identity has exactly one source: auth-service mints the id, and every other service
     * keys off it. The cost is that a profile can only be created for an account that already
     * exists, which is the correct constraint anyway.
     */
    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    private String address;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }
}
