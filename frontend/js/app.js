import { route, startRouter, navigate } from "./router.js";
import { API_BASE } from "./api.js";
import {
    isLoggedIn, currentUser, clearSession, isAdmin, isDriver, isCustomer, ROLE_LABELS,
} from "./auth.js";
import { esc, toast } from "./ui.js";

import { loginView } from "./views/login.js";
import { restaurantsView, restaurantView } from "./views/restaurants.js";
import { ordersView, orderView } from "./views/orders.js";
import { deliveriesView } from "./views/deliveries.js";
import { profileView } from "./views/profile.js";
import { adminView } from "./views/admin.js";

/* --------------------------------------------------------------------------
   Routes
   --------------------------------------------------------------------------
   The `roles` option here is a UX guard, not a security boundary: it stops a user landing on a
   page whose every request would 403. Deleting it would change nothing about what data anyone
   can actually reach — the gateway and each service decide that.
   -------------------------------------------------------------------------- */

route("/login", loginView, { public: true });
route("/", () => navigate(isLoggedIn() ? "/restaurants" : "/login"), { public: true });

route("/restaurants", restaurantsView);
route("/restaurants/:id", restaurantView);
route("/orders", ordersView);
route("/orders/:id", orderView);
route("/deliveries", deliveriesView);
route("/profile", profileView);
route("/admin", adminView, { roles: ["ADMIN"] });

/* --------------------------------------------------------------------------
   Chrome: navigation and account menu, both rebuilt whenever the session changes
   -------------------------------------------------------------------------- */

/**
 * The navigation is the clearest place the role model shows up: sign in as a driver and the app
 * is a delivery board; sign in as an owner and it's a menu editor. Same code, same server.
 */
function navLinks() {
    const links = [{ path: "/restaurants", label: "Restaurants" }];

    if (isCustomer() || isAdmin()) links.push({ path: "/orders", label: "Orders" });
    if (isCustomer() || isDriver() || isAdmin()) links.push({ path: "/deliveries", label: "Deliveries" });
    if (isAdmin()) links.push({ path: "/admin", label: "Admin" });

    links.push({ path: "/profile", label: "Profile" });
    return links;
}

function renderChrome() {
    const nav = document.getElementById("nav");
    const account = document.getElementById("account");
    const user = currentUser();

    if (!isLoggedIn()) {
        nav.innerHTML = "";
        account.innerHTML = "";
        return;
    }

    const here = window.location.hash.replace(/^#/, "");
    nav.innerHTML = navLinks()
        .map(({ path, label }) => {
            const active = here === path || here.startsWith(path + "/");
            return `<a href="#${path}" class="${active ? "active" : ""}">${esc(label)}</a>`;
        })
        .join("");

    account.innerHTML = `
        <div class="row" style="gap:8px">
            <div style="text-align:right; line-height:1.25">
                <div style="font-size:13.5px; font-weight:600">${esc(user.username)}</div>
                <div class="muted" style="font-size:11.5px">
                    ${esc(user.roles.map((r) => ROLE_LABELS[r] ?? r).join(", "))}
                </div>
            </div>
            <button class="ghost small" id="sign-out">Sign out</button>
        </div>`;

    document.getElementById("sign-out").addEventListener("click", () => {
        clearSession();
        // Nothing is invalidated server-side by this — the token stays valid until it expires.
        // "Signing out" of a stateless system means forgetting the token, not revoking it.
        toast("Signed out", { kind: "ok" });
        window.dispatchEvent(new CustomEvent("session-changed"));
        navigate("/login");
    });
}

/* --------------------------------------------------------------------------
   Session lifecycle
   -------------------------------------------------------------------------- */

// Any 401 from any call means the token is gone or expired; get out cleanly rather than
// leaving the user on a page that silently fails.
window.addEventListener("session-expired", () => {
    renderChrome();
    if (window.location.hash !== "#/login") {
        toast("Your session expired. Please sign in again.", { kind: "danger" });
        navigate("/login");
    }
});

window.addEventListener("session-changed", renderChrome);
window.addEventListener("route-changed", renderChrome);

/**
 * Expiry is checked on a timer as well as on failure, so an idle tab logs out on its own instead
 * of waiting for the user to click something and get a 401.
 */
setInterval(() => {
    if (localStorage.getItem("fd.token") && !isLoggedIn()) {
        clearSession();
        window.dispatchEvent(new CustomEvent("session-expired"));
    }
}, 30_000);

/* --------------------------------------------------------------------------
   Boot
   -------------------------------------------------------------------------- */

document.getElementById("api-target").textContent =
    API_BASE ? `API: ${API_BASE}` : "API: same origin (/api → gateway)";

renderChrome();
startRouter();
