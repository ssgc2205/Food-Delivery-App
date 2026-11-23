# Resume & Interview Notes

Everything here describes what the code in this repo **actually does**. Only claim what you can
walk an interviewer through — this file exists so you can.

---

## Resume bullets

Pick 3–4. The first two carry the most weight for an SDE1 backend role.

**Option A — concise (4 lines, best for a one-page resume)**

> **Food Delivery Platform** — *Java 21, Spring Boot 3, Spring Cloud, Spring Security, Kafka, PostgreSQL, Docker*
> - Built an event-driven microservices backend (8 services) where order → payment → delivery is
>   coordinated through Kafka topics, keeping services decoupled and independently deployable.
> - Implemented stateless JWT authentication with role-based access control across all services:
>   coarse route rules at the API Gateway plus per-service method and resource-ownership checks.
> - Designed database-per-service persistence with PostgreSQL and an API Gateway + Eureka service
>   discovery layer routing all external traffic through a single entry point.
> - Added resilience (Resilience4j circuit breakers with fallbacks) and integration tests using
>   Testcontainers that verify the order flow against real PostgreSQL and Kafka instances.

**Option B — additional bullets to swap in**

- Implemented both synchronous (OpenFeign, load-balanced via Eureka) and asynchronous (Kafka)
  inter-service communication, choosing per use case: validation reads sync, state changes async.
- Eliminated error-format drift across services by extracting a shared Spring auto-configuration
  module providing a uniform error contract and global exception handling.
- Built a reusable Spring Boot auto-configuration module applying identical JWT verification and
  role mapping to all 8 services, including bearer-token propagation on service-to-service calls.
- Containerized all services with multi-stage Docker builds and a single `docker compose up`
  workflow; automated build, test, and image verification in GitHub Actions CI.
- Documented every service with OpenAPI 3, aggregated into a single Swagger UI at the gateway.
- Built a role-aware single-page web UI (vanilla ES modules, no build tooling) served by nginx as
  a reverse proxy, giving the browser a single origin and removing CORS from the deployment.

**Formatting tip:** name the metrics you actually have (8 services, 4 roles, 3 Kafka topics,
6 databases). Don't invent latency or scale numbers — you have no load test to back them.

**On the frontend bullet:** only include it if the role is full-stack or the listing mentions
frontend work. For a backend SDE1 role it competes for space with stronger material, and "vanilla
JS" doesn't read as a frontend credential the way React would. Where it *does* earn its place is
in conversation — see the frontend question below.

**A caution on the security bullet.** "Implemented JWT authentication" invites hard questions, and
it should — it's the bullet most likely to get probed, because most candidates who write it have
only wired up a filter. Yours holds up: you can name the ThreadLocal/circuit-breaker interaction,
the 404-not-403 decision, and why `ADMIN` isn't self-assignable. Read the security Q&A below until
those come out naturally, and don't put the bullet on the page until they do.

---

## The 60-second verbal pitch

> "It's a food delivery backend built as eight Spring Boot microservices. The interesting part is
> the order flow: when a customer places an order, order-service saves it and publishes an
> `order-placed` event to Kafka rather than calling payment directly. Payment-service consumes it,
> charges, and publishes `payment-processed`; delivery-service picks that up and assigns a driver.
> Order-service listens to both and updates its own status. So the services never block on each
> other — if delivery-service is down, orders still get taken and it catches up when it restarts.
>
> Where I *do* need a synchronous answer — checking the user and restaurant exist before accepting
> an order — I use Feign clients through Eureka, wrapped in circuit breakers so a slow dependency
> can't take down order-service.
>
> Each service owns its own PostgreSQL database, so no service reads another's tables. I tested
> the order path end-to-end with Testcontainers, spinning up real Postgres and Kafka.
>
> For security, there's an auth-service that issues signed JWTs with the account's roles. The
> gateway verifies the token and does route-level role checks, and every service verifies it again
> and applies its own ownership rules — so a customer can only read their own orders, not just any
> order. I put the shared verification in an auto-configuration module so all eight services get
> identical policy."

---

## Questions you *will* be asked (and the answers)

**Q: Why microservices for this? Isn't a monolith simpler?**
Yes — and for a real product at this scale a modular monolith would be the right call. I built it
this way to work through the distributed-systems problems deliberately: service boundaries, async
messaging, partial failure, data ownership. The trade-off I'd name in a real design review is
operational overhead: eight deployables, eight sets of logs, and eventual consistency to reason
about, in exchange for independent deploys and failure isolation.

**Q: Why Kafka instead of just calling payment-service over HTTP?**
Three reasons. First, decoupling — order-service doesn't need to know payment-service exists, so I
added notification-service later without touching order-service at all. Second, resilience — if
payment-service is down, events queue in Kafka and get processed on restart instead of the order
failing. Third, fan-out — one `order-placed` event is consumed independently by both payment and
notification services.

**Q: So how does the order status get updated if it's all async?**
Order-service is both a producer and a consumer. It publishes `order-placed`, then listens on
`payment-processed` and `delivery-status` and updates its own row. That's eventual consistency:
right after `POST /api/orders` the status reads `PLACED`, and within a second or two it becomes
`PAID`, then `ASSIGNED`.

**Q: What happens if payment succeeds but delivery-service crashes before assigning a driver?**
Kafka's consumer group offsets mean the event isn't lost — on restart, delivery-service resumes
from its last committed offset and processes it. What I *haven't* implemented is a full Saga with
compensating transactions: if a payment succeeded and the order later has to be cancelled, there's
no automatic refund step. That'd be my next addition — an orchestrated saga, or an outbox pattern
so the DB write and the event publish are atomic.

**Q: Your DB write and Kafka publish aren't atomic. What if the publish fails?**
Correct, and that's a real gap. `placeOrder` is `@Transactional` and sends to Kafka inside it — if
the broker is unreachable the order is committed but no event goes out, and it stays `PLACED`
forever. The standard fix is the **transactional outbox**: write the event to an `outbox` table in
the same transaction, then relay it to Kafka with a separate poller or Debezium CDC. I know the
gap and can name the fix — I scoped it out to keep the project reviewable.

**Q: Why circuit breakers, and what does yours actually do?**
`order-service` calls user-service and restaurant-service synchronously to validate IDs. Without
protection, if user-service hangs, order-service's threads pile up waiting and it goes down too —
cascading failure. Resilience4j opens the circuit after 50% of a 10-call window fails, then fails
fast into a fallback for 10 seconds before probing again. My fallback returns `null`, and
`OrderService` treats that as "skip validation and accept the order" — I chose availability over
strict validation, since taking the order is the revenue path. A definitive 404 (service is up and
says the user doesn't exist) *does* reject the order. That distinction is deliberate.

**Q: How did you test this?**
Two layers. Unit tests with Mockito for business logic — total calculation, event publishing, and
the validation/degradation branches. Then an integration test with Testcontainers that boots real
PostgreSQL and Kafka in Docker, POSTs an order through the actual HTTP stack, and asserts the row
landed in Postgres *and* the event landed on the Kafka topic. That catches serialization and
config bugs a mocked test would miss.

**Q: Why database-per-service? Isn't that duplication?**
It's the constraint that makes services independent — if payment-service could read the orders
table, changing that schema would break it, and they'd be coupled through the database. The cost
is no cross-service joins and no foreign keys across boundaries, which is why events carry the
data consumers need (`payment-processed` includes the delivery address so delivery-service doesn't
have to call back for it).

---

### Security questions

**Q: Walk me through what happens when a user logs in.**
They POST username and password to auth-service. It looks the account up, compares the password
against a stored bcrypt hash, and if that passes, mints a JWT signed with HMAC-SHA256 containing
the subject, the account id, the roles, an issuer, and a two-hour expiry. The client sends that
back as `Authorization: Bearer <token>` on every later request. No session is stored anywhere —
the token *is* the session, which is what makes it work across eight independent services.

**Q: Why JWT rather than sessions?**
Sessions need shared state. With eight services, either every one of them queries a session store
on every request — a network hop on the hot path and a new single point of failure — or you get
sticky sessions and lose the ability to scale services independently. A signed token moves the
proof to the request itself: any service can verify it locally with the key it already holds.
The trade-off is revocation, which I'll come back to.

**Q: So the gateway checks the token. Why does each service check it again?**
Because the gateway is the front door, not the only door. Anything that can reach a service's port
directly — a misconfigured network policy, another compromised container, someone with a
port-forward — would otherwise face no authorization at all. Verifying an HMAC signature is
microseconds, so there's no real cost to being safe regardless of what's in front of you. They
also check *different* things: the gateway sees a method and a URL, so it can decide "may drivers
call this route", but only order-service can decide "is this your order", because that needs the
row.

**Q: How do you stop a user placing an order as someone else?**
The endpoint doesn't accept a user id any more. It originally took `userId` in the body, which
meant changing one number let you order on anyone's account. Now the id comes out of the verified
token. That's the general rule I applied everywhere: a request body is attacker-controlled input,
so it's never allowed to assert identity.

**Q: What happens if I request an order that isn't mine?**
You get a 404, not a 403. A 403 confirms the record exists, and that's enough to count a
competitor's order volume by walking ids. If you may not see something, the honest answer is that
it isn't there. And the list endpoint is scoped in the query — `findByUserId`, not `findAll` then
filter — so rows you can't see are never loaded at all.

**Q: Roles are in the token. What stops me editing them?**
The signature. A JWT is base64, not encryption — you can read the claims, and you should assume
users will. But changing `CUSTOMER` to `ADMIN` changes the payload, and the signature no longer
matches a value only the server can compute. There's a test for exactly this: it re-encodes the
payload with the role swapped, leaves the original signature, and asserts the decoder rejects it.

**Q: Could someone just register as an admin?**
No, and that was a real decision. Registration takes a roles list, so the naive implementation
trusts it — that's privilege escalation in one line, and it's one of the most common real-world
access-control bugs. Self-registration is limited to customer, restaurant owner, and driver;
`ADMIN` can only be granted by an existing admin. Which creates a chicken-and-egg problem, so
there's a startup seeder that creates the first admin when the account table has none.

**Q: Tell me something subtle you hit.**
Authentication worked until I turned circuit breakers on, then every downstream call started
failing with 401. Resilience4j runs the guarded call on a separate thread so its time limiter can
abandon a hang — you can't time out work on the thread that's doing the waiting. But Spring
Security keeps the principal in a `ThreadLocal`, so the identity didn't survive the hop, and my
Feign interceptor found nothing to relay. The fix was wrapping the circuit breaker's executor in
Spring Security's `DelegatingSecurityContextExecutorService`, which copies the context onto the
worker thread and clears it afterwards — that last part matters on a pooled thread that will serve
a different user next. The lazy alternative is disabling the thread pool, but that silently throws
away the timeout, which was the whole reason for the circuit breaker.

**Q: Your services share one HMAC secret. Isn't that a weakness?**
Yes, and I'd name it before you did. With HS256 the signing and verification key are the same, so
every service holds a key that could also mint tokens — compromise one, forge anything. It keeps
local setup to a single environment variable, which is why it's here. Production wants RS256:
auth-service holds a private key, the others fetch a public JWKS endpoint and can only verify.
It's a decoder change, not an architecture change — the claims and the role mapping stay identical.

**Q: How do you revoke a token?**
Right now, I can't — that's the honest answer, and it's the real cost of stateless auth. Disabling
an account stops new logins but doesn't kill tokens already issued. What I did do is bound the
damage: a two-hour TTL, so a stolen or stale token has a limited life. The proper fix is short
access tokens plus revocable refresh tokens, with a denylist of revoked ids the services check —
which reintroduces shared state, but only for the rare revocation case rather than every request.

**Q: What else is missing, security-wise?**
No rate limiting or lockout on login, so nothing slows down credential stuffing. No refresh
tokens. No audit log of privileged actions. The OpenAPI docs are public, which maps the whole API
for anyone probing. And there's no HTTPS in the compose setup — bearer tokens over plaintext are
only acceptable because it's all on localhost; anywhere real, TLS terminates at the gateway.

---

### Frontend questions

**Q: Why didn't you use React?**
For this project the frontend's job is to make the backend visible, and a build toolchain works
against that — anyone cloning it can run the UI with no `npm install` and nothing to be on the
wrong version of. There's a real cost: React's component model earns its keep on a larger app, and
it's what most teams use. I'd reach for it the moment the UI had enough state to justify it. What
I didn't want was a `node_modules` directory in a repo that's fundamentally about Spring Boot.

**Q: How does the frontend handle authorization?**
It doesn't — deliberately, and that's the point I'd make. The UI reads roles out of the JWT to
decide which nav links to draw, but that's presentation only. Anyone can open devtools and change
it, or skip the page entirely and use curl. Delete the whole auth module in the frontend and
nothing becomes insecure; the app would just offer buttons that come back 403. You can watch it:
as a customer the Admin tab is hidden, but navigate to `#/admin` directly and the page renders,
then every request on it gets a 403 from the gateway. The client's job is to avoid *offering*
actions that will fail. The server's job is to refuse them.

**Q: How did you handle CORS?**
By not having any. nginx serves the static files and reverse-proxies `/api` to the gateway, so the
browser only ever sees one origin — no preflight, no `Access-Control-Allow-Origin`, nothing to
misconfigure. CORS *is* configured on the gateway, but only for the development workflow where the
services run in an IDE and the origins genuinely differ. Two details there: the origins are listed
rather than `*`, and credentials are off, because authentication rides in an `Authorization`
header the app sets by hand rather than a cookie the browser attaches automatically.

**Q: Where do you store the token, and why?**
`localStorage`, with the downside stated plainly: any XSS on the page can read it. The alternative
is an `httpOnly` cookie the browser attaches automatically — immune to that, but then it rides
along on cross-site requests too, so it needs CSRF protection that bearer tokens otherwise avoid.
Neither is free. I escape every server-supplied value before it touches `innerHTML` and keep
tokens short-lived; for something handling real payments I'd use the cookie.

---

**Q: What would you do differently / what's missing?**
Honestly: the outbox pattern for atomicity, RS256 with a JWKS endpoint instead of a shared HMAC
secret, refresh tokens and revocation, Flyway migrations instead of Hibernate `ddl-auto: update`
(which is unsafe for production), distributed tracing so one request can be followed across
services, and idempotent consumers — right now a redelivered `payment-processed` would re-run the
handler.

---

## Known gaps — name these before the interviewer finds them

Volunteering these reads as engineering maturity, not weakness.

| Gap | Why it's there | The real fix |
|---|---|---|
| DB write + Kafka publish not atomic | Scoped out for size | Transactional outbox / Debezium CDC |
| Shared HMAC secret (HS256) | One env var beats key distribution locally | RS256 + JWKS endpoint |
| No token revocation or refresh tokens | Statelessness was the point | Short access + revocable refresh tokens |
| No rate limiting or lockout on login | Scoped out | Bucket4j at the gateway, failed-attempt counter |
| No HTTPS in the compose stack | Everything is on localhost | TLS terminating at the gateway |
| OpenAPI docs are public | Convenient for a portfolio project | Same auth as every other route |
| `ddl-auto: update` creates schemas | Fast local iteration | Flyway or Liquibase migrations |
| Consumers aren't idempotent | Simplification | Dedupe on event ID, or upsert semantics |
| Payment is simulated | No real gateway to integrate | Stripe/Razorpay integration |
| No distributed tracing | Scoped out | Micrometer Tracing + OpenTelemetry |
| Single Kafka broker, no replication | Local dev setup | Multi-broker cluster, replication factor ≥ 3 |

---

## Numbers you can quote

| Metric | Value |
|---|---|
| Services | 8 runtime services + 3 shared library modules + a web UI |
| Kafka topics | 3 (`order-placed`, `payment-processed`, `delivery-status`) |
| Databases | 6 (one per stateful service) |
| Roles | 4 (`CUSTOMER`, `RESTAURANT_OWNER`, `DELIVERY_AGENT`, `ADMIN`) |
| Build | 12-module Maven reactor |
| Deploy | `docker compose up --build` — full stack + UI, 12 containers |

**Counting tests:** run `./mvnw test` and read the number off the output rather than quoting one
from memory — the suite grew when security was added, and a number you can't reproduce on demand
is worse than no number. See the note below about the integration tests specifically.

---

## ⚠️ Things to verify before you publish

**1. Run the test suite.** All 12 modules compile, but the suite has **not been run since the
security work landed** — the new security tests and the updated order tests are written and
compiled, not yet observed passing.

```bash
./mvnw verify
```

Expect `BUILD SUCCESS`. If something fails, fix it before claiming it — the failures to expect are
in the tests that changed shape (`OrderServiceTest`, `OrderFlowIntegrationTest`), not in the new
ones.

**2. Run the integration tests specifically.** The Testcontainers tests have **still never been
observed passing** — they're written and compiled, but the environment they were authored in
couldn't reach the Docker daemon, so they skipped.

```bash
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./mvnw -pl order-service test \
  -Dtest=OrderFlowIntegrationTest
```

Or run `OrderFlowIntegrationTest` from IntelliJ with Docker Desktop running. If they skip,
Testcontainers still can't see Docker; if they fail, fix them before you claim them. Never put a
test on a resume you haven't watched go green.

**3. Walk the auth flow once by hand.** Follow **TESTING.md Steps 6–8** end to end. Step 8 is the
one that matters: watch the 401s, the 403s, and the 404-instead-of-403 actually happen. Security
that has never been seen rejecting anything is not known to work — and it's the part of this
project an interviewer is most likely to ask you to justify.

---

## GitHub presentation

1. **Push it public** with a clear repo name (`food-delivery-microservices`).
2. The README is what a reviewer reads — the architecture diagram is the first thing they see.
3. **Add topics** on the repo: `spring-boot`, `microservices`, `kafka`, `docker`, `java`.
4. Make sure the **CI badge is green** — a passing build tells a reviewer the project is real.
5. Commit in meaningful chunks with real messages, not one "initial commit" dump of everything.
