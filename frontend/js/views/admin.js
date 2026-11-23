import { Auth, Payments, Notifications } from "../api.js";
import { ALL_ROLES, ROLE_LABELS, currentUser } from "../auth.js";
import { render, esc, money, when, loading, empty, toast, reportError, onClick } from "../ui.js";

/**
 * The admin console. Every endpoint behind this page is ADMIN-only server-side — the tab simply
 * isn't drawn for other roles, but hiding it is convenience, not protection.
 */
export async function adminView() {
    loading("Loading admin data…");

    // Fetched together, and tolerant of any one failing: a payment-service outage shouldn't
    // blank the whole page.
    const [accounts, payments, notifications] = await Promise.all([
        Auth.listAccounts().catch((e) => e),
        Payments.list().catch((e) => e),
        Notifications.list().catch((e) => e),
    ]);

    render(`
        <div class="page-head">
            <div>
                <h1>Admin</h1>
                <p class="subtitle">Accounts, payments and notifications across every service.</p>
            </div>
        </div>

        <div class="stack" style="gap:18px">
            <div class="card">
                <h2>Accounts</h2>
                ${section(accounts, accountsTable)}
            </div>

            <div class="card">
                <h2>Payments</h2>
                <p class="hint" style="margin-top:-6px; margin-bottom:12px">
                    Written by payment-service when it consumes an order-placed event.
                </p>
                ${section(payments, paymentsTable)}
            </div>

            <div class="card">
                <h2>Notifications</h2>
                <p class="hint" style="margin-top:-6px; margin-bottom:12px">
                    notification-service listens to all three topics and records a message per event.
                </p>
                ${section(notifications, notificationsList)}
            </div>
        </div>
    `);

    bindRoleEditing();
}

/** Renders a fetched section, or an inline explanation if that one call failed. */
function section(result, renderer) {
    if (result instanceof Error) {
        return `<div class="notice danger">Couldn't load this section (${esc(result.status || "error")}).</div>`;
    }
    return renderer(result);
}

function accountsTable(accounts) {
    if (!accounts.length) return empty("👤", "No accounts");

    const me = currentUser()?.id;

    return `
        <div class="table-wrap">
            <table>
                <thead>
                    <tr><th>#</th><th>Username</th><th>Roles</th><th>Created</th><th></th></tr>
                </thead>
                <tbody>
                    ${accounts.map((a) => `
                        <tr>
                            <td class="mono">${a.id}</td>
                            <td>
                                ${esc(a.username)}
                                ${a.id === me ? `<span class="badge accent" style="margin-left:6px">you</span>` : ""}
                                ${a.enabled ? "" : `<span class="badge danger" style="margin-left:6px">disabled</span>`}
                            </td>
                            <td>
                                <div class="row wrap" style="gap:4px">
                                    ${(a.roles ?? []).map((r) =>
                                        `<span class="role-pill">${esc(ROLE_LABELS[r] ?? r)}</span>`).join("")}
                                </div>
                            </td>
                            <td class="muted" style="font-size:13px">${esc(when(a.createdAt))}</td>
                            <td>
                                <button class="ghost small" data-edit-roles="${a.id}"
                                        data-roles="${esc((a.roles ?? []).join(","))}">
                                    Edit roles
                                </button>
                            </td>
                        </tr>
                        <tr id="roles-row-${a.id}" hidden>
                            <td colspan="5" style="background:var(--surface-2)">
                                <form data-roles-form="${a.id}" class="row wrap" style="gap:14px">
                                    ${ALL_ROLES.map((role) => `
                                        <label style="display:flex; align-items:center; gap:6px; margin:0; font-weight:500">
                                            <input type="checkbox" name="role" value="${role}"
                                                   style="width:auto"
                                                   ${(a.roles ?? []).includes(role) ? "checked" : ""}>
                                            ${esc(ROLE_LABELS[role])}
                                        </label>`).join("")}
                                    <button type="submit" class="small">Save</button>
                                </form>
                            </td>
                        </tr>`).join("")}
                </tbody>
            </table>
        </div>`;
}

function bindRoleEditing() {
    onClick("[data-edit-roles]", (el) => {
        const row = document.getElementById(`roles-row-${el.dataset.editRoles}`);
        row.hidden = !row.hidden;
    });

    document.querySelectorAll("[data-roles-form]").forEach((form) => {
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const id = Number(form.dataset.rolesForm);
            const roles = [...form.querySelectorAll("input[name=role]:checked")].map((i) => i.value);

            if (!roles.length) {
                toast("An account must keep at least one role.", { kind: "danger" });
                return;
            }

            try {
                await Auth.setRoles(id, roles);
                toast(`Roles updated for account ${id}.`, { kind: "ok" });
                // The change only takes effect for that user on their NEXT login — their current
                // token still carries the old roles until it expires. That is the revocation
                // trade-off of stateless auth, visible in miniature.
                toast("They'll need to sign in again for it to take effect.", { kind: "info" });
                adminView();
            } catch (error) {
                reportError(error, "Couldn't update roles");
            }
        });
    });
}

function paymentsTable(payments) {
    if (!payments.length) return empty("💳", "No payments yet");

    return `
        <div class="table-wrap">
            <table>
                <thead><tr><th>#</th><th>Order</th><th>Amount</th><th>Status</th></tr></thead>
                <tbody>
                    ${payments.map((p) => `
                        <tr>
                            <td class="mono">${p.id}</td>
                            <td class="mono">#${esc(p.orderId)}</td>
                            <td>₹${money(p.amount)}</td>
                            <td>
                                <span class="badge ${p.status === "SUCCESS" ? "ok" : "danger"}">
                                    ${esc(p.status)}
                                </span>
                            </td>
                        </tr>`).join("")}
                </tbody>
            </table>
        </div>`;
}

function notificationsList(notifications) {
    if (!notifications.length) return empty("🔔", "No notifications yet");

    return `<div class="stack" style="gap:8px">
        ${[...notifications].reverse().map((n) => `
            <div class="row" style="gap:10px; padding:9px 0; border-bottom:1px solid var(--border)">
                <span>🔔</span>
                <div class="grow">
                    <div style="font-size:14px">${esc(n.message ?? n.text ?? JSON.stringify(n))}</div>
                    ${n.userId !== undefined
                        ? `<div class="muted" style="font-size:12.5px">account #${esc(n.userId)}</div>`
                        : ""}
                </div>
            </div>`).join("")}
    </div>`;
}
