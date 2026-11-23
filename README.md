# Food Delivery Platform — Event-Driven Microservices

[![CI](https://github.com/USERNAME/food-delivery-microservices/actions/workflows/ci.yml/badge.svg)](https://github.com/USERNAME/food-delivery-microservices/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.8-black)](https://kafka.apache.org/)

A food-delivery platform built as **eight independently deployable Spring Boot services**, with a
web UI on top. Placing an order kicks off a chain of Kafka events across payment, delivery, and
notification services — no service blocks on another, and each owns its own PostgreSQL database.
Every request carries a signed JWT, and authorization is enforced twice: coarse role rules at the
gateway, fine-grained role and ownership rules inside each service.

**One command runs all of it:** `docker compose up --build` → UI on
[localhost:3000](http://localhost:3000).

> Replace `USERNAME` in the badge URL above after pushing to GitHub.

📖 **New to the stack?** [**EXPLAINED.md**](EXPLAINED.md) walks through every technology used here
— Kafka, Docker, circuit breakers, Spring Boot, JPA, service discovery — from first principles,
plus a line-by-line tour of every file.

---

## Architecture

```
                        ┌──────────────────────────┐
    browser ───────────►│    frontend (nginx)      │ :3000
                        │  static files + /api →   │   the only origin the browser sees,
                        └────────────┬─────────────┘   so there is no CORS to configure
                                     │
                        ┌────────────▼─────────────┐
    curl ──────────────►│       api-gateway        │ :8080
   (Bearer token)       │  JWT verified + route-   │
                        │  level role rules        │
                        └────────────┬─────────────┘
                                     │ load-balanced routing via Eureka
   ┌──────────────┬───────────────┬──┴────────────┬───────────────┬──────────────────┐
   ▼              ▼               ▼               ▼               ▼                  ▼
auth-service  user-service  restaurant-service  order-service  payment-service  delivery-service
   :8087         :8081           :8082             :8083           :8084            :8085
   issues           ▲              ▲                 │               ▲                 ▲
   tokens           └──── sync ────┘                 │               │                 │
                     (Feign + circuit                │ order-placed  │ payment-        │ delivery-
                      breaker; the caller's          ▼               │ processed       │ status
                      token is relayed)      ╔═══════════════════════╧═════════════════╧═══════╗
                                             ║              Apache Kafka                       ║
                                             ╚═════════════════════════╤═══════════════════════╝
                                                                       ▼
                                                             notification-service :8086

  discovery-server :8761 (Eureka)        PostgreSQL :5432 (6 databases, one per service)

  Every service independently verifies the JWT — the gateway is the front door, not the only door.
```

**Two communication styles, chosen per use case:**

| Need | Style | Why |
|---|---|---|
| Validate a user/restaurant exists before accepting an order | **Synchronous** (OpenFeign) | The answer is needed *now* to accept or reject |
| Order → payment → delivery → notifications | **Asynchronous** (Kafka) | Services stay decoupled; a downstream outage doesn't fail the order |

### The order lifecycle

1. `POST /api/orders` — order-service validates the user and restaurant (sync, circuit-breakered),
   saves the order as `PLACED`, and publishes **`order-placed`**.
2. **payment-service** consumes it, charges, publishes **`payment-processed`** (`SUCCESS`/`FAILED`).
3. **delivery-service** consumes a successful payment, assigns a driver, publishes **`delivery-status`**.
4. **order-service** consumes both and advances its own status: `PLACED → PAID → ASSIGNED → DELIVERED`.
5. **notification-service** consumes all three topics and generates customer notifications.

Because steps 2–5 are asynchronous, the `POST` returns as soon as the order is saved. Status
converges within a second or two — deliberate eventual consistency.

---

## Security

Authentication is **stateless JWT**. `auth-service` is the only component that signs anything;
everything else verifies. A token carries the account id and roles, so a service can authorize a
request without a network call — which also means auth-service being down doesn't invalidate
tokens already issued.

**Four roles**, and what each one may do:

| Role | Can |
|---|---|
| `CUSTOMER` | Place orders; read **their own** orders and deliveries |
| `RESTAURANT_OWNER` | Create restaurants; edit menus for **restaurants they own** |
| `DELIVERY_AGENT` | Advance delivery status; view the delivery board |
| `ADMIN` | Everything, including payment records, the account list, and role changes |

**Authorization runs at two layers, and both matter.**

The gateway sees only a method and a URL, so it enforces what that's enough to decide —
`PUT /api/deliveries/*/status` needs `DELIVERY_AGENT`. It rejects bad traffic before it costs a
downstream service anything. But it cannot answer *"is this **your** order?"*, because that needs
the row. So each service verifies the token again and applies ownership rules itself. That
redundancy is the point: a service reachable by any other path — a port-forward, a misconfigured
network policy, another compromised container — is still safe.

**Decisions worth calling out:**

- **Identity comes from the token, never the request body.** `POST /api/orders` used to accept a
  `userId` field; it doesn't any more. An attacker-controlled field is not an identity claim.
- **Reading someone else's order returns `404`, not `403`.** A 403 confirms the record exists,
  which is enough to count a competitor's order volume by walking ids.
- **Ownership filtering is a query, not a post-fetch filter** — rows you may not see are never
  loaded.
- **`ADMIN` cannot be self-assigned at registration.** Trusting a role name from an anonymous
  request body is privilege escalation in one line. The first admin is seeded at startup.
- **Login gives the same answer for "no such user" and "wrong password"**, and runs the bcrypt
  comparison either way, so it leaks nothing through the response *or* through timing.
- **Passwords are stored `{bcrypt}`-prefixed** via Spring's delegating encoder, so the hashing
  algorithm can be changed later without forcing a password reset on everyone.

**One subtlety worth reading the code for:** `order-service` calls user- and restaurant-service
through Feign, and those calls need the caller's token. But Resilience4j runs circuit-breakered
calls on a *separate thread* so its time limiter can abandon a hang — and `SecurityContextHolder`
is a `ThreadLocal`, so the identity vanishes on the way. The fix is a
`DelegatingSecurityContextExecutorService` wired into the circuit breaker factory
(`common-security/.../FeignTokenRelayAutoConfiguration`). Symptom if you get it wrong:
authentication works perfectly until you switch circuit breakers on.

---

## Design decisions

**Database per service.** Each stateful service has its own PostgreSQL database and no service
reads another's tables. This is what makes them independently deployable — otherwise a schema
change couples every service through the database. The cost is no cross-service joins, which is
why events carry the data consumers need (`payment-processed` includes the delivery address so
delivery-service never has to call back).

**Circuit breakers on synchronous calls.** `order-service` calls user- and restaurant-service
directly. Without protection, a hanging dependency would exhaust order-service's threads and take
it down too. Resilience4j opens the circuit after 50% of a 10-call window fails and fails fast for
10s. The fallback returns `null`, which `OrderService` treats as *"dependency down — accept the
order anyway"*: availability is chosen over strict validation, since taking orders is the revenue
path. A definitive `404` (service is up, user genuinely doesn't exist) still rejects the order.

**Shared contracts, not shared databases.** Three library modules carry cross-service concerns:
`common-events` (Kafka event records + topic names), `common-web` (a uniform error response and
global exception handler), and `common-security` (the role model and JWT verification). All three
apply through Spring auto-configuration, so error formats and security policy can't drift between
services — and a typo in a role name is a compile error rather than a silently-open endpoint.

**One source of identity.** Account ids are issued by `auth-service` and every other service keys
off them — `user-service` profiles are stored under the account id rather than generating their
own. An earlier version let each service generate its own ids, which meant the "7" in a token and
the "7" in an order row came from different sequences and referred to different people. Ownership
checks would then compare unrelated numbers and occasionally match by coincidence: the worst
failure mode an authorization rule can have.

---

## Services

| Service | Port | Storage | Responsibility |
|---|---|---|---|
| `discovery-server` | 8761 | – | Eureka registry |
| `api-gateway` | 8080 | – | Single entry point, JWT verification, route-level roles, Swagger aggregation |
| `auth-service` | 8087 | `authdb` | Registration, login, JWT issuing, role management |
| `user-service` | 8081 | `userdb` | Customer profiles (keyed by account id) |
| `restaurant-service` | 8082 | `restaurantdb` | Restaurants and menus |
| `order-service` | 8083 | `orderdb` | Order placement, Kafka producer **and** consumer |
| `payment-service` | 8084 | `paymentdb` | Payment processing (simulated gateway) |
| `delivery-service` | 8085 | `deliverydb` | Driver assignment, delivery tracking |
| `notification-service` | 8086 | in-memory | Customer notifications from events |
| `common-events` | – | – | Shared event contracts *(library)* |
| `common-web` | – | – | Error contract + exception handling *(library)* |
| `common-security` | – | – | Role model, JWT verification, token relay *(library)* |
| `frontend` | 3000 | – | Web UI (nginx: static files + `/api` reverse proxy) |

---

## Tech stack

**Backend:** Java 21 · Spring Boot 3.3 · Spring Cloud 2023.0 (Gateway, Eureka, OpenFeign,
Resilience4j) · Spring Security + OAuth2 Resource Server (JWT) · Apache Kafka · PostgreSQL 16 ·
Spring Data JPA · springdoc OpenAPI 3

**Frontend:** vanilla ES modules · nginx (static + reverse proxy) — no build tooling

**Tooling:** Testcontainers · JUnit 5 / Mockito / AssertJ · Docker & Docker Compose · GitHub Actions

---

## The web UI

`frontend/` is a small single-page app that exists to make the backend's behaviour visible — the
role model, the ownership rules, and the Kafka event chain are much easier to believe when you can
watch them.

**It has no build step.** No `npm install`, no `node_modules`, no lockfile — the files written are
the files the browser runs, served by nginx. That's a deliberate trade: a React build would be
more conventional, but this runs unchanged on any machine with a browser, which matters more for a
project whose point is the backend.

What's worth looking at:

- **The nav changes shape per role.** Sign in as a driver and it's a delivery board; as an owner,
  a menu editor; as an admin, a console.
- **The order screen polls and draws a timeline** — `PLACED → PAID → ASSIGNED → DELIVERED` —
  so you watch four services react to Kafka events in real time. That's eventual consistency
  made visible rather than described.
- **UI role checks are cosmetics, and the code says so.** Delete `auth.js` and nothing becomes
  insecure; the app would just offer buttons that return 403. Try it: as a customer the Admin tab
  is hidden, but typing `#/admin` loads the page and every request on it comes back 403 from the
  gateway.
- **No CORS configuration is involved** in the Docker setup. nginx serves the app *and* proxies
  `/api` to the gateway, so the browser only ever sees one origin — the problem is removed rather
  than configured around.

---

## Quick start

Everything — infrastructure, eight services, and the UI — in one command:

```bash
docker compose up --build
```

Then:
- **Web UI:** http://localhost:3000 *(sign in as `admin` / `admin12345`)*
- **Swagger UI** (all services): http://localhost:8080/swagger-ui.html
- **Eureka dashboard:** http://localhost:8761
- **API base:** http://localhost:8080

Prefer to use your own local PostgreSQL and run services from your IDE? See
**[TESTING.md](TESTING.md)** for that setup plus a full end-to-end walkthrough. In that mode,
serve the UI with `./frontend/serve.sh`.

### Try the order flow

Easiest in the UI at [localhost:3000](http://localhost:3000) — register a customer, browse, order,
and watch the status advance. The same flow with `curl`, going through the gateway on `:8080`:

```bash
# 1. Register a customer and a restaurant owner. Registration returns a token immediately.
CUSTOMER=$(curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"surya","password":"password123","roles":["CUSTOMER"]}' | jq -r .accessToken)

OWNER=$(curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"chef","password":"password123","roles":["RESTAURANT_OWNER"]}' | jq -r .accessToken)

# 2. The customer creates their own profile. The account id comes from the token —
#    there is no id field to set.
curl -s -X POST localhost:8080/api/users -H "Authorization: Bearer $CUSTOMER" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Surya","email":"surya@example.com","address":"221B Baker St"}'

# 3. The owner creates a restaurant. Ownership is recorded from their token.
RESTAURANT=$(curl -s -X POST localhost:8080/api/restaurants -H "Authorization: Bearer $OWNER" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Curry House","cuisine":"Indian","address":"12 Spice Rd"}' | jq -r .id)

# 4. Place an order — triggers the payment → delivery → notification chain.
#    Note there is no userId: the order belongs to whoever the token says you are.
curl -s -X POST localhost:8080/api/orders -H "Authorization: Bearer $CUSTOMER" \
  -H 'Content-Type: application/json' \
  -d "{\"restaurantId\":$RESTAURANT,\"deliveryAddress\":\"221B Baker St\",
       \"items\":[{\"name\":\"Butter Chicken\",\"quantity\":2,\"price\":12.50}]}"

# 5. Watch the status advance as events flow through the system.
curl -s localhost:8080/api/orders -H "Authorization: Bearer $CUSTOMER"
```

**See the authorization actually bite:**

```bash
# No token at all -> 401
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/orders

# A restaurant owner trying to place an order -> 403 (wrong role)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/orders \
  -H "Authorization: Bearer $OWNER" -H 'Content-Type: application/json' \
  -d '{"restaurantId":1,"deliveryAddress":"x","items":[{"name":"a","quantity":1,"price":1}]}'

# A customer reading the payment ledger -> 403 (admin-only)
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/payments \
  -H "Authorization: Bearer $CUSTOMER"

# Anyone trying to sign up as an admin -> 403 (roles are not self-assignable)
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"sneaky","password":"password123","roles":["ADMIN"]}'
```

The seeded admin (`admin` / `admin12345` by default) can see everything:

```bash
ADMIN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin12345"}' | jq -r .accessToken)

curl -s localhost:8080/api/payments      -H "Authorization: Bearer $ADMIN"
curl -s localhost:8080/api/notifications -H "Authorization: Bearer $ADMIN"
```

---

## Testing

```bash
./mvnw verify          # unit + integration tests
```

**Unit tests** (Mockito) cover order total calculation, event publishing, validation rejection,
and graceful degradation when a dependency's circuit is open.

**Security tests** cover the parts that fail quietly if they break: password hashing (asserted
against a *real* encoder, since a mock would happily let a bug that stores plaintext pass), the
refusal to self-assign `ADMIN`, login failing identically for unknown users and wrong passwords,
and a full JWT round trip — signed by auth-service's encoder, verified by the very decoder the
other services use, including a tampered-payload token being rejected. Ownership rules are tested
directly: customer 7 reading customer 8's order gets a 404, and listing orders as a customer is
asserted to hit `findByUserId` and never `findAll`.

**Integration tests** (Testcontainers) start a **real PostgreSQL and a real Kafka broker** in
Docker, POST an order through the full HTTP stack, then assert both that the row landed in
Postgres and that the event landed on the Kafka topic — catching serialization and configuration
bugs that mocks would hide. They skip automatically (rather than fail) where Docker isn't
reachable, so the build stays green on machines without it.

> **macOS note:** if Testcontainers reports *"Could not find a valid Docker environment"*, export
> `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`. Docker Desktop doesn't populate
> `/var/run/docker.sock` on every setup, and Docker Engine 29+ rejects the older API versions that
> Testcontainers < 1.20 negotiates.

---

## API documentation

Every service publishes an OpenAPI 3 spec, aggregated into one Swagger UI at the gateway:

- **http://localhost:8080/swagger-ui.html** — pick a service from the dropdown
- Individual specs: `http://localhost:8080/docs/<service-name>/v3/api-docs`

To call a protected endpoint from Swagger UI, get a token from `POST /api/auth/login` first and
paste it into the **Authorize** box.

> The gateway's `discovery.locator` is deliberately **off**. When enabled it exposes every
> registered service at `/<service-id>/**`, which quietly bypasses the gateway's role rules —
> those match on `/api/...` paths, so a customer blocked from `POST /api/restaurants` could just
> post to `/restaurant-service/api/restaurants` instead. Routes are declared explicitly so the
> gateway's surface is exactly what's written in its config.

---

## Project layout

```
food-delivery-app/              # parent POM — dependency management, module aggregation
├── common-events/              # shared Kafka event records + topic names
├── common-web/                 # shared error contract, auto-configured exception handling
├── common-security/            # role model, JWT verification, Feign token relay
├── discovery-server/           # Eureka registry
├── api-gateway/                # Spring Cloud Gateway + perimeter authorization
├── auth-service/               # registration, login, JWT issuing
├── user-service/               # ─┐
├── restaurant-service/         #  │
├── order-service/              #  ├─ business services, one PostgreSQL DB each
├── payment-service/            #  │
├── delivery-service/           # ─┘
├── notification-service/       # event consumer, no DB
├── frontend/                   # web UI — nginx, no build step
├── infra/postgres/             # database bootstrap SQL
├── docker-compose.yml          # full stack
├── docker-compose.infra.yml    # infrastructure only (for IDE development)
└── .github/workflows/ci.yml    # build, test, and image verification
```

---

## Known limitations

Deliberately scoped out, and worth stating plainly:

- **The DB write and Kafka publish are not atomic** — if the broker is unreachable after the order
  commits, no event is published. The fix is the transactional outbox pattern.
- **Tokens are signed with a shared HMAC secret (HS256)**, so every service holds a key that could
  also *mint* tokens — a compromise anywhere is a compromise everywhere. Production wants RS256:
  auth-service keeps a private key and publishes a public JWKS endpoint. It's a decoder change,
  not an architecture change.
- **No refresh tokens or revocation** — a stateless token stays valid until it expires, so
  disabling an account doesn't kill sessions already in flight. The 2-hour TTL bounds the damage;
  a real system pairs short access tokens with revocable refresh tokens.
- **No brute-force protection on login** — no rate limiting, no lockout after repeated failures.
- **`ddl-auto: update`** creates schemas automatically; production needs Flyway or Liquibase.
- **Consumers aren't idempotent** — a redelivered event would be processed twice.
- **Payments are simulated** rather than integrated with a real gateway.
- **The OpenAPI documents are public**, which lists every endpoint. Convenient for a portfolio
  project; a real deployment would put them behind the same authentication as everything else.
