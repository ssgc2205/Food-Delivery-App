import { Users } from "../api.js";
import { currentUser, ROLE_LABELS } from "../auth.js";
import { render, esc, loading, notice, toast, reportError, showFieldErrors, setBusy } from "../ui.js";

/**
 * The customer's delivery profile — name, email, address.
 *
 * Deliberately separate from the account in auth-service, which holds only credentials. The two
 * are linked by a single id: auth-service issues it, and this profile is stored under the same
 * number rather than generating its own. One id, one meaning, everywhere.
 */
export async function profileView() {
    loading("Loading profile…");

    const account = currentUser();
    let profile = null;

    try {
        profile = await Users.me();
    } catch (error) {
        // 404 simply means "signed up, but hasn't filled this in yet" — a normal state, not a fault.
        if (error.status !== 404) return reportError(error, "Couldn't load your profile");
    }

    render(`
        <div class="page-head">
            <div>
                <h1>Your profile</h1>
                <p class="subtitle">
                    ${profile ? "Where your orders get delivered." : "Add your details so orders can be delivered."}
                </p>
            </div>
        </div>

        <div class="split">
            <div class="card">
                ${profile ? "" : notice("info",
                    "You don't have a delivery profile yet. Create one before placing an order.")}

                <form id="profile-form" class="form-narrow">
                    <div class="field">
                        <label for="p-name">Full name</label>
                        <input id="p-name" name="name" required value="${esc(profile?.name ?? "")}"
                               ${profile ? "disabled" : ""} placeholder="Surya">
                    </div>
                    <div class="field">
                        <label for="p-email">Email</label>
                        <input id="p-email" name="email" type="email" required value="${esc(profile?.email ?? "")}"
                               ${profile ? "disabled" : ""} placeholder="surya@example.com">
                    </div>
                    <div class="field">
                        <label for="p-phone">Phone</label>
                        <input id="p-phone" name="phone" value="${esc(profile?.phone ?? "")}"
                               ${profile ? "disabled" : ""} placeholder="Optional">
                    </div>
                    <div class="field">
                        <label for="p-address">Delivery address</label>
                        <input id="p-address" name="address" value="${esc(profile?.address ?? "")}"
                               ${profile ? "disabled" : ""} placeholder="221B Baker St">
                    </div>

                    ${profile
                        ? `<p class="hint">Editing a profile isn't implemented — the service has no
                           update endpoint yet.</p>`
                        : `<button type="submit">Create profile</button>`}
                </form>
            </div>

            <div class="card">
                <h2>Account</h2>
                <div class="stack" style="font-size:14px">
                    <div>
                        <div class="muted" style="font-size:12.5px">Username</div>
                        ${esc(account?.username ?? "—")}
                    </div>
                    <div>
                        <div class="muted" style="font-size:12.5px">Account id</div>
                        <span class="mono">#${esc(account?.id ?? "—")}</span>
                    </div>
                    <div>
                        <div class="muted" style="font-size:12.5px">Roles</div>
                        <div class="row wrap" style="gap:5px; margin-top:3px">
                            ${(account?.roles ?? []).map((r) =>
                                `<span class="role-pill">${esc(ROLE_LABELS[r] ?? r)}</span>`).join("")}
                        </div>
                    </div>
                    <div>
                        <div class="muted" style="font-size:12.5px">Session expires</div>
                        ${esc(account?.expiresAt?.toLocaleTimeString() ?? "—")}
                    </div>
                </div>
                <p class="hint" style="margin-top:14px">
                    These come from the token itself, not from an API call — that's what "stateless"
                    means in practice.
                </p>
            </div>
        </div>
    `);

    document.getElementById("profile-form")?.addEventListener("submit", async (event) => {
        event.preventDefault();
        const form = event.target;
        const button = form.querySelector("button[type=submit]");
        if (!button) return;

        const data = new FormData(form);
        setBusy(button, true, "Saving…");
        form.querySelectorAll(".field-error").forEach((el) => el.remove());

        try {
            // No id in this payload: the server keys the profile off the token's account id.
            await Users.create({
                name: data.get("name"),
                email: data.get("email"),
                phone: data.get("phone") || null,
                address: data.get("address") || null,
            });
            toast("Profile created", { kind: "ok" });
            profileView();
        } catch (error) {
            setBusy(button, false);
            showFieldErrors(form, error);
            if (!Object.keys(error.fieldErrors ?? {}).length) {
                reportError(error, "Couldn't save your profile");
            }
        }
    });
}
