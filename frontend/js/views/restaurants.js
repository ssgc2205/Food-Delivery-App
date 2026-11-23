import { Restaurants, Orders } from "../api.js";
import { currentUser, isOwner, isAdmin, isCustomer } from "../auth.js";
import {
    render, esc, money, loading, empty, notice, toast, reportError, onClick, setBusy,
} from "../ui.js";
import { navigate } from "../router.js";

/* --------------------------------------------------------------------------
   Restaurant list
   -------------------------------------------------------------------------- */

export async function restaurantsView() {
    loading("Loading restaurants…");

    let list;
    try {
        list = await Restaurants.list();
    } catch (error) {
        return reportError(error, "Couldn't load restaurants");
    }

    const canCreate = isOwner() || isAdmin();
    const mine = currentUser()?.id;

    render(`
        <div class="page-head">
            <div>
                <h1>Restaurants</h1>
                <p class="subtitle">${list.length} place${list.length === 1 ? "" : "s"} to order from</p>
            </div>
            ${canCreate ? `<button id="new-restaurant">Add restaurant</button>` : ""}
        </div>

        ${canCreate ? `<div id="new-restaurant-form" hidden>${restaurantForm()}</div>` : ""}

        ${list.length === 0
            ? empty("🍽️", "No restaurants yet",
                canCreate ? "Add one to get started." : "Check back once an owner adds one.")
            : `<div class="grid">${list.map((r) => card(r, mine)).join("")}</div>`}
    `);

    onClick(".restaurant-card", (el) => navigate(`/restaurants/${el.dataset.id}`));

    const toggle = document.getElementById("new-restaurant");
    if (toggle) {
        toggle.addEventListener("click", () => {
            const panel = document.getElementById("new-restaurant-form");
            panel.hidden = !panel.hidden;
            if (!panel.hidden) panel.querySelector("input")?.focus();
        });
        document.getElementById("restaurant-form")?.addEventListener("submit", createRestaurant);
    }
}

function card(restaurant, myAccountId) {
    const isMine = restaurant.ownerAccountId && restaurant.ownerAccountId === myAccountId;
    return `
        <div class="card restaurant-card" data-id="${restaurant.id}">
            <div class="row-between" style="margin-bottom:6px">
                <h2 style="margin:0">${esc(restaurant.name)}</h2>
                ${isMine ? `<span class="badge accent">Yours</span>` : ""}
            </div>
            <div class="muted" style="font-size:13.5px">
                ${esc(restaurant.cuisine || "Cuisine not set")}
            </div>
            <div class="muted" style="font-size:13px; margin-top:8px">
                📍 ${esc(restaurant.address || "No address")}
            </div>
        </div>`;
}

function restaurantForm() {
    return `
        <div class="card" style="margin-bottom:18px">
            <h2>New restaurant</h2>
            <form id="restaurant-form" class="form-narrow">
                <div class="field">
                    <label for="r-name">Name</label>
                    <input id="r-name" name="name" required placeholder="Curry House">
                </div>
                <div class="field">
                    <label for="r-cuisine">Cuisine</label>
                    <input id="r-cuisine" name="cuisine" placeholder="Indian">
                </div>
                <div class="field">
                    <label for="r-address">Address</label>
                    <input id="r-address" name="address" placeholder="12 Spice Rd">
                </div>
                <button type="submit">Create</button>
            </form>
        </div>`;
}

async function createRestaurant(event) {
    event.preventDefault();
    const form = event.target;
    const button = form.querySelector("button[type=submit]");
    const data = new FormData(form);

    setBusy(button, true, "Creating…");
    try {
        await Restaurants.create({
            name: data.get("name"),
            cuisine: data.get("cuisine"),
            address: data.get("address"),
        });
        // The server records ownership from the token — the browser never sends an owner id.
        toast("Restaurant created. You're the owner.", { kind: "ok" });
        restaurantsView();
    } catch (error) {
        setBusy(button, false);
        reportError(error, "Couldn't create restaurant");
    }
}

/* --------------------------------------------------------------------------
   One restaurant: menu + cart
   -------------------------------------------------------------------------- */

const cart = new Map(); // name -> { name, price, quantity }

export async function restaurantView({ params }) {
    const id = Number(params.id);
    cart.clear();
    loading("Loading menu…");

    let restaurant, menu;
    try {
        [restaurant, menu] = await Promise.all([Restaurants.get(id), Restaurants.menu(id)]);
    } catch (error) {
        return reportError(error, "Couldn't load the restaurant");
    }

    const mine = restaurant.ownerAccountId && restaurant.ownerAccountId === currentUser()?.id;
    const canEditMenu = mine || isAdmin();

    render(`
        <div class="page-head">
            <div>
                <button class="ghost" id="back" style="margin-bottom:6px">← Restaurants</button>
                <h1>${esc(restaurant.name)}</h1>
                <p class="subtitle">
                    ${esc(restaurant.cuisine || "—")} · ${esc(restaurant.address || "No address")}
                </p>
            </div>
            ${mine ? `<span class="badge accent">You own this</span>` : ""}
        </div>

        <div class="split">
            <div class="card">
                <div class="row-between" style="margin-bottom:4px">
                    <h2 style="margin:0">Menu</h2>
                    ${canEditMenu ? `<button class="secondary small" id="add-item">Add item</button>` : ""}
                </div>

                ${canEditMenu ? `<div id="item-form" hidden style="padding-top:12px">${menuItemForm()}</div>` : ""}

                <div id="menu-list">
                    ${menu.length === 0
                        ? empty("📋", "Nothing on the menu yet",
                            canEditMenu ? "Add the first dish." : "")
                        : menu.map(menuRow).join("")}
                </div>
            </div>

            <div class="card cart">
                <h2>Your order</h2>
                <div id="cart-body">${cartBody()}</div>
            </div>
        </div>
    `);

    document.getElementById("back").addEventListener("click", () => navigate("/restaurants"));

    const addToggle = document.getElementById("add-item");
    if (addToggle) {
        addToggle.addEventListener("click", () => {
            const panel = document.getElementById("item-form");
            panel.hidden = !panel.hidden;
            if (!panel.hidden) panel.querySelector("input")?.focus();
        });
        document.getElementById("menu-item-form")?.addEventListener("submit", (e) => addMenuItem(e, id));
    }

    bindMenuButtons(id, restaurant);
}

function menuRow(item) {
    return `
        <div class="menu-item">
            <div>
                <div style="font-weight:550">${esc(item.name)}</div>
                <div class="muted" style="font-size:13px">₹${money(item.price)}</div>
            </div>
            <button class="secondary small" data-add="${esc(item.name)}" data-price="${esc(item.price)}">
                Add
            </button>
        </div>`;
}

function menuItemForm() {
    return `
        <form id="menu-item-form" style="border-bottom:1px solid var(--border); padding-bottom:14px">
            <div class="row wrap" style="align-items:flex-end">
                <div class="field grow" style="margin-bottom:0">
                    <label for="m-name">Dish</label>
                    <input id="m-name" name="name" required placeholder="Butter Chicken">
                </div>
                <div class="field" style="margin-bottom:0; width:110px">
                    <label for="m-price">Price</label>
                    <input id="m-price" name="price" type="number" step="0.01" min="0.01" required placeholder="12.50">
                </div>
                <button type="submit">Add</button>
            </div>
        </form>`;
}

async function addMenuItem(event, restaurantId) {
    event.preventDefault();
    const form = event.target;
    const button = form.querySelector("button[type=submit]");
    const data = new FormData(form);

    setBusy(button, true, "Adding…");
    try {
        await Restaurants.addMenuItem(restaurantId, {
            name: data.get("name"),
            price: Number(data.get("price")),
        });
        toast("Menu item added", { kind: "ok" });
        restaurantView({ params: { id: restaurantId } });
    } catch (error) {
        setBusy(button, false);
        if (error.status === 403) {
            // The role alone isn't enough — restaurant-service also checks ownerAccountId.
            toast("You can only edit menus for restaurants you own.", { kind: "danger", title: "Not your restaurant" });
        } else {
            reportError(error, "Couldn't add the item");
        }
    }
}

function bindMenuButtons(restaurantId, restaurant) {
    onClick("[data-add]", (el) => {
        const name = el.dataset.add;
        const price = Number(el.dataset.price);
        const line = cart.get(name) ?? { name, price, quantity: 0 };
        line.quantity += 1;
        cart.set(name, line);
        refreshCart(restaurantId, restaurant);
    });
}

function cartBody() {
    if (cart.size === 0) {
        return `<div class="muted" style="font-size:13.5px; padding:8px 0">
                    Pick something from the menu to start an order.
                </div>`;
    }

    const lines = [...cart.values()];
    const total = lines.reduce((sum, l) => sum + l.price * l.quantity, 0);

    return `
        ${lines.map((line) => `
            <div class="cart-line">
                <div class="grow">
                    <div style="font-weight:550; font-size:13.5px">${esc(line.name)}</div>
                    <div class="muted" style="font-size:12.5px">₹${money(line.price)} each</div>
                </div>
                <div class="qty">
                    <button data-dec="${esc(line.name)}" aria-label="One fewer">−</button>
                    <span>${line.quantity}</span>
                    <button data-inc="${esc(line.name)}" aria-label="One more">+</button>
                </div>
            </div>`).join("")}

        <div class="cart-total">
            <span>Total</span>
            <span>₹${money(total)}</span>
        </div>

        <div class="field" style="margin-top:14px">
            <label for="address">Deliver to</label>
            <input id="address" name="address" placeholder="221B Baker St" value="${esc(defaultAddress())}">
        </div>

        ${isCustomer() || isAdmin()
            ? `<button id="place-order" style="width:100%">Place order</button>`
            : notice("warn", "Only customer accounts can place orders.")}
    `;
}

let cachedAddress = "";

function defaultAddress() {
    return cachedAddress;
}

function refreshCart(restaurantId, restaurant) {
    const host = document.getElementById("cart-body");
    const typed = document.getElementById("address")?.value;
    if (typed !== undefined) cachedAddress = typed;

    host.innerHTML = cartBody();

    host.querySelectorAll("[data-inc]").forEach((el) =>
        el.addEventListener("click", () => {
            cart.get(el.dataset.inc).quantity += 1;
            refreshCart(restaurantId, restaurant);
        }));

    host.querySelectorAll("[data-dec]").forEach((el) =>
        el.addEventListener("click", () => {
            const line = cart.get(el.dataset.dec);
            line.quantity -= 1;
            if (line.quantity <= 0) cart.delete(line.name);
            refreshCart(restaurantId, restaurant);
        }));

    document.getElementById("place-order")
        ?.addEventListener("click", (e) => placeOrder(e.target, restaurantId));
}

async function placeOrder(button, restaurantId) {
    const address = document.getElementById("address")?.value?.trim();
    if (!address) {
        toast("Add a delivery address first.", { kind: "danger" });
        return;
    }

    setBusy(button, true, "Placing…");
    try {
        // Note what is NOT in this payload: a user id. The server takes the customer from the
        // token, so there is nothing here for a caller to tamper with.
        const order = await Orders.place({
            restaurantId,
            deliveryAddress: address,
            items: [...cart.values()].map(({ name, quantity, price }) => ({ name, quantity, price })),
        });

        cart.clear();
        toast("Order placed — watch it move through payment and delivery.", { kind: "ok", title: `Order #${order.id}` });
        navigate(`/orders/${order.id}`);
    } catch (error) {
        setBusy(button, false);

        // A missing profile is the one failure with an obvious next step, so say so.
        if (error.status === 400 && /does not exist/i.test(error.message || "")) {
            toast("Create your profile first, then order.", { kind: "danger", title: "Profile needed" });
            navigate("/profile");
            return;
        }
        reportError(error, "Couldn't place the order");
    }
}
