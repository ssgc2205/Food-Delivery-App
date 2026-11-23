import { Deliveries } from "../api.js";
import { isDriver, isAdmin } from "../auth.js";
import { render, esc, empty, loading, toast, reportError, onClick, setBusy } from "../ui.js";

const FLOW = ["ASSIGNED", "OUT_FOR_DELIVERY", "DELIVERED"];

const LABELS = {
    ASSIGNED: "Assigned",
    OUT_FOR_DELIVERY: "On the way",
    DELIVERED: "Delivered",
};

function badge(status) {
    const kind = status === "DELIVERED" ? "ok" : status === "ASSIGNED" ? "warn" : "info";
    return `<span class="badge ${kind}">${esc(LABELS[status] || status)}</span>`;
}

export async function deliveriesView() {
    loading("Loading deliveries…");

    let deliveries;
    try {
        deliveries = await Deliveries.list();
    } catch (error) {
        return reportError(error, "Couldn't load deliveries");
    }

    deliveries.sort((a, b) => b.id - a.id);
    const canAdvance = isDriver() || isAdmin();

    render(`
        <div class="page-head">
            <div>
                <h1>${canAdvance ? "Delivery board" : "My deliveries"}</h1>
                <p class="subtitle">
                    ${canAdvance
                        ? "Every active delivery. Advancing one publishes a delivery-status event."
                        : "Deliveries for your own orders."}
                </p>
            </div>
        </div>

        ${deliveries.length === 0
            ? empty("🛵", "Nothing to deliver",
                "Deliveries appear once an order has been paid for.")
            : `<div class="card table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>#</th><th>Order</th><th>Driver</th><th>Address</th>
                            <th>Status</th>${canAdvance ? "<th></th>" : ""}
                        </tr>
                    </thead>
                    <tbody>
                        ${deliveries.map((d) => row(d, canAdvance)).join("")}
                    </tbody>
                </table>
               </div>`}
    `);

    if (canAdvance) {
        onClick("[data-advance]", (el) =>
            advance(el, Number(el.dataset.advance), el.dataset.next));
    }
}

function row(delivery, canAdvance) {
    const next = FLOW[FLOW.indexOf(delivery.status) + 1];
    return `
        <tr>
            <td class="mono">${delivery.id}</td>
            <td class="mono">#${esc(delivery.orderId)}</td>
            <td>${esc(delivery.driverName || "—")}</td>
            <td class="muted" style="font-size:13px">${esc(delivery.address || "—")}</td>
            <td>${badge(delivery.status)}</td>
            ${canAdvance
                ? `<td>${next
                    ? `<button class="secondary small" data-advance="${delivery.id}" data-next="${next}">
                         Mark ${esc(LABELS[next].toLowerCase())}
                       </button>`
                    : `<span class="muted" style="font-size:13px">Complete</span>`}</td>`
                : ""}
        </tr>`;
}

async function advance(button, id, next) {
    setBusy(button, true, "Updating…");
    try {
        await Deliveries.updateStatus(id, next);
        // This is not just a database write: delivery-service republishes a delivery-status
        // event, which order-service consumes to move the order's own status along.
        toast(`Delivery ${id} → ${LABELS[next]}. The order will follow in a moment.`, { kind: "ok" });
        deliveriesView();
    } catch (error) {
        setBusy(button, false);
        if (error.status === 403) {
            toast("Only delivery drivers can advance a delivery.", { kind: "danger", title: "Not permitted" });
        } else {
            reportError(error, "Couldn't update the delivery");
        }
    }
}
