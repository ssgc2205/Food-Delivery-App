# Testing Guide — Food Delivery App (local Postgres)

This guide walks you through running and testing the whole system end-to-end using your
**local Postgres** (managed with pgAdmin) for the databases and **Docker only for Kafka**.

```
 local Postgres (pgAdmin)        Docker                 IntelliJ / jars
 ┌──────────────────────┐   ┌──────────────┐   ┌────────────────────────────────┐
 │ authdb, userdb       │   │ Kafka :9092  │   │ discovery-server :8761         │
 │ restaurantdb, orderdb│◄──┤ Kafka UI:8090│◄──┤ api-gateway :8080              │
 │ paymentdb, deliverydb│   └──────────────┘   │ auth/user/restaurant/order/    │
 └──────────────────────┘                      │ payment/delivery/notification  │
                                               └───────────────┬────────────────┘
                                                               ▲
                                      web UI ./frontend/serve.sh│ :3000  (optional, Step 5b)
```

## Prerequisites

- Local **Postgres** running on `localhost:5432`, reachable in pgAdmin
- **Docker Desktop** running (only used for Kafka)
- **Java 21** (`java -version` should show 21) — you already have Temurin 21
- **IntelliJ IDEA** (bundles Maven), or built jars (see step 3)
- Nothing extra for the web UI — it has no build step and no dependencies

> **Just want to see it run?** `docker compose up --build` starts everything including the UI on
> http://localhost:3000. This guide is for the IDE workflow, where services run from IntelliJ
> against your own Postgres.

> **Postgres credentials.** The services default to user `postgres` / password `postgres`.
> If your local Postgres uses a different password (or user), you'll set
> `POSTGRES_USER` / `POSTGRES_PASSWORD` in step 4 — note them now.

---

## Step 1 — Create the databases (pgAdmin)

Each service owns its own database. Create all six at once:

1. Open **pgAdmin** and connect to your local server.
2. Open **Query Tool** (right-click the server → *Query Tool*).
3. Paste the contents of [`infra/postgres/init-databases.sql`](infra/postgres/init-databases.sql) and run it.

You should now see `authdb`, `userdb`, `restaurantdb`, `orderdb`, `paymentdb`, `deliverydb`
under *Databases*. You don't need to create any tables — each service creates its own tables on
startup (Hibernate `ddl-auto=update`).

---

## Step 2 — Start Kafka

From the project root:

```bash
docker compose -f docker-compose.infra.yml up -d
```

This starts a single-node Kafka broker (advertised to your host as `localhost:9092`) plus a
web UI. Verify:

- **Kafka UI:** http://localhost:8090 — you should see a `food-delivery` cluster.

Topics (`order-placed`, `payment-processed`, `delivery-status`) are auto-created the first time
a service publishes to them, so the topic list may be empty until you place an order.

---

## Step 3 — Build the jars (only needed for the `run-all.sh` path)

If you'll run services from **IntelliJ** (Step 4, Option A), **skip this** — IntelliJ compiles
for you.

To build jars for the script-based path, use IntelliJ's Maven tool window:

- Open the **Maven** panel (right edge) → **food-delivery-app (root)** → **Lifecycle** →
  double-click **package**.

That produces a runnable jar in each module's `target/` folder.

---

## Step 4 — Start the services

Discovery server must start first; everything else registers with it.

### Option A — Run from IntelliJ (simplest, no jars)

Run each service's `*Application` main class, **starting with discovery-server**:

1. `DiscoveryServerApplication` (wait ~10s)
2. Then the rest, in any order:
   `ApiGatewayApplication`, `AuthServiceApplication`, `UserServiceApplication`,
   `RestaurantServiceApplication`, `OrderServiceApplication`, `PaymentServiceApplication`,
   `DeliveryServiceApplication`, `NotificationServiceApplication`

**If your Postgres password isn't `postgres`:** in each DB-backed service's Run Configuration,
add environment variables `POSTGRES_USER` and `POSTGRES_PASSWORD` (Edit Configurations →
Environment variables). The DB-backed services are auth, user, restaurant, order, payment, delivery.
(A quick alternative: temporarily create a Postgres role `postgres`/`postgres`, or set your
`postgres` user's password to `postgres`.)

### Option B — One command (uses the built jars from Step 3)

```bash
# if your Postgres password differs from "postgres", export it first:
export POSTGRES_PASSWORD='your-password'
# export POSTGRES_USER='your-user'   # only if not "postgres"

./run-all.sh
```

Logs stream to `./logs/*.log`. Stop everything with `./stop-all.sh`.

---

## Step 5 — Confirm everything registered

Open the **Eureka dashboard:** http://localhost:8761

Under *Instances currently registered with Eureka* you should see:
`API-GATEWAY`, `AUTH-SERVICE`, `USER-SERVICE`, `RESTAURANT-SERVICE`, `ORDER-SERVICE`,
`PAYMENT-SERVICE`, `DELIVERY-SERVICE`, `NOTIFICATION-SERVICE`.

Give it 20–30s after startup for all of them to appear.

---

## Step 5b — Start the web UI (optional, but the fastest way to see it work)

```bash
./frontend/serve.sh          # http://localhost:3000
```

Sign in as `admin` / `admin12345`, or register a customer. Everything in Steps 6–8 below can be
done through the UI instead of `curl` — including watching an order advance
`PLACED → PAID → ASSIGNED → DELIVERED` live on the order screen as Kafka events flow between
services.

**Why a server rather than opening `index.html`?** The app uses ES modules, and browsers refuse to
load those over `file://`. Any HTTP server works; `serve.sh` uses the Python already on macOS.

**In this mode the browser calls the gateway on `:8080` directly**, which is cross-origin, so it
relies on the CORS settings in `api-gateway/src/main/resources/application.yml` (which already
allow `localhost:3000`). Under `docker compose up` nginx proxies `/api` instead and CORS never
comes into it.

The `curl` walkthrough below is still worth doing at least once — it shows the raw tokens and
status codes the UI hides.

---

## Step 6 — Get tokens

**Every endpoint except registration and login now requires a bearer token.** A call without one
gets `401`; a call with the wrong role gets `403`.

Different actions need different roles, so create one account per role. Registration returns a
token straight away, so no separate login is needed.

```bash
# A customer — places orders
CUSTOMER=$(curl -s -X POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"surya","password":"password123","roles":["CUSTOMER"]}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

# A restaurant owner — creates restaurants and menus
OWNER=$(curl -s -X POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"chef","password":"password123","roles":["RESTAURANT_OWNER"]}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

# A driver — advances deliveries
DRIVER=$(curl -s -X POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"dave","password":"password123","roles":["DELIVERY_AGENT"]}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

# The admin seeded at startup — sees payments, notifications, and every account
ADMIN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin12345"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

echo "${CUSTOMER:0:40}..."   # sanity check: should print the start of a JWT
```

Check who a token belongs to at any point:

```bash
curl -s localhost:8080/api/auth/me -H "Authorization: Bearer $CUSTOMER"
# -> {"id":2,"username":"surya","roles":["CUSTOMER"],"enabled":true,...}
```

> **Tokens are just base64.** Paste one into https://jwt.io (or run
> `echo $CUSTOMER | cut -d. -f2 | base64 -d`) to see the `sub`, `uid`, `roles`, and `exp` claims.
> Note what that means: a JWT is **signed, not encrypted** — anyone holding it can read the claims.
> The signature stops them *changing* the claims, which is the property that matters here.

---

## Step 7 — Exercise the full flow

All calls go through the **gateway on :8080**. Run these in order.

```bash
# 1) The customer creates their own profile.
#    There is no id field — it comes from the token's account id.
curl -s -X POST localhost:8080/api/users \
  -H "Authorization: Bearer $CUSTOMER" -H 'Content-Type: application/json' \
  -d '{"name":"Surya","email":"surya@example.com","address":"221B Baker St"}'
# -> {"id":2,"name":"Surya",...}   (id 2 because the seeded admin is account 1)

# 2) The OWNER creates a restaurant + a menu item.
#    Ownership is recorded from their token, so only they can edit this menu later.
curl -s -X POST localhost:8080/api/restaurants \
  -H "Authorization: Bearer $OWNER" -H 'Content-Type: application/json' \
  -d '{"name":"Curry House","cuisine":"Indian","address":"12 Spice Rd"}'
# -> {"id":1,"ownerAccountId":3,...}

curl -s -X POST localhost:8080/api/restaurants/1/menu \
  -H "Authorization: Bearer $OWNER" -H 'Content-Type: application/json' \
  -d '{"name":"Butter Chicken","price":12.50}'

# 3) The CUSTOMER places an order — this kicks off the event chain.
#    No userId in the body: the order belongs to whoever the token says you are.
curl -s -X POST localhost:8080/api/orders \
  -H "Authorization: Bearer $CUSTOMER" -H 'Content-Type: application/json' \
  -d '{"restaurantId":1,"deliveryAddress":"221B Baker St",
       "items":[{"name":"Butter Chicken","quantity":2,"price":12.50}]}'
# -> {"id":1,"userId":2,"status":"PLACED","totalAmount":25.0,...}
```

Behind the scenes, within a second or two:
`order-service` publishes **order-placed** → `payment-service` charges and publishes
**payment-processed** → `delivery-service` assigns a driver and publishes **delivery-status** →
`order-service` and `notification-service` react.

```bash
# 4) Watch the order status advance (PLACED -> PAID -> ASSIGNED)
curl -s localhost:8080/api/orders/1 -H "Authorization: Bearer $CUSTOMER"
# -> {"id":1,"status":"ASSIGNED",...}

# 5) Admin-only views: payments and notifications
curl -s localhost:8080/api/payments/order/1 -H "Authorization: Bearer $ADMIN"   # "SUCCESS"
curl -s localhost:8080/api/notifications    -H "Authorization: Bearer $ADMIN"   # 3 messages

# 6) The DRIVER advances the delivery (re-publishes delivery-status)
curl -s localhost:8080/api/deliveries -H "Authorization: Bearer $DRIVER"

curl -s -X PUT localhost:8080/api/deliveries/1/status \
  -H "Authorization: Bearer $DRIVER" -H 'Content-Type: application/json' \
  -d '{"status":"DELIVERED"}'

curl -s localhost:8080/api/orders/1 -H "Authorization: Bearer $CUSTOMER"   # now "DELIVERED"
```

---

## Step 8 — Prove the authorization actually works

This is the part worth doing carefully: security that has never been observed rejecting anything
is not known to work. Each command prints just the HTTP status.

```bash
code() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }

# --- Authentication ---
code localhost:8080/api/orders                                  # 401 — no token
code localhost:8080/api/orders -H "Authorization: Bearer not.a.real.token"   # 401 — bad token

# --- Roles ---
# The owner cannot place an order (needs CUSTOMER)
code -X POST localhost:8080/api/orders -H "Authorization: Bearer $OWNER" \
  -H 'Content-Type: application/json' \
  -d '{"restaurantId":1,"deliveryAddress":"x","items":[{"name":"a","quantity":1,"price":1}]}'
                                                                # 403

# The customer cannot read the payment ledger (admin only)
code localhost:8080/api/payments -H "Authorization: Bearer $CUSTOMER"         # 403

# The customer cannot mark their own delivery DELIVERED (needs DELIVERY_AGENT)
code -X PUT localhost:8080/api/deliveries/1/status -H "Authorization: Bearer $CUSTOMER" \
  -H 'Content-Type: application/json' -d '{"status":"DELIVERED"}'             # 403

# The customer cannot list every account (admin only)
code localhost:8080/api/users -H "Authorization: Bearer $CUSTOMER"            # 403

# --- Privilege escalation ---
# Nobody can sign up as an admin, however nicely they ask
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"sneaky","password":"password123","roles":["ADMIN"]}'
# -> 403 with "Roles [ADMIN] cannot be self-assigned..."

# --- Ownership (the interesting one) ---
# Register a second customer and have them try to read the first customer's order.
OTHER=$(curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"mallory","password":"password123","roles":["CUSTOMER"]}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

code localhost:8080/api/orders/1 -H "Authorization: Bearer $OTHER"           # 404, NOT 403
```

That last one is deliberate: `403` would confirm that order 1 exists, which is enough to count
someone's order volume by walking ids. To a caller who may not see it, it isn't there.

```bash
# And listing orders is scoped, not filtered — mallory sees an empty list, not a rejection
curl -s localhost:8080/api/orders -H "Authorization: Bearer $OTHER"          # -> []
curl -s localhost:8080/api/orders -H "Authorization: Bearer $CUSTOMER"       # -> their order
curl -s localhost:8080/api/orders -H "Authorization: Bearer $ADMIN"          # -> everything
```

> **Tip:** on macOS, pipe any response through `python3 -m json.tool` for pretty JSON, e.g.
> `curl -s localhost:8080/api/orders/1 -H "Authorization: Bearer $CUSTOMER" | python3 -m json.tool`.

---

## Step 9 — Verify the data in pgAdmin

Each service wrote to its own database. In pgAdmin's Query Tool, switch the database (top
toolbar) and run:

| Database       | Query                                   | Expect                          |
|----------------|-----------------------------------------|---------------------------------|
| `authdb`       | `SELECT * FROM auth_users;`             | admin, surya, chef, dave — passwords are bcrypt hashes, never plaintext |
| `authdb`       | `SELECT * FROM auth_user_roles;`        | one row per role held           |
| `userdb`       | `SELECT * FROM users;`                  | Surya, with `id` = their auth account id |
| `restaurantdb` | `SELECT * FROM restaurants;` / `menu_items;` | Curry House + Butter Chicken |
| `orderdb`      | `SELECT * FROM orders;`                 | order 1, status `DELIVERED`     |
| `orderdb`      | `SELECT * FROM order_items;`            | 2 × Butter Chicken              |
| `paymentdb`    | `SELECT * FROM payments;`               | order 1, `SUCCESS`              |
| `deliverydb`   | `SELECT * FROM deliveries;`             | order 1, a driver               |

This proves the database-per-service separation: no service reads another's tables — they
coordinate purely through Kafka events.

---

## Step 10 — Watch the events (optional)

Open **Kafka UI** at http://localhost:8090 → *Topics*. You'll see `order-placed`,
`payment-processed`, and `delivery-status`. Click a topic → *Messages* to inspect the JSON
payloads that flowed between services.

---

## Shutting down

```bash
./stop-all.sh                                   # if you used run-all.sh
docker compose -f docker-compose.infra.yml down # stop Kafka (add -v to wipe its data)
```

Your Postgres databases persist in your local Postgres. To reset them, drop and re-create with
the Step 1 script.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Service fails to start, `password authentication failed` | Your Postgres password isn't `postgres`. Set `POSTGRES_PASSWORD` (and `POSTGRES_USER`) — Step 4. |
| `database "userdb" does not exist` | Re-run the Step 1 SQL in pgAdmin. |
| `Connection refused` to `localhost:5432` | Local Postgres isn't running / not on port 5432. |
| Services can't reach Kafka | Ensure `docker compose -f docker-compose.infra.yml up -d` is running; check http://localhost:8090. |
| Gateway returns 503 for `/api/...` | The target service hasn't registered with Eureka yet — wait ~30s and check http://localhost:8761. |
| Order status stays `PLACED` | payment-service/delivery-service aren't running, or Kafka is down. Check their logs. |
| Port already in use | Another process holds 8080/8761/8081–8087 or 9092. Stop it or change the port in that service's `application.yml`. |
| **`401` on every call, even with a fresh token** | The services aren't all using the same `JWT_SECRET`. They default to the same dev value, so this usually means you exported `JWT_SECRET` for some processes but not others — export it in the shell you start *everything* from, or leave it unset everywhere. |
| **`401` after a couple of hours** | Tokens expire (`access-token-ttl: 2h`). Log in again. |
| **`403` where you expected success** | Right token, wrong role. Check with `curl localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"` — placing orders needs `CUSTOMER`, menus need `RESTAURANT_OWNER`, delivery status needs `DELIVERY_AGENT`, payments and notifications need `ADMIN`. |
| **`404` on an order you know exists** | It belongs to a different account. That's the intended answer — see Step 8. |
| Service fails at startup with *"security.jwt.secret must be at least 32 characters"* | Exactly what it says: HS256 needs a 256-bit key. Failing loudly at startup beats signing tokens with a weak key. |
| Can't log in as `admin` | The bootstrap admin is only seeded when `authdb` contains **no** admin account. If you changed `BOOTSTRAP_ADMIN_PASSWORD` after the first run, the old password still applies — drop `authdb`, re-create it from the Step 1 SQL, and restart auth-service. |
| Swagger UI can't reach a service's docs | Docs are served through explicit `/docs/<service-name>/v3/api-docs` routes now (the discovery locator is off on purpose — see the README). Check the service is registered in Eureka. |
| **UI loads but every call fails**, console says *blocked by CORS policy* | You're serving the UI from an origin the gateway doesn't allow. Use `./frontend/serve.sh` (port 3000), or add your origin to `app.cors.allowed-origins` in the gateway's `application.yml`. Doesn't apply under `docker compose`. |
| **UI is blank, console says** *Failed to load module script* / *CORS ... file://* | You opened `index.html` directly. Browsers won't load ES modules over `file://` — run `./frontend/serve.sh` instead. |
| UI shows *"Cannot reach the API"* | The gateway isn't running on :8080, or `frontend/env.js` points somewhere else. |
| UI still shows old roles after an admin changed them | Expected. Roles live in the token, and the existing one keeps its old claims until it expires — sign out and back in. This is the revocation trade-off of stateless auth. |
