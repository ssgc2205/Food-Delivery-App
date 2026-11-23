import { Orders } from "../api.js";
import { isAdmin } from "../auth.js";
import { render, esc, money, when, loading, empty, notice, reportError, onClick } from "../ui.js";
import { navigate } from "../router.js";

/**
 * The order lifecycle, in the order the backend advances it.
 * PAYMENT_FAILED is a terminal branch rather than a step, so it isn't on the timeline.
 */
const STAGES = ["PLACED", "PAID", "ASSIGNED", "OUT_FOR_DELIVERY", "DELIVERED"];
const STAGE_LABELS = {
    PLACED: "Placed",
    PAID: "Paid",
    ASSIGNED: "Driver assigned",
    OUT_FOR_DELIVERY: "On the way",
    DELIVERED: "Delivered",
};

function statusBadge(status) {
    const kind = status === "DELIVERED" ? "ok"
        : status === "PAYMENT_FAILED" ? "danger"
        : status === "PLACED" ? "warn"
        : "info";
    return `<span class="badge ${kind}">${esc(STAGE_LABELS[status] || status)}</span>`;
}

/* --------------------------------------------------------------------------
   Order list
   -------------------------------------------------------------------------- */

export async function ordersView() {
    loading("Loading orders…");

    let orders;
    try {
        orders = await Orders.list();
    } catch (error) {
        return reportError(error, "Couldn't load orders");
    }

    orders.sort((a, b) => b.id - a.id);

    render(`
        <div class="page-head">
            <div>
                <h1>${isAdmin() ? "All orders" : "My orders"}</h1>
                <p class="subtitle">
                    ${isAdmin()
                        ? "Admin view — every customer's orders."
                        : "The server only returns orders belonging to your account."}
                </p>
            </div>
        </div>

        ${orders.length === 0
            ? empty("🧾", "No orders yet", "Pick a restaurant and place one.")
            : `<div class="card table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>Order</th>
                            ${isAdmin() ? "<th>Customer</th>" : ""}
                            <th>Items</th>
                            <th>Total</th>
                            <th>Status</th>
                            <th></th>
                        </tr>
                    </thead>
                    <tbody>
                        ${orders.map((o) => `
                            <tr>
                                <td class="mono">#${o.id}</td>
                                ${isAdmin() ? `<td class="mono">${esc(o.userId)}</td>` : ""}
                                <td>${esc((o.items ?? []).map((i) => `${i.quantity}× ${i.name}`).join(", ")) || "—"}</td>
                                <td>₹${money(o.totalAmount)}</td>
                                <td>${statusBadge(o.status)}</td>
                                <td><button class="ghost small" data-open="${o.id}">Track →</button></td>
                            </tr>`).join("")}
                    </tbody>
                </table>
               </div>`}
    `);

    onClick("[data-open]", (el) => navigate(`/orders/${el.dataset.open}`));
}

/* --------------------------------------------------------------------------
   One order — with live polling
   -------------------------------------------------------------------------- */

/**
 * Polls the order while it is still moving.
 *
 * This is the screen that makes the event-driven backend visible. `POST /api/orders` returns as
 * soon as the row is saved, with status PLACED — payment, delivery assignment and the status
 * updates all happen afterwards, as Kafka events flow between four services. So the honest UI is
 * one that watches the status converge rather than pretending the work finished with the request.
 *
 * That convergence is *eventual consistency*, and polling is the simplest way to show it. A
 * production app would push the change over WebSockets or SSE instead of asking repeatedly.
 *
 * Returns a cleanup function; the router calls it when navigating away, otherwise the interval
 * keeps firing against a DOM that no longer exists.
 */
export async function orderView({ params }) {
    const id = Number(params.id);
    loading("Loading order…");

    let timer = null;
    let elapsed = 0;

    const draw = (order, polling) => {
        const reached = STAGES.indexOf(order.status);
        const failed = order.status === "PAYMENT_FAILED";

        render(`
            <div class="page-head">
                <div>
                    <button class="ghost" id="back" style="margin-bottom:6px">← Orders</button>
                    <h1>Order #${order.id}</h1>
                    <p class="subtitle">Placed ${when(order.createdAt) || "just now"}</p>
                </div>
                ${statusBadge(order.status)}
            </div>

            ${failed ? notice("danger", "Payment was declined for this order.") : ""}

            <div class="card" style="margin-bottom:18px">
                <div class="row-between">
                    <h2 style="margin:0">Progress</h2>
                    ${polling
                        ? `<span class="muted" style="font-size:12.5px">
                             <span class="spinner"></span> waiting for updates…
                           </span>`
                        : ""}
                </div>

                <div class="timeline">
                    ${STAGES.map((stage, i) => {
                        const done = !failed && i < reached;
                        const current = !failed && i === reached;
                        return `
                            <div class="timeline-step ${done ? "done" : ""} ${current ? "current" : ""}">
                                <div class="timeline-dot"></div>
                                <div class="timeline-label">${esc(STAGE_LABELS[stage])}</div>
                            </div>`;
                    }).join("")}
                </div>

                <p class="hint" style="margin-top:14px">
                    Each step is a separate service reacting to a Kafka event — order-service never
                    calls payment or delivery directly. That's why the status catches up a moment
                    after the order is placed rather than being final immediately.
                </p>
            </div>

            <div class="split">
                <div class="card">
                    <h2>Items</h2>
                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr><th>Item</th><th>Qty</th><th>Price</th><th>Line total</th></tr>
                            </thead>
                            <tbody>
                                ${(order.items ?? []).map((item) => `
                                    <tr>
                                        <td>${esc(item.name)}</td>
                                        <td>${esc(item.quantity)}</td>
                                        <td>₹${money(item.price)}</td>
                                        <td>₹${money(item.price * item.quantity)}</td>
                                    </tr>`).join("")}
                            </tbody>
                        </table>
                    </div>
                    <div class="cart-total"><span>Total</span><span>₹${money(order.totalAmount)}</span></div>
                </div>

                <div class="card">
                    <h2>Details</h2>
                    <div class="stack" style="font-size:14px">
                        <div><div class="muted" style="font-size:12.5px">Delivering to</div>
                             ${esc(order.deliveryAddress || "—")}</div>
                        <div><div class="muted" style="font-size:12.5px">Restaurant</div>
                             <span class="mono">#${esc(order.restaurantId)}</span></div>
                        <div><div class="muted" style="font-size:12.5px">Account</div>
                             <span class="mono">#${esc(order.userId)}</span></div>
                    </div>
                </div>
            </div>
        `);

        document.getElementById("back").addEventListener("click", () => navigate("/orders"));
    };

    let order;
    try {
        order = await Orders.get(id);
    } catch (error) {
        // 404 here can mean two different things, and the backend deliberately makes them
        // indistinguishable: the order doesn't exist, or it isn't yours. Not confirming which
        // is the point — see the ownership rules in OrderController.
        if (error.status === 404) {
            return render(empty("🔍", "Order not found",
                "It either doesn't exist or belongs to another account."));
        }
        return reportError(error, "Couldn't load the order");
    }

    const settled = (o) => o.status === "DELIVERED" || o.status === "PAYMENT_FAILED";
    draw(order, !settled(order));

    if (!settled(order)) {
        timer = setInterval(async () => {
            elapsed += 2;
            try {
                const latest = await Orders.get(id);
                const changed = latest.status !== order.status;
                order = latest;

                // Give up after a minute: if nothing has moved by then, a consumer is down and
                // hammering the endpoint forever helps nobody.
                const keepPolling = !settled(order) && elapsed < 60;
                if (changed || !keepPolling) draw(order, keepPolling);
                if (!keepPolling) clearInterval(timer);
            } catch {
                clearInterval(timer);
            }
        }, 2000);
    }

    return () => timer && clearInterval(timer);
}
