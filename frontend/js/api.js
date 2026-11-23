import { getToken, clearSession } from "./auth.js";

/** Set by env.js: "" in Docker (same origin), "http://localhost:8080" when run from the IDE. */
export const API_BASE = window.__API_BASE__ ?? "";

/**
 * An error carrying the server's response, so callers can react to *why* a call failed
 * rather than just that it did.
 */
export class ApiError extends Error {
    constructor(status, body, path) {
        super(body?.message || `Request failed (${status})`);
        this.status = status;
        this.body = body;
        this.path = path;
        /** field name -> message, populated by the backend's ApiError on validation failures */
        this.fieldErrors = body?.fieldErrors ?? {};
    }
}

/**
 * The single place every request goes through.
 *
 * <p>Two things it guarantees, which is the reason for having it at all:
 * the bearer token is attached to every call (impossible to forget), and a 401 always
 * results in the stale session being cleared rather than the app limping on with a dead token.
 */
async function request(method, path, body) {
    const headers = {};
    const token = getToken();

    // The server does not care who the browser thinks you are; it verifies this signature.
    // Everything the UI does with roles is presentation — this header is the actual claim.
    if (token) headers["Authorization"] = `Bearer ${token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";

    let response;
    try {
        response = await fetch(`${API_BASE}${path}`, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch (networkError) {
        // fetch() rejects only for genuine network failures — service down, DNS, CORS refusal.
        // An HTTP 500 is a *successful* fetch, so it does not land here.
        throw new ApiError(0, {
            message: "Cannot reach the API. Is the gateway running on " + (API_BASE || "this origin") + "?",
        }, path);
    }

    if (response.status === 204) return null;

    let payload = null;
    const text = await response.text();
    if (text) {
        try {
            payload = JSON.parse(text);
        } catch {
            payload = { message: text };
        }
    }

    if (!response.ok) {
        // The token is missing, expired, or invalid. Anything the UI still shows is now a lie,
        // so drop the session and let the router send the user back to the login screen.
        if (response.status === 401) {
            clearSession();
            window.dispatchEvent(new CustomEvent("session-expired"));
        }
        throw new ApiError(response.status, payload, path);
    }

    return payload;
}

export const api = {
    get: (path) => request("GET", path),
    post: (path, body) => request("POST", path, body),
    put: (path, body) => request("PUT", path, body),
    del: (path) => request("DELETE", path),
};

/* --------------------------------------------------------------------------
   Endpoints, named after what they do rather than the URL they hit.
   Keeping them here means a route change is a one-line edit, not a search.
   -------------------------------------------------------------------------- */

export const Auth = {
    login: (username, password) => api.post("/api/auth/login", { username, password }),
    register: (username, password, roles) => api.post("/api/auth/register", { username, password, roles }),
    me: () => api.get("/api/auth/me"),
    listAccounts: () => api.get("/api/auth/users"),
    setRoles: (id, roles) => api.put(`/api/auth/users/${id}/roles`, { roles }),
};

export const Users = {
    me: () => api.get("/api/users/me"),
    list: () => api.get("/api/users"),
    create: (profile) => api.post("/api/users", profile),
};

export const Restaurants = {
    list: () => api.get("/api/restaurants"),
    get: (id) => api.get(`/api/restaurants/${id}`),
    create: (restaurant) => api.post("/api/restaurants", restaurant),
    menu: (id) => api.get(`/api/restaurants/${id}/menu`),
    addMenuItem: (id, item) => api.post(`/api/restaurants/${id}/menu`, item),
};

export const Orders = {
    list: () => api.get("/api/orders"),
    get: (id) => api.get(`/api/orders/${id}`),
    place: (order) => api.post("/api/orders", order),
};

export const Deliveries = {
    list: () => api.get("/api/deliveries"),
    updateStatus: (id, status) => api.put(`/api/deliveries/${id}/status`, { status }),
};

export const Payments = {
    list: () => api.get("/api/payments"),
};

export const Notifications = {
    list: () => api.get("/api/notifications"),
};
