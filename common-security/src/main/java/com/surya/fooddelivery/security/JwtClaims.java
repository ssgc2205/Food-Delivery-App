package com.surya.fooddelivery.security;

/**
 * Names of the custom claims carried in our access tokens. Shared so the service that writes a
 * claim and the services that read it can never disagree about its spelling.
 */
public final class JwtClaims {

    /** Roles held by the principal, as bare names ({@code ["CUSTOMER"]}), no {@code ROLE_} prefix. */
    public static final String ROLES = "roles";

    /**
     * The account's numeric id. Services use this for ownership checks — "is this <em>your</em>
     * order?" — instead of trusting a user id sent in the request body.
     */
    public static final String USER_ID = "uid";

    private JwtClaims() {
    }
}
