/**
 * Session state: the token, and what the UI is allowed to infer from it.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * IMPORTANT, and the thing to say out loud in an interview:
 *
 * Every role check in this file is for LAYOUT ONLY. It decides which links to draw and which
 * buttons to grey out. It is not security, and it cannot be — anyone can open devtools and call
 * `setRoles(["ADMIN"])`, or simply send the request with curl and skip this code entirely.
 *
 * The real enforcement is server-side: the gateway checks roles per route, and each service
 * re-verifies the token and applies its own @PreAuthorize and ownership rules. If this file were
 * deleted, nothing would become insecure — the app would just show buttons that return 403.
 *
 * That is the correct relationship between a client and authorization: the client's job is to
 * avoid *offering* actions that will fail, not to prevent them.
 * ─────────────────────────────────────────────────────────────────────────────
 */

const TOKEN_KEY = "fd.token";

/**
 * localStorage is the pragmatic choice here and it has a real downside worth naming: any XSS on
 * this page can read the token. The alternative — an httpOnly cookie the browser attaches
 * automatically — is immune to that, but then the token rides along on every request including
 * cross-site ones, so it needs CSRF protection, which stateless bearer auth otherwise avoids.
 *
 * Neither is free. For a single-page app talking to a token API, localStorage plus a short token
 * lifetime is the common trade; a production system handling payments should use the cookie.
 */
export function getToken() {
    return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token) {
    localStorage.setItem(TOKEN_KEY, token);
}

export function clearSession() {
    localStorage.removeItem(TOKEN_KEY);
}

export function isLoggedIn() {
    const token = getToken();
    return Boolean(token) && !isExpired(token);
}

/**
 * Reads the claims out of a JWT without verifying anything.
 *
 * A JWT is three base64url segments: header.payload.signature. The middle one is plain JSON —
 * signed, not encrypted — so any holder can read it. That is by design, and it is why a token
 * must never contain a secret.
 *
 * Decoding here is safe precisely because we do nothing security-relevant with the result.
 */
export function decodeToken(token = getToken()) {
    if (!token) return null;
    try {
        const payload = token.split(".")[1];
        // base64url -> base64, then decode UTF-8 correctly (atob alone mangles non-ASCII).
        const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
        const json = decodeURIComponent(
            atob(base64)
                .split("")
                .map((c) => "%" + c.charCodeAt(0).toString(16).padStart(2, "0"))
                .join("")
        );
        return JSON.parse(json);
    } catch {
        return null;
    }
}

export function isExpired(token = getToken()) {
    const claims = decodeToken(token);
    if (!claims?.exp) return true;
    // exp is in seconds since the epoch; Date.now() is milliseconds.
    return claims.exp * 1000 <= Date.now();
}

export function currentUser() {
    const claims = decodeToken();
    if (!claims) return null;
    return {
        id: claims.uid,
        username: claims.sub,
        roles: claims.roles ?? [],
        expiresAt: claims.exp ? new Date(claims.exp * 1000) : null,
    };
}

export function roles() {
    return currentUser()?.roles ?? [];
}

export function hasRole(...wanted) {
    const held = roles();
    return wanted.some((role) => held.includes(role));
}

export const isAdmin = () => hasRole("ADMIN");
export const isCustomer = () => hasRole("CUSTOMER");
export const isOwner = () => hasRole("RESTAURANT_OWNER");
export const isDriver = () => hasRole("DELIVERY_AGENT");

export const ALL_ROLES = ["CUSTOMER", "RESTAURANT_OWNER", "DELIVERY_AGENT", "ADMIN"];

/** Human-readable role labels, so the UI never shows RESTAURANT_OWNER to a person. */
export const ROLE_LABELS = {
    CUSTOMER: "Customer",
    RESTAURANT_OWNER: "Restaurant owner",
    DELIVERY_AGENT: "Delivery driver",
    ADMIN: "Admin",
};
