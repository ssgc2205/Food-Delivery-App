/** Small DOM helpers. Everything the views need and nothing they don't. */

/**
 * Escapes text for interpolation into HTML.
 *
 * Used on every value that came from the server. Restaurant names, usernames and addresses are
 * user-supplied, and dropping them into innerHTML unescaped is stored XSS — someone registers as
 * `<img src=x onerror=...>` and the script runs in every admin's browser. Since the token lives
 * in localStorage, that would hand over the session too.
 */
export function esc(value) {
    if (value === null || value === undefined) return "";
    return String(value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

export function money(amount) {
    const n = Number(amount ?? 0);
    return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function when(timestamp) {
    if (!timestamp) return "";
    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) return "";
    return date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/** Renders HTML into the main view area. */
export function render(html) {
    document.getElementById("view").innerHTML = html;
}

export function loading(message = "Loading…") {
    render(`<div class="loading"><span class="spinner"></span> ${esc(message)}</div>`);
}

export function empty(icon, title, body = "") {
    return `
        <div class="empty">
            <span class="empty-icon">${icon}</span>
            <div><strong>${esc(title)}</strong></div>
            ${body ? `<div class="muted" style="margin-top:4px">${esc(body)}</div>` : ""}
        </div>`;
}

export function notice(kind, message) {
    return `<div class="notice ${kind}">${esc(message)}</div>`;
}

/** Binds a click handler to every element matching a selector inside the view. */
export function onClick(selector, handler) {
    document.getElementById("view").querySelectorAll(selector).forEach((el) => {
        el.addEventListener("click", (event) => handler(el, event));
    });
}

export function onSubmit(selector, handler) {
    const form = document.querySelector(selector);
    if (!form) return;
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        handler(new FormData(form), form);
    });
}

let toastId = 0;

export function toast(message, { kind = "info", title = "" } = {}) {
    const host = document.getElementById("toasts");
    const el = document.createElement("div");
    el.className = `toast ${kind}`;
    el.id = `toast-${++toastId}`;
    el.innerHTML = `
        ${title ? `<div class="toast-title">${esc(title)}</div>` : ""}
        <div class="toast-body">${esc(message)}</div>`;
    host.appendChild(el);
    setTimeout(() => el.remove(), 4200);
}

/**
 * Turns a failed API call into something a human can act on.
 *
 * The status codes carry real meaning in this backend, so they're translated rather than dumped:
 * a 403 means "wrong role", and a 404 on someone else's record is deliberate rather than a bug —
 * see the ownership rules in order-service.
 */
export function reportError(error, context = "") {
    const status = error?.status;
    let message = error?.message || "Something went wrong.";

    if (status === 403) {
        message = "Your account doesn't have permission for that.";
    } else if (status === 401) {
        message = "Your session has expired. Please sign in again.";
    } else if (status === 409) {
        message = error.message || "That already exists.";
    } else if (status === 0) {
        message = error.message;
    }

    const fields = Object.entries(error?.fieldErrors ?? {});
    if (fields.length) {
        message = fields.map(([field, detail]) => `${field}: ${detail}`).join(", ");
    }

    toast(message, { kind: "danger", title: context || undefined });
}

/** Renders validation errors from the backend's ApiError next to the right inputs. */
export function showFieldErrors(form, error) {
    form.querySelectorAll(".field-error").forEach((el) => el.remove());
    Object.entries(error?.fieldErrors ?? {}).forEach(([field, message]) => {
        const input = form.querySelector(`[name="${field}"]`);
        if (!input) return;
        const hint = document.createElement("div");
        hint.className = "field-error";
        hint.textContent = message;
        input.insertAdjacentElement("afterend", hint);
    });
}

export function setBusy(button, busy, busyLabel = "Working…") {
    if (!button) return;
    if (busy) {
        button.dataset.label = button.textContent;
        button.disabled = true;
        button.innerHTML = `<span class="spinner"></span> ${esc(busyLabel)}`;
    } else {
        button.disabled = false;
        button.textContent = button.dataset.label || button.textContent;
    }
}
