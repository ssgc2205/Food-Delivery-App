import { isLoggedIn, hasRole } from "./auth.js";
import { render, esc } from "./ui.js";

/**
 * A hash router (`#/orders`) rather than the History API.
 *
 * With `history.pushState`, a refresh on /orders sends a real GET /orders to the web server, which
 * has no such file and returns 404 — fixable with a rewrite rule, but that's server config the
 * frontend would then depend on. Everything after `#` is never sent to the server, so deep links
 * and refreshes work with no configuration anywhere.
 */

const routes = [];
let currentCleanup = null;

/**
 * @param path    e.g. "/restaurants" or "/restaurants/:id"
 * @param view    async ({ params }) => void — renders, optionally returns a cleanup function
 * @param options { roles: [...] } to require one of those roles, or omit for any signed-in user
 */
export function route(path, view, options = {}) {
    const paramNames = [];
    const pattern = new RegExp(
        "^" + path.replace(/:([A-Za-z]+)/g, (_, name) => {
            paramNames.push(name);
            return "([^/]+)";
        }) + "$"
    );
    routes.push({ pattern, paramNames, view, options });
}

export function navigate(path) {
    window.location.hash = path;
}

function currentPath() {
    const hash = window.location.hash.replace(/^#/, "");
    return hash || "/";
}

async function resolve() {
    // Views that poll (order status) return a cleanup function; run it before rendering the next
    // one, or the timer keeps firing against a dead DOM for the rest of the session.
    if (currentCleanup) {
        currentCleanup();
        currentCleanup = null;
    }

    const path = currentPath();
    const match = routes
        .map((r) => ({ r, m: path.match(r.pattern) }))
        .find(({ m }) => m);

    if (!match) {
        render(`<div class="empty"><span class="empty-icon">🤔</span><strong>Page not found</strong>
                <div class="muted">${esc(path)}</div></div>`);
        return;
    }

    const { r, m } = match;
    const isPublic = r.options.public === true;

    if (!isPublic && !isLoggedIn()) {
        // Remember where they were headed so login can send them back.
        sessionStorage.setItem("fd.redirect", path);
        navigate("/login");
        return;
    }

    if (r.options.roles && !hasRole(...r.options.roles)) {
        render(`
            <div class="empty">
                <span class="empty-icon">🔒</span>
                <div><strong>Not available for your account</strong></div>
                <div class="muted" style="margin-top:4px">
                    This page needs the ${esc(r.options.roles.join(" or "))} role.
                </div>
            </div>`);
        return;
    }

    const params = {};
    r.paramNames.forEach((name, i) => (params[name] = m[i + 1]));

    window.scrollTo(0, 0);
    currentCleanup = (await r.view({ params })) || null;
    window.dispatchEvent(new CustomEvent("route-changed", { detail: { path } }));
}

export function startRouter() {
    window.addEventListener("hashchange", resolve);
    resolve();
}

export function redirectAfterLogin() {
    const target = sessionStorage.getItem("fd.redirect");
    sessionStorage.removeItem("fd.redirect");
    navigate(target || "/restaurants");
}
