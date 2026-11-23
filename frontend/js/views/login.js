import { Auth } from "../api.js";
import { setToken, ALL_ROLES, ROLE_LABELS } from "../auth.js";
import { render, esc, toast, reportError, showFieldErrors, setBusy, onClick } from "../ui.js";
import { redirectAfterLogin } from "../router.js";

let mode = "login";

export async function loginView() {
    draw();
}

function draw() {
    const isLogin = mode === "login";

    render(`
        <div class="auth-page">
            <div class="card">
                <div class="tabs">
                    <button class="${isLogin ? "active" : ""}" data-mode="login">Sign in</button>
                    <button class="${isLogin ? "" : "active"}" data-mode="register">Create account</button>
                </div>

                <form id="auth-form">
                    <div class="field">
                        <label for="username">Username</label>
                        <input id="username" name="username" autocomplete="username" required
                               autofocus placeholder="surya">
                    </div>

                    <div class="field">
                        <label for="password">Password</label>
                        <input id="password" name="password" type="password" required
                               autocomplete="${isLogin ? "current-password" : "new-password"}"
                               placeholder="••••••••">
                        ${isLogin ? "" : `<div class="hint">At least 8 characters.</div>`}
                    </div>

                    ${isLogin ? "" : roleChooser()}

                    <button type="submit" style="width:100%">
                        ${isLogin ? "Sign in" : "Create account"}
                    </button>
                </form>

                ${isLogin ? demoAccounts() : ""}
            </div>
        </div>
    `);

    document.querySelectorAll("[data-mode]").forEach((tab) => {
        tab.addEventListener("click", () => {
            mode = tab.dataset.mode;
            draw();
        });
    });

    onClick("[data-demo]", (el) => {
        document.getElementById("username").value = el.dataset.demo;
        document.getElementById("password").value = el.dataset.password;
    });

    document.getElementById("auth-form").addEventListener("submit", submit);
}

/**
 * ADMIN is absent from this list on purpose, mirroring the server.
 *
 * Removing it from the UI is *not* what stops privilege escalation — AuthService rejects a
 * self-assigned ADMIN with a 403 regardless of what gets posted. This just avoids offering an
 * option that would be refused.
 */
function roleChooser() {
    const selectable = ALL_ROLES.filter((role) => role !== "ADMIN");
    return `
        <div class="field">
            <label>I am signing up as</label>
            <select name="role">
                ${selectable.map((role) => `<option value="${role}">${esc(ROLE_LABELS[role])}</option>`).join("")}
            </select>
            <div class="hint">
                Admin accounts can't be self-assigned — an existing admin has to grant that role.
            </div>
        </div>`;
}

function demoAccounts() {
    return `
        <div class="demo-accounts">
            <div class="muted" style="margin-bottom:6px">Seeded account:</div>
            <button data-demo="admin" data-password="admin12345">admin / admin12345</button>
        </div>`;
}

async function submit(event) {
    event.preventDefault();
    const form = event.target;
    const button = form.querySelector("button[type=submit]");
    const data = new FormData(form);

    setBusy(button, true, mode === "login" ? "Signing in…" : "Creating…");
    form.querySelectorAll(".field-error").forEach((el) => el.remove());

    try {
        const response = mode === "login"
            ? await Auth.login(data.get("username"), data.get("password"))
            : await Auth.register(data.get("username"), data.get("password"), [data.get("role")]);

        // Registration logs you straight in — auth-service returns a token either way.
        setToken(response.accessToken);
        toast(`Signed in as ${response.username}`, { kind: "ok", title: "Welcome" });
        window.dispatchEvent(new CustomEvent("session-changed"));
        redirectAfterLogin();
    } catch (error) {
        setBusy(button, false);
        showFieldErrors(form, error);

        if (error.status === 401) {
            toast("Incorrect username or password.", { kind: "danger", title: "Sign in failed" });
        } else if (error.status === 409) {
            toast("That username is taken.", { kind: "danger", title: "Try another" });
        } else if (!Object.keys(error.fieldErrors ?? {}).length) {
            reportError(error, mode === "login" ? "Sign in failed" : "Registration failed");
        }
    }
}
