package com.surya.fooddelivery.security;

/**
 * The roles a principal can hold. Lives in a shared module because every service has to agree
 * on the exact strings — a typo in one service's {@code @PreAuthorize} would silently grant or
 * deny access, and a shared enum turns that into a compile error instead.
 *
 * <p>Spring Security's {@code hasRole('X')} looks for the authority {@code ROLE_X}; the tokens
 * carry the bare name ({@code CUSTOMER}) and the {@code ROLE_} prefix is added during conversion.
 */
public enum Role {

    /** Places and views their own orders. The default for public self-registration. */
    CUSTOMER,

    /** Manages restaurants and menus. */
    RESTAURANT_OWNER,

    /** Advances deliveries they are assigned. */
    DELIVERY_AGENT,

    /** Full access, including other users' data and payment records. */
    ADMIN;

    /** The Spring Security authority string for this role. */
    public String authority() {
        return "ROLE_" + name();
    }
}
