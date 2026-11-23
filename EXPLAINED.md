# The Project, Explained From Scratch

A complete walkthrough of this codebase: every technology, every folder, every design decision,
and why each one exists. Written to be read top to bottom if you're learning, or jumped into via
the table of contents if you need one topic.

If you only want to *run* the thing, see [TESTING.md](TESTING.md). If you want the short pitch,
see [README.md](README.md).

---

## Table of contents

1. [The big picture: what are we building?](#1-the-big-picture)
2. [Why microservices? (and why they're not free)](#2-why-microservices)
3. [Maven and the multi-module build](#3-maven-and-the-multi-module-build)
4. [Spring Boot fundamentals used here](#4-spring-boot-fundamentals)
5. [Service discovery with Eureka](#5-service-discovery-with-eureka)
6. [The API Gateway](#6-the-api-gateway)
7. [Databases: JPA, Hibernate, and database-per-service](#7-databases)
8. [Apache Kafka — the deep dive](#8-apache-kafka)
9. [Synchronous calls with OpenFeign](#9-openfeign)
10. [Circuit breakers and Resilience4j — the deep dive](#10-circuit-breakers)
11. [Docker and Docker Compose — the deep dive](#11-docker)
12. [Security: authentication and authorization — the deep dive](#12-security)
13. [The frontend, and why it has no build step](#13-the-frontend)
14. [Shared modules: common-events, common-web, common-security](#14-shared-modules)
15. [Testing: unit vs integration](#15-testing)
16. [Configuration and environment variables](#16-configuration)
17. [A complete request, traced through the code](#17-a-complete-request-traced)
18. [Every file in the repo](#18-every-file-in-the-repo)
19. [Glossary](#19-glossary)

---

## 1. The big picture

We're building the **backend** of a food delivery app — think Swiggy, DoorDash, Uber Eats. There's
no visual frontend; everything is REST APIs you call with `curl` or Postman.

The core user story:

> A customer signs in, browses restaurants, picks food, places an order. The order gets paid for,
> a driver is assigned, and the customer is notified at each step — and nobody can see or touch
> anyone else's data.

The naive way to build this is **one big application** (a "monolith") that does everything. We
instead split it into **eight small programs** that each do one job and talk to each other over
the network. That's the microservice architecture.

Here's the map:

```
                        ┌──────────────────────────┐
    client ────────────►│       api-gateway        │ :8080
   (Bearer token)       │  verifies the JWT first  │
                        └────────────┬─────────────┘
   ┌──────────────┬───────────────┬──┴────────────┬───────────────┬──────────────────┐
   ▼              ▼               ▼               ▼               ▼                  ▼
auth-service  user-service  restaurant-service  order-service  payment-service  delivery-service
   :8087         :8081           :8082             :8083           :8084            :8085
                                                     │               ▲                 ▲
                                                     │               │                 │
                                             ╔═══════▼═══════════════╧═════════════════╧═══════╗
                                             ║              Apache Kafka                       ║
                                             ╚═════════════════════════╤═══════════════════════╝
                                                                       ▼
                                                             notification-service :8086

  discovery-server :8761 (Eureka)        PostgreSQL :5432 (6 separate databases)
```

**Who does what:**

| Service | Its one job |
|---|---|
| `auth-service` | Holds credentials, checks passwords, issues signed tokens. |
| `user-service` | Stores customer profiles (name, address) — never passwords. |
| `restaurant-service` | Stores restaurants and their menu items. |
| `order-service` | Takes orders, tracks their status. |
| `payment-service` | Charges the customer. |
| `delivery-service` | Assigns a driver, tracks the delivery. |
| `notification-service` | Turns things that happen into customer messages. |
| `api-gateway` | The single front door. Verifies tokens and routes requests. |
| `discovery-server` | A phone book so services can find each other. |

---

## 2. Why microservices?

### The problem with a monolith

Imagine all this code in one application. It works fine — until:

- **Deployment is all-or-nothing.** Fixing a typo in a notification message means redeploying
  payment processing too. Every deploy risks everything.
- **One bug takes down everything.** A memory leak in restaurant search crashes the process that
  also handles payments.
- **Scaling is coarse.** If order placement is your bottleneck, you have to run more copies of
  the *entire* application, including the parts that aren't busy.
- **One team, one codebase.** Twenty developers merging into the same repo means constant
  conflicts.

### What microservices give you

Each service is its own program, its own deployable, its own database:

- **Independent deploys.** Ship notification-service ten times a day without touching payments.
- **Failure isolation.** delivery-service crashing doesn't stop orders from being accepted.
- **Targeted scaling.** Run five copies of order-service and one of restaurant-service.
- **Team ownership.** One team owns payment-service end to end.

### The honest cost

**Microservices are not the default right answer.** For a real product at this size, a
well-structured monolith would likely be better. What you pay:

- **Operational complexity.** Seven things to deploy, monitor, and debug instead of one.
- **The network is unreliable.** A method call that used to be nanoseconds and never failed is now
  a network hop that can time out. This is why we need circuit breakers (section 10).
- **No transactions across services.** In a monolith you'd wrap "save order + charge card" in one
  database transaction. Across services, that's impossible — you get **eventual consistency**
  instead, and you have to design for it.
- **Debugging is harder.** One user request touches four services; you need distributed tracing to
  follow it.

This project exists to work through those problems deliberately. Being able to say all of the
above is more valuable than the code itself.

---

## 3. Maven and the multi-module build

**Maven** is Java's build tool. It compiles code, runs tests, downloads dependencies, and packages
everything into a runnable `.jar` file. It's driven by a file called `pom.xml`
(*Project Object Model*).

### The parent POM

At the repo root, `pom.xml` doesn't contain any code. It's the **parent** — it declares:

```xml
<packaging>pom</packaging>          <!-- "I'm a container, not a program" -->

<modules>                           <!-- the actual projects underneath me -->
    <module>common-events</module>
    <module>common-web</module>
    <module>discovery-server</module>
    ...
</modules>
```

Running `./mvnw package` at the root builds **all eleven modules in dependency order** — this is
called the *reactor build*. Maven works out that `common-events` must be built before
`order-service`, because order-service depends on it.

### Dependency management

The parent also declares `<dependencyManagement>`, which sets **versions in one place**:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>       <!-- import a whole BOM of versions -->
        </dependency>
    </dependencies>
</dependencyManagement>
```

A **BOM** (Bill of Materials) is a curated list of library versions known to work together. By
importing Spring Cloud's BOM, every module can write:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
    <!-- no <version> — inherited from the BOM -->
</dependency>
```

This prevents "dependency hell", where module A uses Kafka client 3.2 and module B uses 3.7 and
they subtly break each other.

### One override worth understanding

```xml
<testcontainers.version>1.20.6</testcontainers.version>
```

Spring Boot's BOM pins Testcontainers to an older version that negotiates a Docker API version
newer Docker Engines reject. Overriding the property replaces it everywhere. Knowing you *can*
override a managed version is a genuinely useful Maven skill.

### The Maven Wrapper

`./mvnw` is the **Maven Wrapper** — a small script that downloads the correct Maven version
automatically. It means anyone can build the project with just a JDK installed; they don't need
Maven. That's why the command is `./mvnw` and not `mvn`.

### Useful commands

| Command | What it does |
|---|---|
| `./mvnw compile` | Compile source code only |
| `./mvnw test` | Compile + run tests |
| `./mvnw package` | Compile + test + build the `.jar` |
| `./mvnw install` | ...and copy the jar into your local `~/.m2` repo so other modules can use it |
| `./mvnw verify` | Full build including integration tests |
| `./mvnw -pl order-service -am package` | Build only order-service **and** the modules it needs (`-am` = also make) |
| `./mvnw -DskipTests package` | Skip tests (faster, for when you just need the jar) |

---

## 4. Spring Boot fundamentals

**Spring** is a framework that wires your objects together for you. **Spring Boot** is Spring with
sensible defaults so you don't write mountains of configuration.

### The entry point

Every service has a class like this:

```java
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

`@SpringBootApplication` is three annotations in one:

- `@Configuration` — this class can define beans
- `@ComponentScan` — **scan this package and everything below it** for Spring components
- `@EnableAutoConfiguration` — configure things automatically based on what's on the classpath

That last one is the magic. Spring Boot sees `spring-boot-starter-web` in your dependencies and
concludes: *"they want a web app"* → it starts an embedded Tomcat server on port 8080, sets up
JSON conversion, and registers all the plumbing. You wrote none of it.

### Dependency injection

You never write `new UserRepository()`. Instead you declare what you need in the constructor:

```java
@RestController
public class UserController {
    private final UserRepository repository;

    public UserController(UserRepository repository) {   // Spring passes it in
        this.repository = repository;
    }
}
```

Spring creates one instance of each component (a **bean**), stores it in a container, and hands it
to whoever asks. This is **Inversion of Control** — you don't construct your dependencies, the
framework does.

Why it matters: in tests you can pass a *fake* repository into that same constructor. That's
exactly what `OrderServiceTest` does with Mockito.

### The stereotype annotations

| Annotation | Meaning | Example in this repo |
|---|---|---|
| `@RestController` | Handles HTTP requests, returns JSON | `UserController` |
| `@Service` | Business logic | `OrderService` |
| `@Repository` | Database access | `UserRepository` |
| `@Component` | Generic Spring-managed object | `NotificationListener` |
| `@Configuration` | Defines beans | `CommonWebAutoConfiguration` |

They're functionally near-identical — they exist to communicate *intent* to the reader.

### Handling a web request

```java
@RestController
@RequestMapping("/api/users")          // every method here starts with /api/users
public class UserController {

    @GetMapping("/{id}")               // GET /api/users/5
    public User byId(@PathVariable Long id) { ... }

    @PostMapping                       // POST /api/users
    @ResponseStatus(HttpStatus.CREATED)             // return 201, not 200
    public User create(@Valid @RequestBody CreateUserRequest request) { ... }
}
```

- `@PathVariable` — pull a value out of the URL
- `@RequestBody` — parse the JSON body into an object
- `@Valid` — enforce the validation annotations on that object *before* the method runs

### Validation

Request objects declare their rules:

```java
public record CreateUserRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        String phone,                  // optional
        String address
) {}
```

If someone POSTs `{"name": "", "email": "not-an-email"}`, Spring rejects it with a 400 **before**
your code runs. The `GlobalExceptionHandler` (section 14) then formats that into a clean error.

### Records

`public record CreateUserRequest(String name, ...)` is a Java 16+ feature: an immutable data class
where the compiler generates the constructor, getters, `equals`, `hashCode`, and `toString`. All
our events and DTOs are records — they're data, they never change after creation.

---

## 5. Service discovery with Eureka

### The problem

`order-service` needs to call `user-service`. What URL?

Hardcoding `http://localhost:8081` breaks the moment you deploy to real servers. And if you run
*three copies* of user-service for load, which one do you call? What if one dies?

### The solution: a registry

**Eureka** (from Netflix) is a phone book:

1. On startup, every service **registers**: *"I'm `user-service`, reachable at 10.0.1.7:8081."*
2. Every 30 seconds it sends a **heartbeat**: *"still alive."*
3. Services **fetch the registry** and cache it locally.
4. Stop heartbeating, and Eureka **evicts** you — no one routes to a dead instance.

So `order-service` asks for "user-service" by name and gets back a live address.

### In this project

`discovery-server` is the registry itself:

```java
@EnableEurekaServer                     // "I am the phone book"
@SpringBootApplication
public class DiscoveryServerApplication { ... }
```

```yaml
eureka:
  client:
    register-with-eureka: false         # don't register with myself
    fetch-registry: false               # don't fetch from myself
```

Every other service is a **client** — just by having `spring-cloud-starter-netflix-eureka-client`
on the classpath plus:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://${EUREKA_HOST:localhost}:8761/eureka/
```

Visit **http://localhost:8761** and you'll see every registered instance. That dashboard is the
fastest way to check whether a service actually started.

**Why discovery-server must start first:** other services will retry registration if it's not up,
but nothing can find anything until it is. That's why `docker-compose.yml` uses
`depends_on: condition: service_healthy` and `run-all.sh` sleeps 15 seconds after starting it.

---

## 6. The API Gateway

### The problem

Without a gateway, a client needs to know all six service addresses and ports. Add authentication,
rate limiting, or CORS, and you'd implement them six times.

### The solution

**Spring Cloud Gateway** is one entry point at `:8080` that routes to the right service:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service        # "lb" = load-balanced, resolved via Eureka
          predicates:
            - Path=/api/users/**        # if the path matches, send it here
```

- **Predicate** = a condition. *If the path starts with `/api/users/`...*
- **URI** = where to send it. `lb://user-service` means *"ask Eureka for user-service and pick an
  instance"* — if three are running, requests are balanced across them automatically.

So `curl localhost:8080/api/users/1` hits the gateway, which forwards to whichever user-service
instance is healthy. The client only ever knows about port 8080.

### Why this is architecturally important

The gateway is the natural home for cross-cutting concerns:

- **Authentication** — validate a JWT once at the edge instead of in six services *(not yet built
  here — it's the top item on the "next steps" list)*
- **Rate limiting**
- **CORS**
- **Request logging / tracing**

### The `discovery.locator` setting

```yaml
discovery:
  locator:
    enabled: false
```

This setting auto-creates a route for *every* registered service at `/<service-name>/**`. It's
handy in development, and it is **switched off here on purpose**.

Once the gateway started enforcing role rules, the locator became a hole rather than a
convenience. The rules match on `/api/...` paths, so a route that reaches the same service at
`/restaurant-service/api/restaurants` matches none of them and slips through to the catch-all.
Section 12 covers this properly — the short version is that an auto-generated route is an
auto-generated bypass, and a gateway's attack surface should be exactly what's written in its
config.

---

## 7. Databases

### JPA and Hibernate

**JPA** (Jakarta Persistence API) is a specification for mapping Java objects to database tables.
**Hibernate** is the implementation Spring Boot uses. Together they let you avoid writing SQL for
routine work.

An **entity** is a class that maps to a table:

```java
@Entity                                  // this is a table
@Table(name = "users")                   // called "users"
public class User {

    @Id                                              // primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // database assigns it
    private Long id;

    @Column(nullable = false)            // NOT NULL
    private String name;

    @Column(nullable = false, unique = true)
    private String email;
}
```

### Repositories

This interface — with **no implementation** — gives you full CRUD:

```java
public interface UserRepository extends JpaRepository<User, Long> {
}
```

Spring generates the implementation at runtime. You get `save()`, `findById()`, `findAll()`,
`deleteById()`, `count()`, and more, for free.

Even better, **derived queries** — Spring parses the *method name* and writes the SQL:

```java
public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
    List<MenuItem> findByRestaurantId(Long restaurantId);
    // becomes: SELECT * FROM menu_items WHERE restaurant_id = ?
}
```

### Embedded collections

An order has multiple items, but an item has no meaning on its own:

```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
private List<OrderItem> items = new ArrayList<>();
```

`OrderItem` is `@Embeddable`, not `@Entity` — it lives in its own table but has no independent
identity. `EAGER` means load the items whenever you load the order (fine here; items are always
needed and always few).

### Configuration explained

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:orderdb}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

**`ddl-auto: update`** — Hibernate inspects your entity classes on startup and creates/alters
tables to match. Wonderful for development; **dangerous in production**, because it can't do
safe migrations, never drops columns, and can behave unpredictably. Real systems use **Flyway** or
**Liquibase**, where every schema change is a versioned, reviewed SQL script. This is a known
limitation of the project and worth saying out loud.

**`open-in-view: false`** — Spring's default keeps the database session open for the whole HTTP
request, which quietly allows lazy-loading in your controller and causes surprise N+1 queries. Off
is the better default.

### Database-per-service

This is the important architectural rule:

```
user-service       →  userdb        (tables: users)
restaurant-service →  restaurantdb  (tables: restaurants, menu_items)
order-service      →  orderdb       (tables: orders, order_items)
payment-service    →  paymentdb     (tables: payments)
delivery-service   →  deliverydb    (tables: deliveries)
```

**No service reads another service's tables.** Ever.

Why so strict? If payment-service could `SELECT` from the orders table, then changing the orders
schema would break payment-service. You'd have all the operational cost of microservices with none
of the independence — a "distributed monolith", the worst of both worlds.

**What it costs you:** no `JOIN` across services, and no foreign keys across boundaries. If
delivery-service needs the delivery address, it can't join to `orders`. So the *event* carries it:

```java
public record PaymentProcessedEvent(
        Long orderId, Long paymentId, Long userId, Long restaurantId,
        BigDecimal amount,
        String deliveryAddress,      // ← carried along so delivery-service needn't ask
        String status) {}
```

That's a deliberate trade: a little data duplication in exchange for decoupling.

---

## 8. Apache Kafka

This is the heart of the project, so we'll go slowly.

### What problem does it solve?

When an order is placed, three things must happen: charge the card, assign a driver, notify the
customer.

**The direct approach:**

```java
public void placeOrder(...) {
    orderRepository.save(order);
    paymentService.charge(order);        // HTTP call
    deliveryService.assign(order);       // HTTP call
    notificationService.notify(order);   // HTTP call
}
```

Problems:

1. **Slow** — the customer waits for all three round trips.
2. **Fragile** — if notification-service is down, does the whole order fail? The customer's card
   is already charged.
3. **Tightly coupled** — order-service must know all three services exist. Adding a fourth
   (analytics) means changing and redeploying order-service.

**The Kafka approach:**

```java
public void placeOrder(...) {
    orderRepository.save(order);
    kafkaTemplate.send("order-placed", event);   // announce it, then return
}
```

order-service says *"an order was placed"* to nobody in particular and returns immediately.
Whoever cares subscribes. Adding analytics later means writing a new service that subscribes —
**order-service never changes.**

### Core concepts

**Kafka is a distributed, append-only log.** Not a queue that deletes messages when read — a
durable log that many independent readers can replay.

| Term | What it means |
|---|---|
| **Broker** | A Kafka server. We run one; production runs three or more. |
| **Topic** | A named stream of messages, e.g. `order-placed`. |
| **Partition** | A topic is split into partitions for parallelism. Order is guaranteed *within* a partition, not across them. |
| **Producer** | Something that writes messages. |
| **Consumer** | Something that reads messages. |
| **Consumer group** | A team of consumers sharing the work. Each partition goes to exactly one member. |
| **Offset** | A consumer's bookmark — how far it has read. |

### Why offsets matter so much

Kafka doesn't delete a message once it's read. Each consumer group tracks its own **offset**.

The practical consequence: **if delivery-service crashes, nothing is lost.** When it restarts, it
resumes from its last committed offset and processes everything it missed. With direct HTTP calls,
those requests would simply have failed.

### Consumer groups and the fan-out pattern

Look at `payment-processed`. Two different services consume it:

```java
// order-service
@KafkaListener(topics = Topics.PAYMENT_PROCESSED, groupId = "order-service")
public void onPayment(PaymentProcessedEvent event) { ... }

// delivery-service
@KafkaListener(topics = Topics.PAYMENT_PROCESSED, groupId = "delivery-service")
public void onPayment(PaymentProcessedEvent event) { ... }
```

Different `groupId` values mean **both receive every message independently**, each with its own
offset. That's fan-out.

If you instead ran *two instances of order-service*, both with `groupId = "order-service"`, Kafka
would split the partitions between them and each message would be handled **once**. That's how you
scale a consumer horizontally.

> **`groupId` is one of the most important settings in Kafka.** Same group = share the work.
> Different groups = everyone gets a copy.

### Our three topics

Defined once in `common-events` so nobody typos a topic name:

```java
public final class Topics {
    public static final String ORDER_PLACED = "order-placed";
    public static final String PAYMENT_PROCESSED = "payment-processed";
    public static final String DELIVERY_STATUS = "delivery-status";
}
```

```
order-service ──publishes──► order-placed ──────┬──► payment-service
                                                └──► notification-service

payment-service ──publishes──► payment-processed ─┬──► delivery-service
                                                  ├──► order-service
                                                  └──► notification-service

delivery-service ──publishes──► delivery-status ──┬──► order-service
                                                  └──► notification-service
```

Note that **order-service is both a producer and a consumer**. It publishes `order-placed`, then
listens for the consequences to update its own status field.

### Producing a message

```java
kafkaTemplate.send(Topics.ORDER_PLACED, String.valueOf(saved.getId()), event);
//                 ^topic               ^key                          ^value
```

The **key** determines the partition. Using the order ID means *all events for one order land on
the same partition*, and therefore stay in order relative to each other. That's a subtle but real
guarantee — without a key, messages are distributed round-robin and ordering is lost.

### Consuming a message

```java
@KafkaListener(topics = Topics.ORDER_PLACED, groupId = "payment-service")
public void onOrderPlaced(OrderPlacedEvent event) {
    // Spring polls Kafka, deserializes the JSON, and calls this method
}
```

You never write a polling loop. Spring runs it in a background thread and invokes your method per
message.

### Serialization

Kafka only moves bytes. Something must convert `OrderPlacedEvent` → bytes → `OrderPlacedEvent`.

```yaml
producer:
  key-serializer: org.apache.kafka.common.serialization.StringSerializer
  value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
consumer:
  key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
  value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
  properties:
    spring.json.trusted.packages: com.surya.fooddelivery.events
```

That last line is a **security setting**. The JSON deserializer can instantiate arbitrary classes
named in the message, which is a remote-code-execution risk. Restricting it to our events package
means a malicious message can't make us construct dangerous objects.

### KRaft: no ZooKeeper

Older Kafka needed a separate ZooKeeper cluster for coordination. Modern Kafka uses **KRaft**,
where Kafka manages its own metadata. That's what all this in `docker-compose.yml` is:

```yaml
KAFKA_PROCESS_ROLES: broker,controller       # be both roles in one process
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
```

One container instead of two.

### Eventual consistency — the thing to actually understand

Because everything after step 1 is asynchronous:

```
t=0ms     POST /api/orders returns 201 with status "PLACED"
t=~50ms   payment-service consumes, charges, publishes → order becomes "PAID"
t=~100ms  delivery-service consumes, assigns driver     → order becomes "ASSIGNED"
```

If you `GET /api/orders/1` immediately, you may see `PLACED`. A second later, `ASSIGNED`. The
system is **eventually consistent** — correct in the end, briefly out of date in between.

This is a genuine trade-off, not a bug. You accept a short window of staleness in exchange for
services that don't block on each other. A real app shows "Order received…" and updates the UI as
events arrive.

### The gap you should know about

```java
@Transactional
public FoodOrder placeOrder(...) {
    FoodOrder saved = repository.save(order);       // database transaction
    kafkaTemplate.send(Topics.ORDER_PLACED, ...);   // NOT part of that transaction
}
```

The database write and the Kafka publish **are not atomic**. If Kafka is unreachable, the order
commits but no event is published — it stays `PLACED` forever, silently.

The standard fix is the **transactional outbox pattern**: write the event into an `outbox` table
in the *same* database transaction, then have a separate process relay rows from that table into
Kafka. Now either both happen or neither does.

We haven't implemented it. Knowing the gap and naming the fix is what matters in an interview.

---

## 9. OpenFeign

Not everything should be asynchronous. Before accepting an order we want to know **right now**
whether the user and restaurant exist. That needs a synchronous call.

### Declarative HTTP clients

Instead of hand-writing HTTP calls, you declare an interface:

```java
@FeignClient(name = "user-service", fallback = UserClientFallback.class)
public interface UserClient {

    @GetMapping("/api/users/{id}")
    UserSummary getUser(@PathVariable("id") Long id);

    record UserSummary(Long id, String name, String email, String address) {}
}
```

Feign generates the implementation. Calling `userClient.getUser(5L)`:

1. Asks Eureka for instances of `user-service`
2. Picks one (load balancing)
3. Sends `GET http://<that-instance>/api/users/5`
4. Parses the JSON response into `UserSummary`

Compare that to writing `RestTemplate` code with manual URL building and error handling.

Enabled with `@EnableFeignClients` on `OrderServiceApplication`.

### Note the DTO

`UserSummary` is a small record inside `UserClient` — **not** a shared class imported from
user-service. That's intentional. Sharing entity classes between services would couple them: a
change in user-service's `User` would ripple into order-service. Instead, order-service declares
only the fields *it* needs. If user-service adds a field, nothing here breaks.

---

## 10. Circuit breakers

### The problem: cascading failure

`order-service` calls `user-service` synchronously. Now user-service gets slow — not down, just
taking 30 seconds per request.

What happens to order-service?

1. A request comes in; the thread calls user-service and **blocks** for 30 seconds.
2. More requests arrive; more threads block.
3. Tomcat's thread pool (200 by default) fills up.
4. **order-service stops responding entirely** — including for requests that don't touch
   user-service at all.

One slow service has taken down a healthy one. Repeat outward and you lose the whole system.
This is **cascading failure**, and it's the defining failure mode of distributed systems.

### The solution

A **circuit breaker** wraps calls to a dependency and watches the failure rate. Named after an
electrical breaker: when there's a fault, it trips and stops the current rather than letting the
house burn.

**Three states:**

```
        ┌─────────────────────────────────────────────────┐
        │                                                 │
        ▼                                                 │
   ┌─────────┐  failure rate too high   ┌──────┐          │
   │ CLOSED  │─────────────────────────►│ OPEN │          │
   │(normal) │                          │(fail │          │
   └─────────┘                          │ fast)│          │
        ▲                               └───┬──┘          │
        │                                   │ after wait   │
        │                                   ▼  duration    │
        │      probe calls succeed    ┌───────────┐        │
        └─────────────────────────────│ HALF_OPEN │────────┘
                                      │ (testing) │  probes fail
                                      └───────────┘
```

- **CLOSED** — normal. Calls pass through; failures are counted.
- **OPEN** — too many failures. Calls **fail instantly** without touching the network. This is the
  point: you stop wasting threads waiting on something known to be broken.
- **HALF_OPEN** — after a wait, let a few calls through to test. Recovered? → CLOSED. Still
  broken? → OPEN.

### Our configuration, line by line

From `order-service/src/main/resources/application.yml`:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
```

| Setting | Meaning |
|---|---|
| `slidingWindowSize: 10` | Judge health on the last 10 calls |
| `minimumNumberOfCalls: 5` | Don't trip before seeing 5 calls (1 failure out of 1 isn't evidence) |
| `failureRateThreshold: 50` | Trip when 50% of the window failed |
| `waitDurationInOpenState: 10s` | Stay open 10s before probing |
| `permittedNumberOfCallsInHalfOpenState: 3` | Allow 3 probe calls to decide |

Plus a time limiter:

```yaml
timelimiter:
  configs:
    default:
      timeoutDuration: 3s        # a call taking >3s counts as a failure
```

That's what defeats the *slow* dependency, as opposed to the dead one.

And to connect Feign to it:

```yaml
spring:
  cloud:
    openfeign:
      circuitbreaker:
        enabled: true
```

### Fallbacks

When the circuit is open, the call goes to the **fallback** instead:

```java
@Component
public class UserClientFallback implements UserClient {
    @Override
    public UserSummary getUser(Long id) {
        log.warn("user-service unavailable; skipping validation for user {}", id);
        return null;
    }
}
```

### The policy decision (this is the interesting part)

The fallback returns `null`. What should `OrderService` do with that?

```java
private void validateUser(Long userId) {
    try {
        if (userClient.getUser(userId) == null) {
            // Fallback fired — dependency is down. Accept the order anyway.
            log.warn("Skipping user validation for {} - user-service unavailable", userId);
        }
    } catch (FeignException.NotFound e) {
        // Service is UP and says this user genuinely doesn't exist. Reject.
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User " + userId + " does not exist");
    }
}
```

Two distinct situations, deliberately handled differently:

| Situation | Meaning | Our response |
|---|---|---|
| Fallback returns `null` | *"I don't know"* — service down | **Accept the order** |
| `FeignException.NotFound` (404) | *"I know, and no"* — user doesn't exist | **Reject the order** |

We chose **availability over strict validation**: an outage in user-service shouldn't stop the
business taking orders. The rare bad order gets caught downstream. A payment system would likely
choose the opposite. There's no universally correct answer — what matters is making the choice
consciously and being able to defend it.

### Watching it work

```bash
curl localhost:8083/actuator/circuitbreakers
```

Kill user-service, place a few orders, and watch the state flip to `OPEN`.

---

## 11. Docker

### Containers vs virtual machines

A **container** packages your application with everything it needs — runtime, libraries, config —
into one artifact that runs identically anywhere. "Works on my machine" stops being a problem.

Unlike a VM, a container **shares the host's kernel**. No guest OS, so it starts in milliseconds
and uses megabytes rather than gigabytes.

| Term | Meaning |
|---|---|
| **Image** | A read-only template (like a class) |
| **Container** | A running instance of an image (like an object) |
| **Dockerfile** | The recipe for building an image |
| **Registry** | Where images are stored (Docker Hub) |
| **Volume** | Storage that survives container deletion |

### Our Dockerfiles

Every service has one, and they're **multi-stage builds**:

```dockerfile
# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl order-service -am -DskipTests clean package

# ---------- Stage 2: run ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/order-service/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Why two stages?** Stage 1 needs Maven, a full JDK, and the whole source tree — roughly 800 MB.
But to *run* a jar you only need a JRE. Stage 2 starts fresh from a small JRE image and copies in
**only the jar**. Everything from stage 1 is discarded.

Result: an image around 200 MB instead of 800 MB, containing no source code and no build tools —
smaller to ship and a smaller attack surface.

Note `-pl order-service -am`: build *this* module and the ones it depends on, not all eleven.

### `.dockerignore`

```
**/target/
.idea/
.git/
```

`COPY . .` would otherwise copy build output and Git history into the image, making it huge and
slow. Same idea as `.gitignore`.

### Docker Compose

Running ten containers by hand with the right ports, networks, and env vars is unmanageable.
**Compose** describes it all in one YAML file, and `docker compose up` starts everything.

Key pieces of our `docker-compose.yml`:

**Reusable environment (a YAML anchor):**

```yaml
x-service-env: &service-env          # define once
  EUREKA_HOST: discovery-server
  POSTGRES_HOST: postgres
  KAFKA_BOOTSTRAP: kafka:9092

services:
  order-service:
    environment:
      <<: *service-env               # reuse
      POSTGRES_DB: orderdb           # plus service-specific bits
```

**Networking — the important bit:** Compose creates a network where **the service name is the
hostname**. That's why `POSTGRES_HOST: postgres` works — `postgres` resolves to that container's
IP. No IP addresses anywhere.

**Health checks and startup order:**

```yaml
depends_on:
  postgres:
    condition: service_healthy       # wait until Postgres actually accepts connections
```

Plain `depends_on` only waits for the container to *start*, not to be *ready*. A Postgres
container is "started" seconds before it can serve queries, so services would crash on boot
without `condition: service_healthy`. The health check itself:

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U postgres"]
  interval: 5s
  retries: 10
```

**Volumes — data that survives:**

```yaml
volumes:
  - postgres-data:/var/lib/postgresql/data
```

Without this, `docker compose down` would delete your database. The named volume persists until
you explicitly run `docker compose down -v`.

**Bind-mounting the init script:**

```yaml
- ./infra/postgres/init-databases.sql:/docker-entrypoint-initdb.d/init-databases.sql:ro
```

The Postgres image runs any `.sql` in that directory on **first** initialization — which is how
our five databases get created. `:ro` mounts it read-only.

### Two compose files

| File | Contents | Use when |
|---|---|---|
| `docker-compose.yml` | Everything — infra + all 8 services | You just want it running |
| `docker-compose.infra.yml` | Kafka + Kafka UI only | You run services in IntelliJ against your local Postgres |

The second exists because rebuilding eight images every time you change a line of code is painful.
During development you want infrastructure in Docker and your code in the IDE with a debugger.

### The Kafka listener puzzle (worth understanding)

In `docker-compose.infra.yml`:

```yaml
KAFKA_LISTENERS: HOST://:9092,CONTROLLER://:9093,INTERNAL://:29092
KAFKA_ADVERTISED_LISTENERS: HOST://localhost:9092,INTERNAL://kafka:29092
```

Kafka tells clients where to reach it — and the right answer **depends on who's asking**:

- Your app in IntelliJ (on the host) must connect to `localhost:9092`
- Kafka UI (in Docker) must connect to `kafka:29092`

One advertised address can't satisfy both, so we define two listeners. This trips up almost
everyone the first time they run Kafka in Docker.

### Everyday commands

| Command | What it does |
|---|---|
| `docker compose up --build` | Build images and start everything |
| `docker compose up -d` | Start in the background |
| `docker compose ps` | What's running |
| `docker compose logs -f order-service` | Follow one service's logs |
| `docker compose down` | Stop and remove containers |
| `docker compose down -v` | ...and delete volumes (wipes the database) |

---

## 12. Security

This is the chapter with the most decisions per line of code in the project. Authentication is
mostly plumbing; **authorization is design**, and almost every real-world breach in this category
comes from getting the design wrong rather than the crypto.

### Two words that get confused constantly

- **Authentication** — *who are you?* Proving identity. Logging in.
- **Authorization** — *what may you do?* Deciding whether this identity may perform this action.

They fail differently, and the HTTP codes say which is which:

| Code | Meaning | Cause |
|---|---|---|
| `401 Unauthorized` | "I don't know who you are" | No token, expired token, bad signature |
| `403 Forbidden` | "I know who you are, and no" | Valid token, insufficient role |

(The names are historically backwards — `401` is really "unauthenticated". Everyone lives with it.)

---

### Why not sessions?

The traditional approach: the server creates a session, stores it in memory, and hands the browser
a cookie holding the session id. Every request looks the session up.

That works for a monolith. With eight services it breaks in two ways:

1. **Where does the session live?** If it's in one service's memory, only that service can
   authenticate. So you add Redis — now every request from every service does a network round trip
   before doing any work, and Redis becomes a component whose failure takes down all eight
   services at once.
2. **Sticky sessions.** The alternative is pinning each user to one instance, which fights against
   the thing microservices exist for: scaling and replacing instances freely.

### What a JWT does instead

A **JSON Web Token** carries the proof inside the request. It's three base64 chunks separated by
dots:

```
eyJhbGciOiJIUzI1NiJ9  .  eyJzdWIiOiJzdXJ5YSIsInVpZCI6Miwicm9sZXMiOlsiQ1VTVE9NRVIiXX0  .  4pE7f...
     header                              payload (the claims)                            signature
```

Decode the middle one and you get our claims:

```json
{
  "iss": "food-delivery-auth",   // who issued it
  "sub": "surya",                // subject: the username
  "uid": 2,                      // our custom claim: the account id
  "roles": ["CUSTOMER"],         // our custom claim: what they may do
  "iat": 1723526400,             // issued at
  "exp": 1723533600              // expires (2 hours later)
}
```

**The critical thing to understand: a JWT is signed, not encrypted.** Anyone holding it can read
every claim — try it: `echo $TOKEN | cut -d. -f2 | base64 -d`. That is not a bug, and it means you
must never put anything secret in a token.

What the signature buys you is **integrity**. The server computes
`HMAC-SHA256(header + "." + payload, secret)` and appends it. Change `"roles":["CUSTOMER"]` to
`"roles":["ADMIN"]` and the payload no longer matches the signature — and you can't compute a new
one without the secret. Any service can verify this locally, with no database and no network call.
That property is the entire reason this scales across eight services.

There's a test for exactly this attack in `TokenServiceTest.tamperedTokenIsRejected` — it
re-encodes the payload with the role swapped, leaves the original signature attached, and asserts
the decoder throws.

### The cost: you can't take a JWT back

Because nothing is stored server-side, **nothing can be revoked**. Disable an account and any
token already issued keeps working until it expires. This is the genuine trade-off of stateless
auth, and the honest way to talk about it in an interview.

Our mitigation is a short life: `access-token-ttl: 2h`. That bounds how long a stolen token is
useful. The full answer is short access tokens plus longer-lived **refresh tokens** that *are*
stored and can be revoked — reintroducing shared state, but only on the rare refresh path rather
than on every request. Not implemented here, and listed as a known limitation.

---

### The pieces

```
                      ┌──────────────────────────────────────────────┐
   POST /api/auth/login                                              │
   {"username","password"}                                           │
          │                                                          │
          ▼                                                          │
   ┌─────────────┐   bcrypt compare    ┌──────────┐                  │
   │ auth-service│◄────────────────────│  authdb  │                  │
   │             │                     └──────────┘                  │
   │  SIGNS the  │                                                   │
   │  token      │──────► JWT ──────► client stores it               │
   └─────────────┘                          │                        │
                                            │ Authorization: Bearer  │
                                            ▼                        │
   ┌──────────────┐  verifies signature + issuer + expiry            │
   │ api-gateway  │  then: route-level role rules                    │
   └──────┬───────┘                                                  │
          │ forwards the SAME token                                  │
          ▼                                                          │
   ┌──────────────┐  verifies AGAIN, then @PreAuthorize + ownership  │
   │ order-service│──────────────────────────────────────────────────┘
   └──────────────┘
```

Only **auth-service signs**. Everyone else only verifies.

---

### Layer 1: the gateway

`api-gateway/.../GatewaySecurityConfig.java`. The gateway runs on WebFlux, so it uses
`SecurityWebFilterChain` rather than the servlet `SecurityFilterChain`, but the rules read the same:

```java
.authorizeExchange(exchange -> exchange
    .pathMatchers(PUBLIC_PATHS).permitAll()
    .pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()

    .pathMatchers(HttpMethod.GET, "/api/restaurants/**").authenticated()
    .pathMatchers("/api/restaurants/**")
        .hasAnyRole("RESTAURANT_OWNER", "ADMIN")

    .pathMatchers(HttpMethod.PUT, "/api/deliveries/*/status")
        .hasAnyRole("DELIVERY_AGENT", "ADMIN")

    .pathMatchers("/api/payments/**").hasRole("ADMIN")

    .anyExchange().authenticated())      // default deny
```

**Order matters.** These are evaluated top to bottom, first match wins. The `GET
/api/restaurants/**` rule sits *above* the general `/api/restaurants/**` rule, so browsing is open
to any signed-in user while writing needs the owner role. Swap those two lines and browsing
silently becomes owner-only.

**`.anyExchange().authenticated()` is the most important line in the file.** It makes the default
*deny*. Any route added tomorrow requires a token today, without anyone having to remember. The
opposite default — everything open unless listed — means every new endpoint is a potential leak
waiting for someone to notice.

#### Why `discovery.locator` had to be turned off

This is a nice example of a convenience feature becoming a security hole. Chapter 6 described
`discovery.locator`, which automatically exposes every registered service at `/<service-id>/**`.

Look at the rules above: they match on `/api/...` paths. With the locator enabled, a signed-in
customer blocked from `POST /api/restaurants` could simply post to
`/restaurant-service/api/restaurants` — a completely different path, matching none of the role
rules, falling through to `anyExchange().authenticated()`, and succeeding.

**An auto-generated route is an auto-generated hole.** The locator is now `enabled: false` and
every route is declared explicitly, including narrow `/docs/<service>/v3/api-docs/**` routes for
Swagger. The gateway's attack surface is exactly what's written in its config, which is the only
kind of attack surface you can reason about.

(Note the doc routes are scoped to the api-docs path, not `/docs/<service>/**` — otherwise
`/docs/user-service/api/users` would have been a second bypass of the same shape. Getting this
wrong twice in one file would be easy.)

---

### Layer 2: every service, again

`common-security/.../ResourceServerAutoConfiguration.java` gives every servlet service the same
baseline: stateless, no login form, default-deny.

**"But the gateway already checked!"** — yes, and it's still not enough. The gateway is the front
door, not the only door. Anything that can reach a service's port directly bypasses it entirely:

- a misconfigured Kubernetes network policy
- another compromised container on the same network
- a developer's `kubectl port-forward`
- someone hitting `localhost:8083` on the machine running `docker compose`

Verifying an HMAC signature is microseconds of local CPU. There's no meaningful cost to being safe
regardless of what sits in front of you. This is **defense in depth**: not paranoia, just refusing
to have exactly one thing standing between the internet and your database.

The two layers also check genuinely different things:

| | Sees | Can decide |
|---|---|---|
| **Gateway** | HTTP method + URL | "May drivers call this route at all?" |
| **Service** | Method, URL, **and the data** | "Is this particular order yours?" |

The gateway *cannot* answer the second question — it would have to load the order, which is
order-service's job and order-service's database.

#### Three annotations

```java
@EnableMethodSecurity                       // switches on @PreAuthorize
@PreAuthorize("hasRole('ADMIN')")           // on a method or a whole controller
@PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
```

`hasRole('ADMIN')` looks for the authority `ROLE_ADMIN`. Our tokens carry bare names
(`"roles": ["ADMIN"]`), and the prefix is added during conversion:

```java
JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
authorities.setAuthoritiesClaimName("roles");   // default looks at "scope"/"scp"
authorities.setAuthorityPrefix("ROLE_");        // default is "SCOPE_"
```

Both lines are needed. Leave them out and Spring looks for a `scope` claim we never write,
produces `SCOPE_*` authorities that `hasRole` never matches — and every user authenticates
successfully while holding no roles at all. Everything returns 403 and nothing looks broken.

---

### Layer 3: ownership — where roles stop being enough

Here's the thing roles fundamentally cannot express. Every customer holds `CUSTOMER`. So
"customers may read orders" either exposes **every** order to **every** customer, or none to any.
Neither is what you want.

This class of bug has a name — **IDOR**, insecure direct object reference — and it's one of the
most common serious vulnerabilities in real applications, precisely because the role check *looks*
like it's doing the job.

`OrderController.byId` is where we handle it:

```java
FoodOrder order = repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Order " + id + " not found"));
if (!CurrentUser.isSelfOrAdmin(order.getUserId())) {
    throw new ResponseStatusException(NOT_FOUND, "Order " + id + " not found");
}
```

Three deliberate choices in six lines:

**1. `404`, not `403`.** A 403 says "this exists, but not for you" — which is information. Walk the
ids and you can count a competitor's daily order volume without reading a single one. To a caller
who may not see a resource, the honest answer is that it isn't there. Note the message is
byte-identical in both branches; a differing message would leak the same fact.

**2. Identity from the token.** `CurrentUser.isSelfOrAdmin` compares against the `uid` claim of a
signature-verified token, not against anything the caller sent.

**3. Admins pass.** One predicate, used everywhere, so the admin exception can't be forgotten in
one place and applied in another.

And for the list endpoint:

```java
if (CurrentUser.isAdmin()) {
    return repository.findAll();
}
return repository.findByUserId(requireAccountId());   // scoped in the QUERY
```

Filtering after `findAll()` would produce the same JSON, so it looks equivalent — and it isn't.
Every other customer's order would be loaded into memory first, where it can surface in a stack
trace, a debug log, a serialization bug, or a heap dump. **Push the authorization rule into the
`WHERE` clause.** `OrderAuthorizationTest.listingIsScopedToTheCaller` asserts `findByUserId` was
called and `findAll` was not, precisely so a later refactor can't quietly regress this.

#### The id-collision bug this design avoids

Ownership checks compare ids, which only works if ids mean the same thing everywhere.

Originally every service generated its own: `auth_users.id` from one sequence, `users.id` from
another. Both start at 1. So a token saying "you are account 7" and an order row saying "user 7"
referred to **different people** — and `isSelfOrAdmin` would compare them and sometimes return
true, by coincidence.

An authorization rule that is *usually* wrong is easy to spot. One that is *occasionally* right by
accident is the worst possible failure mode.

The fix: **identity has exactly one source.** auth-service mints the account id; user-service
stores profiles under that same id (`@Id` with no `@GeneratedValue`). One number, one meaning,
system-wide.

---

### Passwords

`auth-service/.../AuthSecurityConfig.java`:

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

**Never store passwords.** Store a **hash** — a one-way function. Verifying means hashing what was
submitted and comparing.

**bcrypt specifically**, not SHA-256, and the reason is counterintuitive: bcrypt is *deliberately
slow*, and includes a per-password random **salt**. Modern hardware computes billions of SHA-256
hashes per second, so a fast hash is a fast attack. bcrypt's work factor makes each guess cost
milliseconds, turning a few hours of brute-forcing into years. The salt means two users with the
same password get different hashes, so one crack doesn't crack the other, and precomputed rainbow
tables are useless.

**Why the *delegating* encoder** rather than `new BCryptPasswordEncoder()`? It stores the algorithm
in the hash itself:

```
{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
 ^^^^^^^^
```

That prefix is a migration path. The day bcrypt needs replacing, new passwords use the new
algorithm and existing users still log in — Spring reads the prefix and picks the right verifier.
Without it, changing algorithms means forcing a password reset on your entire user base.

#### Two details in `AuthService.authenticate` that look like over-engineering

```java
AuthUser user = repository.findByUsername(username).orElse(null);

String hashToCheck = user != null ? user.getPasswordHash() : DUMMY_HASH;
boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCheck);

if (user == null || !passwordMatches) {
    throw new ResponseStatusException(UNAUTHORIZED, "Invalid username or password");
}
```

**One message for both failures.** "No such user" versus "wrong password" turns login into a
**username oracle**: an attacker submits a wordlist with any password and harvests every valid
account before trying to guess a single one. Those accounts then feed credential-stuffing against
leaked password dumps.

**The hash comparison runs even when the user doesn't exist.** Otherwise the two paths take
measurably different times — bcrypt is intentionally slow, remember — and a **timing attack**
recovers the same information the message was carefully hiding. `DUMMY_HASH` exists purely to burn
the same CPU.

`AuthServiceTest.failuresAreIndistinguishable` asserts both responses match, status and message.

---

### Privilege escalation at registration

The registration endpoint accepts a roles list. The naive implementation trusts it — and that is
privilege escalation in one line:

```bash
curl -X POST /api/auth/register -d '{"username":"me","password":"...","roles":["ADMIN"]}'
```

`AuthService.SELF_SERVICE_ROLES` is the guard:

```java
private static final Set<Role> SELF_SERVICE_ROLES =
        EnumSet.of(Role.CUSTOMER, Role.RESTAURANT_OWNER, Role.DELIVERY_AGENT);
```

`ADMIN` is absent. Requesting it gets a 403 and writes nothing —
`AuthServiceTest.rejectsSelfAssignedAdmin` asserts `repository.save` was never called, because
"returns an error" and "changes nothing" are different claims and only the second one matters.

Which creates a **chicken-and-egg problem**: granting `ADMIN` requires being one, and a fresh
database has none. Hence `BootstrapAdmin`, an `ApplicationRunner` that seeds exactly one admin
when the table contains none. It's keyed on "does any admin exist" rather than "does this username
exist", so renaming the bootstrap account doesn't silently create a second one — and it's guarded
by a property that should be off anywhere real, because a hard-coded starting password is a
well-known way for demo systems to get owned.

---

### Relaying tokens between services — and the bug that hides here

order-service calls user-service and restaurant-service through Feign. Those services default-deny,
so the outbound call needs a token or every order fails with a 401 *from a dependency*.

`BearerTokenRelayInterceptor` copies the caller's token onto outbound requests:

```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
if (authentication instanceof JwtAuthenticationToken token) {
    template.header(AUTHORIZATION, "Bearer " + token.getToken().getTokenValue());
}
```

**Now the subtle part, and the single best story in this project.**

Chapter 10 explained that Resilience4j runs a guarded call on a **separate thread** so its time
limiter can abandon one that hangs — you cannot time out work happening on the thread that's doing
the waiting.

`SecurityContextHolder` stores the principal in a **`ThreadLocal`**.

Put those together: the call hops to a pool thread, the `ThreadLocal` is empty there, the
interceptor finds no authentication, the header is never added, and the downstream service returns
401. The symptom is maddening — **authentication works perfectly until you switch circuit breakers
on**, and neither feature looks broken on its own.

The fix, in `FeignTokenRelayAutoConfiguration`:

```java
ExecutorService delegate = Executors.newCachedThreadPool();
return factory -> factory.configureExecutorService(
        new DelegatingSecurityContextExecutorService(delegate));
```

`DelegatingSecurityContextExecutorService` captures the context at submit time, installs it on the
worker thread, and — importantly — **clears it afterwards**. That last part is not optional: a
pooled thread will serve a different user next, and a leaked context would authorize their request
as the previous one.

The lazy alternative is `spring.cloud.circuitbreaker.resilience4j.disable-threadpool=true`, keeping
the call on the request thread. It works, and it silently discards the timeout — the protection the
circuit breaker was added for.

**A note on the relay approach.** Passing the *end user's* token means downstream services see who
is really behind the call and can apply their own ownership rules. The cost is that order-service
acts with that user's full authority for the token's lifetime. The stricter pattern is **token
exchange**: swap the user's token for a narrowly-scoped one ("may read user 7, nothing else").
Worth naming if asked; overkill here.

---

### Consistent error responses

Spring Security rejects requests **inside the filter chain**, before any controller or
`@RestControllerAdvice` runs. So the shared `GlobalExceptionHandler` never sees a 401 — and left
alone, a service answers a valid request with a rich JSON body and an unauthenticated one with an
empty 401. Clients would need two parsers.

`SecurityErrorHandlers` implements both `AuthenticationEntryPoint` (401) and `AccessDeniedHandler`
(403), writing the same `ApiError` shape as everything else. The messages stay deliberately vague —
"Authentication required", not "signature invalid" or "expired at 14:02". Someone who isn't
authenticated hasn't earned a debugging aid, and precise failure reasons help an attacker work out
what to try next.

The gateway needs a reactive version of the same thing, and builds its JSON by hand rather than
reusing `ApiError`. That's not laziness: `ApiError` lives in common-web, common-web pulls in
`spring-boot-starter-web`, and putting that on the gateway's classpath makes Spring Boot decide it's
a servlet application — which stops Spring Cloud Gateway working at all. Twenty lines of
duplication is the cheaper side of that trade, and the comment in the file says so.

---

### The shared secret, and what's wrong with it

Every service configures the same value:

```yaml
security:
  jwt:
    secret: ${JWT_SECRET:dev-only-jwt-secret-change-me-before-deploying-anywhere}
    issuer: food-delivery-auth
    access-token-ttl: 2h
```

**HS256 is symmetric** — one key both signs and verifies. That's why setup is a single environment
variable, and it's also the weakness: every service holds a key that could *mint* tokens, so
compromising any one of them means being able to forge an admin token for all of them.

**The production answer is RS256**, an asymmetric pair. auth-service keeps the private key and is
the only thing that can sign; everyone else fetches a **public** key from a JWKS endpoint and can
only verify. Compromising a service then yields nothing useful. It's a decoder change — the claims,
the role mapping, and every authorization rule stay identical — which is exactly why it's worth
naming as future work rather than pretending it's done.

Two smaller details in `JwtProperties`:

- **The secret must be ≥ 32 characters**, enforced with a startup failure. HS256 needs a 256-bit
  key; a short one weakens every signature in the system. Failing loudly at startup beats running
  insecurely.
- **The issuer is verified**, not just the signature (`JwtValidators.createDefaultWithIssuer`). A
  token from some other system that happens to share a key is still rejected.

---

### What's deliberately not here

Naming these is not a weakness — being unable to name them would be.

| Missing | Why it matters | The fix |
|---|---|---|
| Refresh tokens / revocation | Disabling an account doesn't kill live tokens | Short access + revocable refresh tokens |
| Rate limiting on login | Nothing slows credential stuffing | Bucket4j at the gateway; lockout after N failures |
| HTTPS | Bearer tokens over plaintext are readable in transit | TLS terminating at the gateway |
| RS256 / JWKS | Every service can forge tokens | Asymmetric keys |
| Audit logging | No record of who changed what | Structured audit events on privileged actions |
| MFA | Password alone is one factor | TOTP on login |

Bearer tokens over plain HTTP are only acceptable here because everything is on `localhost`.
Anywhere else, that's the first thing to fix.

---

## 13. The frontend

A web UI lives in `frontend/`. Its job is to make the backend's behaviour **visible** — the role
model, the ownership rules, and the event chain are all much easier to believe when you can watch
them happen.

### Why there's no build step

Most frontends are compiled. You write `.tsx` or `.vue`, a bundler turns it into plain
HTML/CSS/JS, and the browser runs the output — the same shape as `javac` turning `.java` into
`.class` files.

This one skips that: the files written are the files the browser runs. No `npm install`, no
`node_modules`, no lockfile, nothing to be on the wrong version of.

That's a deliberate trade, not a shortcut:

| | With a build tool (React/Vite) | Here |
|---|---|---|
| Deploy artifact | A folder of static files | The same folder of static files |
| To produce it | Install ~300 MB of packages, run a build | Nothing |
| Failure modes | Node version, dependency conflicts, lockfile drift | None — there's no build to fail |
| Dockerfile | Two stages: build, then serve | One stage: serve |
| Familiar to reviewers | Very | Less so |

What's given up is real: React's component model earns its keep on a large app, and "React" is
what most job listings say. What's gained is that this runs, unchanged, on any machine with a
browser — which for a project whose point is the backend is the better trade.

Everything below applies to either choice.

### The shape of it

```
frontend/
├── index.html        the only page — everything else swaps out inside it
├── env.js            the ONE file that differs per environment
├── styles.css        design tokens, light + dark
├── nginx.conf        serves the files and proxies /api
├── Dockerfile
└── js/
    ├── app.js        routes, navigation, session lifecycle
    ├── api.js        every HTTP call in the app
    ├── auth.js       token storage and role helpers
    ├── router.js     hash routing
    ├── ui.js         escaping, toasts, error translation
    └── views/        one file per screen
```

It's a **single-page application**: one HTML document, and JavaScript swaps the contents of
`<main>` as you navigate. No page reloads, so the token in memory and the app's state survive
moving between screens.

### Hash routing, and why

The URL for the orders page is `#/orders`, not `/orders`.

Everything after `#` is **never sent to the server** — it's the fragment identifier, historically
for jumping to an anchor. That means refreshing on `#/orders` requests `/`, which exists, and the
router reads the fragment and renders the right screen.

With real paths (`/orders`) a refresh sends `GET /orders` to nginx, which has no such file and
returns 404. Fixable with a rewrite rule, but that's server configuration the frontend would then
depend on. The hash needs none.

### One place for HTTP

Every call in the app goes through one function in `api.js`, which guarantees two things:

```js
const token = getToken();
if (token) headers["Authorization"] = `Bearer ${token}`;
```

**The token is always attached.** Not "attached wherever someone remembered".

```js
if (response.status === 401) {
    clearSession();
    window.dispatchEvent(new CustomEvent("session-expired"));
}
```

**A 401 always ends the session.** Whatever the app is showing after a 401 is stale, so it clears
the token and redirects rather than leaving the user clicking buttons that silently fail.

One subtlety: `fetch()` only rejects on genuine network failure — service down, DNS, a CORS
refusal. **An HTTP 500 is a *successful* fetch.** Code that only uses `.catch()` treats every
server error as a success, which is a classic bug; the wrapper checks `response.ok` explicitly.

### Roles in the UI are cosmetics

`auth.js` decodes the JWT and exposes `isAdmin()`, `isDriver()` and friends, and the navigation is
built from them: sign in as a driver and the app is a delivery board; sign in as an owner and it's
a menu editor.

**None of that is security, and it cannot be.** Anyone can open devtools and set their own roles,
or skip the page entirely and use curl. If `auth.js` were deleted, nothing would become insecure —
the app would just offer buttons that come back 403.

The client's job is to **avoid offering actions that will fail**. The server's job is to refuse
them. Confusing those two is how "we check the role in the frontend" becomes a breach.

You can watch the real boundary hold: sign in as a customer, and the Admin tab is absent — but put
`#/admin` in the address bar and the page loads, then every request on it returns 403 from the
gateway. The UI guard was bypassed; the actual one wasn't.

The token is decoded here with plain `atob` and no verification, which is fine for the same
reason: nothing security-relevant depends on the result. The server verifies the signature on
every request.

### Escaping, and why it matters more than usual

Every value from the server goes through `esc()` before landing in HTML.

Restaurant names, usernames and addresses are user-supplied. Interpolating them raw into
`innerHTML` is **stored XSS**: register as `<img src=x onerror="...">` and that script runs in the
browser of every admin who opens the accounts table. And because the token is in `localStorage`,
the first thing such a script would take is the session.

Which is the honest counterweight to the storage choice. `localStorage` is readable by any script
on the page; an `httpOnly` cookie isn't, but rides along on every request and so needs CSRF
protection that bearer tokens otherwise avoid. Neither is free. The mitigation taken here is
escaping everything and keeping tokens short-lived.

### Making eventual consistency visible

The order detail screen polls while an order is still moving, and draws a timeline:

```
PLACED ──── PAID ──── ASSIGNED ──── OUT_FOR_DELIVERY ──── DELIVERED
```

This is the screen worth demoing. `POST /api/orders` returns immediately with `PLACED` — payment,
driver assignment and the status updates all happen afterwards, as Kafka events move between four
services. So the UI watches the status converge instead of pretending the work finished with the
request.

That convergence is **eventual consistency** (section 8), and this is what it looks like from the
outside. Polling is the crude way to show it; a production app would push updates over WebSockets
or SSE rather than asking every two seconds. It stops after a minute — if nothing has moved by
then a consumer is down, and hammering the endpoint forever helps nobody.

### The CORS problem, and how to not have it

A browser will not let a page on `localhost:3000` read a response from `localhost:8080` unless the
server explicitly permits it. That's the **same-origin policy**, and it protects the *user*, not
the server: without it, any site you visited could read your responses from every API you happen
to be logged into.

The usual fix is to configure CORS. The better fix is to have no cross-origin request:

```
browser → nginx :3000 ─┬─ /          → static files
                       └─ /api/**    → api-gateway:8080   (server-side, invisible to the browser)
```

nginx serves the app **and** forwards `/api` to the gateway. The browser only ever sees one
origin, so there's no preflight, no `Access-Control-Allow-Origin`, and nothing to misconfigure.
The problem is removed rather than managed.

CORS *is* configured on the gateway, for the other workflow — services in an IDE, frontend served
by `./frontend/serve.sh`. There the origins differ for real. Note two details in that config: the
origins are listed rather than `*`, and credentials are disabled, because authentication rides in
an `Authorization` header the app sets by hand rather than in a cookie the browser sends
automatically.

### Runtime configuration

One file differs between environments:

```js
// env.js
window.__API_BASE__ = "http://localhost:8080";   // IDE workflow
```

and the Dockerfile overwrites it:

```dockerfile
RUN echo 'window.__API_BASE__ = "";' > /usr/share/nginx/html/env.js
```

Empty means *same origin* — call `/api/orders` and let nginx proxy it.

This is worth noticing because it's exactly what a bundler makes awkward. Vite inlines
`import.meta.env` values at **build** time, so the API URL is frozen into the compiled bundle and
each environment needs its own build — which defeats the point of shipping one image. Reading it
at runtime means the same files run everywhere.

nginx also sends `Cache-Control: no-store` for that file specifically, so a browser can't keep
pointing at the previous environment's API after a redeploy.

### Running it

```bash
docker compose up --build          # everything, UI on http://localhost:3000
```

or, with the services running in an IDE:

```bash
./frontend/serve.sh                # http://localhost:3000
```

The script exists rather than "just open `index.html`" because browsers refuse to load ES modules
over `file://`. Any HTTP server solves it; that one uses the Python already on macOS, so there's
still nothing to install.

---

## 14. Shared modules

Three modules contain no runnable service — they're libraries the others depend on. Each one exists
because some contract has to be *identical* across services, and "identical" maintained by hand
drifts.

### `common-events`

The Kafka message contracts, plus topic names. Both producer and consumer need the identical class
shape, so it lives in one place:

```java
public record OrderPlacedEvent(
        Long orderId, Long userId, Long restaurantId,
        BigDecimal totalAmount, String deliveryAddress) {}
```

**A caveat worth knowing:** sharing this module means a breaking change to an event ripples into
every service. Larger organizations often use a schema registry (Avro/Protobuf) with enforced
compatibility rules instead. For a project this size, a shared module is the pragmatic choice —
but you should know why bigger systems don't do it.

### `common-web`

Every service should return errors in the same shape. Rather than copy a handler into six
services, we define it once:

```java
public record ApiError(
        Instant timestamp, int status, String error,
        String message, String path, Map<String, String> fieldErrors) {}
```

```java
@RestControllerAdvice                    // applies to every controller
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(...) { ... }   // 400 + field details

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(...) { ... }       // 404 etc.

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(...) { ... }   // 500, logged not leaked
}
```

That last handler matters for security: an unexpected exception is logged in full internally, but
the client only sees `"An unexpected error occurred"` — no stack traces, no class names, no SQL.

### The auto-configuration trick

There's a catch. `@ComponentScan` only scans the package of the `@SpringBootApplication` class and
below. `com.surya.fooddelivery.web` is *not* under `com.surya.fooddelivery.order` — so the handler
would never be found.

The fix is Spring Boot's **auto-configuration** mechanism — the same one that configures Tomcat
and Hibernate for you:

```java
@AutoConfiguration
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration { ... }
```

registered in a file at
`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.surya.fooddelivery.web.CommonWebAutoConfiguration
```

On startup, Spring Boot reads that file from **every jar on the classpath** and applies what it
finds. So simply depending on `common-web` gives a service the error handling — no annotation, no
configuration. That's exactly how every `spring-boot-starter-*` works; you've now written one.

### `common-security`

The same trick, applied to something with higher stakes. Chapter 12 covered what it does; what's
worth noting here is *why it's a module at all*.

Security policy is the worst possible thing to copy-paste. Seven services each configuring their
own filter chain means seven chances for one to forget `.anyRequest().authenticated()`, or to
misspell a role, or to be updated in six places out of seven. So the role model, the JWT decoder,
the role-claim mapping, the default-deny filter chain, and the JSON error handlers all live in one
jar, applied by auto-configuration:

```
com.surya.fooddelivery.security.servlet.ResourceServerAutoConfiguration
com.surya.fooddelivery.security.reactive.ReactiveJwtAutoConfiguration
com.surya.fooddelivery.security.feign.FeignTokenRelayAutoConfiguration
```

The shared `Role` enum is a small thing with an outsized payoff: `hasAnyRole(Role.ADMIN.name())`
is checked by the compiler, where `hasAnyRole("ADMN")` is a typo that produces an endpoint nobody
can reach — or, in the mirror-image case, one that silently lets everyone through.

Three conditions do real work in this module:

- **`@ConditionalOnMissingBean`** on the filter chain. auth-service must expose `/login` publicly,
  so it declares its own chain and this default backs off — while still using the decoder and role
  converter beans, so it verifies tokens exactly the way its callers do. Overriding one piece
  doesn't mean re-implementing the rest.
- **`@ConditionalOnWebApplication(type = SERVLET | REACTIVE)`** splits servlet and gateway config,
  since WebFlux needs `SecurityWebFilterChain` and a `ReactiveJwtDecoder`.
- **`@ConditionalOnClass(RequestInterceptor.class)`** means the Feign token relay only appears in
  services that actually make outbound calls.

There's also a dependency subtlety worth understanding, marked in the POM. `common-web` is declared
`<optional>true</optional>` — meaning *not transitive*. If common-security pulled common-web in,
every consumer would get `spring-boot-starter-web` and Tomcat, and the api-gateway would flip from
a reactive application to a servlet one and stop working entirely. The servlet services declare
common-web themselves, so they lose nothing; the gateway is spared a dependency that would break it.

---

## 15. Testing

### Two kinds, two purposes

| | Unit test | Integration test |
|---|---|---|
| **Scope** | One class, dependencies faked | Many real components together |
| **Speed** | Milliseconds | Seconds |
| **Catches** | Logic bugs | Wiring, config, serialization bugs |
| **Example** | `OrderServiceTest` | `OrderFlowIntegrationTest` |

You need both. Unit tests alone pass while the app fails to start; integration tests alone are too
slow to run constantly and too coarse to pinpoint a bug.

### Unit testing with Mockito

**Mockito** creates fake objects that record calls and return whatever you tell them to.

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository repository;         // fake
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private UserClient userClient;

    @InjectMocks private OrderService orderService;   // real object, fakes injected
```

**Arrange, act, assert:**

```java
@Test
void computesTotal() {
    stubDependenciesAvailable();                      // arrange
    returnSavedOrderWithId(1L);

    FoodOrder order = orderService.placeOrder(1L, 1L, "221B", items);   // act

    assertThat(order.getTotalAmount()).isEqualByComparingTo("30.00");   // assert
}
```

**Verifying interactions** — sometimes the point is that something *was called*:

```java
ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
verify(kafkaTemplate).send(eq(Topics.ORDER_PLACED), eq("42"), payload.capture());

assertThat(payload.getValue()).isInstanceOfSatisfying(OrderPlacedEvent.class, event -> {
    assertThat(event.orderId()).isEqualTo(42L);
});
```

An `ArgumentCaptor` grabs the actual argument passed so you can inspect it.

And verifying something *didn't* happen — often the more important assertion:

```java
verify(repository, never()).save(any());        // nothing saved when validation fails
verify(kafkaTemplate, never()).send(any(), any(), any());
```

Note `isEqualByComparingTo("30.00")` rather than `isEqualTo`. `BigDecimal.equals` compares *scale*
too, so `30.0` ≠ `30.00`. This is a classic Java money bug. (And money is `BigDecimal`, never
`double` — `0.1 + 0.2 != 0.3` in floating point.)

### Integration testing with Testcontainers

Mocks can't catch: *does the JSON actually serialize? is the Kafka config right? does Hibernate
generate valid SQL?* For that you need the real thing.

**Testcontainers** starts real Docker containers for the duration of a test:

```java
@Container
@ServiceConnection
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

@Container
@ServiceConnection
static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
```

`@ServiceConnection` (Spring Boot 3.1+) is the nice part: it automatically points
`spring.datasource.url` and `spring.kafka.bootstrap-servers` at the containers' random ports. No
manual property wiring.

The test then exercises the real path and asserts on both sides:

```java
ResponseEntity<FoodOrder> response = postJson("/api/orders", body, FoodOrder.class);

assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

// It really landed in Postgres
FoodOrder stored = orderRepository.findById(created.getId()).orElseThrow();
assertThat(stored.getItems()).hasSize(1);

// And the event really landed on Kafka
ConsumerRecord<String, String> record =
        KafkaTestUtils.getSingleRecord(consumer, Topics.ORDER_PLACED, Duration.ofSeconds(15));
assertThat(record.key()).isEqualTo(String.valueOf(created.getId()));
```

The two downstream services are still mocked with `@MockBean` — this test is about
*order-service's* behaviour, not about running the entire system.

**Skip-if-no-Docker:**

```java
@EnabledIf("dockerAvailable")
```

Without this, the build **fails** on any machine without a running Docker daemon. With it, the
test skips and the build stays green. That's the right behaviour for a test with an external
requirement.

> **Status:** the 7 unit tests are verified passing. The 2 integration tests are written but have
> not yet been observed passing — see the note at the end of [RESUME.md](RESUME.md). Run them once
> before relying on them.

---

## 16. Configuration

### `application.yml`

Each service has one, holding all its settings. Spring reads it automatically.

### Environment variable substitution

```yaml
url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:orderdb}
```

`${VARIABLE:default}` means *"use the environment variable if set, otherwise this default."*

This single pattern is why the same jar runs everywhere:

| Context | `POSTGRES_HOST` | Resolves to |
|---|---|---|
| IntelliJ on your Mac | unset | `localhost` |
| Docker Compose | `postgres` | the Postgres container |
| Production | `db.prod.internal` | the real server |

**One image, many environments** — a core principle of [12-factor apps](https://12factor.net/).
Configuration lives in the environment, never in the build.

### Actuator

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,circuitbreakers
```

**Spring Boot Actuator** adds operational endpoints:

- `/actuator/health` — is this service healthy? (used by Docker health checks and Kubernetes)
- `/actuator/circuitbreakers` — current circuit breaker states
- `/actuator/gateway/routes` — the gateway's active routes

Note that we expose a **specific list**, not `*`. Some endpoints (`/actuator/env`,
`/actuator/heapdump`) leak sensitive data and should never be public.

---

## 17. A complete request, traced

Let's follow one order all the way through. This section ties everything together.

### The request

```bash
curl -X POST localhost:8080/api/orders \
  -H "Authorization: Bearer $CUSTOMER" -H 'Content-Type: application/json' \
  -d '{"restaurantId":1,"deliveryAddress":"221B Baker St",
       "items":[{"name":"Butter Chicken","quantity":2,"price":12.50}]}'
```

Note there is **no `userId`**. The order belongs to whoever the token says you are.

### Step 0 — Getting the token (earlier)

The customer logged in at some point beforehand. auth-service compared their password against a
bcrypt hash, then signed a JWT carrying `sub: "surya"`, `uid: 2`, `roles: ["CUSTOMER"]`, an issuer,
and a two-hour expiry. Everything below uses that one token; auth-service is not consulted again.

### Step 1 — The gateway

Request arrives at `api-gateway:8080`.

1. **Authentication.** The bearer token is verified: HMAC signature against the shared secret,
   issuer matches, not expired. Fail any of these → `401`, and nothing downstream is touched.
2. **Authorization (coarse).** `POST /api/orders/**` requires `CUSTOMER` or `ADMIN`. The token's
   `roles` claim became the authority `ROLE_CUSTOMER` during conversion, so this passes. A
   restaurant owner's token would stop here with `403`.
3. **Routing.** The path matches the `order-service` route. The gateway asks Eureka for a live
   instance and forwards the request — **including the `Authorization` header**, unchanged.

### Step 2 — order-service verifies the token again

The request hits order-service's own filter chain, which verifies the same signature, issuer, and
expiry independently. This is not redundant paranoia: it's what makes the service safe if anything
ever reaches port 8083 without going through the gateway.

`@PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")` on the method then runs before the method body.

### Step 3 — Controller, identity, and validation

`OrderController.create()` receives it. `@Valid` runs first:

- `restaurantId` not null ✓
- `items` not empty ✓
- each item's `quantity` and `price` positive ✓

*(If any failed, `GlobalExceptionHandler` would return a 400 with field-level detail and nothing
below would run.)*

Then the line that matters most for security:

```java
service.placeOrder(requireAccountId(), request.restaurantId(), ...)
```

`requireAccountId()` reads `uid` out of the **verified token** — `2`. The request body has no say
in whose order this is. The DTOs are converted into domain `OrderItem` objects.

### Step 4 — Synchronous validation, protected, authenticated

`OrderService.placeOrder()` calls `userClient.getUser(2L)`:

- Circuit is CLOSED → the call is submitted to Resilience4j's executor, which is wrapped in
  `DelegatingSecurityContextExecutorService`, so the security context travels to the worker thread
- `BearerTokenRelayInterceptor` reads that context and attaches `Authorization: Bearer ...` to the
  outbound request
- Feign asks Eureka for `user-service`, sends `GET /api/users/2`
- user-service verifies the token, allows the read, returns a `UserSummary` → user exists ✓

Same for `restaurantClient.getRestaurant(1L)`.

*(If user-service were down, the circuit breaker's fallback returns `null` and we proceed anyway.
If it returned 404, we'd throw a 400 and stop. If the executor weren't wrapped, the header would be
missing and we'd get a 401 here — the failure described in chapter 12.)*

### Step 5 — Compute and persist

```java
BigDecimal total = items.stream()
        .map(OrderItem::lineTotal)          // 2 × 12.50 = 25.00
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

The order is saved to `orderdb` with status `PLACED`. Hibernate issues an `INSERT` into `orders`
and another into `order_items`. The database assigns id `1`.

### Step 6 — Publish

```java
kafkaTemplate.send(Topics.ORDER_PLACED, "1", event);
```

Serialized to JSON, written to the `order-placed` topic, partitioned by key `"1"`.

### Step 7 — Respond

`201 Created` with the order body, status `PLACED`. **The customer's request is now finished** —
everything below happens in the background.

### Step 8 — payment-service reacts

Its `@KafkaListener` (group `payment-service`) receives the event, "charges" the card (amount > 0
→ approved), saves a `Payment` row to `paymentdb`, and publishes `payment-processed` with
`status: SUCCESS` — carrying the delivery address forward.

### Step 9 — Two services react in parallel

`payment-processed` is consumed independently by two groups:

- **order-service** → sets order 1 to `PAID`
- **delivery-service** → picks a random driver, saves to `deliverydb`, publishes `delivery-status`
  with `ASSIGNED`

### Step 10 — order-service reacts again

Consumes `delivery-status`, sets order 1 to `ASSIGNED`.

### Step 11 — notification-service

Has been listening to all three topics the whole time, and has now recorded:

```
"Your order #1 has been placed. Total: 25.00"
"Payment received for order #1."
"Order #1 is ASSIGNED (driver: Priya)."
```

### The result

```bash
curl localhost:8080/api/orders/1 -H "Authorization: Bearer $CUSTOMER"
# {"status": "ASSIGNED", ...}

curl localhost:8080/api/notifications -H "Authorization: Bearer $ADMIN"
# the three messages above  (admin-only: it returns every customer's notifications)
```

All within a second or two of a request that returned immediately. **Four services collaborated
and none of them called each other directly** (except the two validation reads). That's the whole
idea.

And note what travelled with the request: one signed token, verified four times, never once
requiring a call back to auth-service. That is what stateless authentication buys — and the reason
the same token cannot be revoked mid-flight is the price.

---

## 18. Every file in the repo

```
FoodDeliveryApp/
│
├── pom.xml                      Parent POM: modules, dependency versions, Java 21
├── mvnw, mvnw.cmd, .mvn/        Maven Wrapper — build without installing Maven
├── .dockerignore                Keeps target/ and .git/ out of Docker images
├── .gitignore                   Keeps build output out of Git
│
├── README.md                    Project overview — the "sell it" document
├── EXPLAINED.md                 This file
├── TESTING.md                   Step-by-step run & test guide (local Postgres + pgAdmin)
├── RESUME.md                    Resume bullets, interview Q&A, known gaps
│
├── docker-compose.yml           Full stack: infra + 9 services + the web UI
├── docker-compose.infra.yml     Kafka + Kafka UI only (for IDE development)
├── run-all.sh / stop-all.sh     Start/stop all services locally from built jars
│
├── infra/postgres/
│   └── init-databases.sql       Creates the 6 databases on first Postgres start
│
├── .github/workflows/ci.yml     GitHub Actions: build, test, verify images
│
├── common-events/               ── LIBRARY, no main() ──
│   └── .../events/
│       ├── Topics.java                  The 3 topic name constants
│       ├── OrderPlacedEvent.java
│       ├── PaymentProcessedEvent.java
│       └── DeliveryStatusEvent.java
│
├── common-web/                  ── LIBRARY, no main() ──
│   ├── .../web/
│   │   ├── ApiError.java                Uniform error response shape
│   │   ├── GlobalExceptionHandler.java  Turns exceptions into ApiError
│   │   └── CommonWebAutoConfiguration.java
│   └── resources/META-INF/spring/...AutoConfiguration.imports
│
├── common-security/             ── LIBRARY, no main() ──
│   ├── main/.../security/
│   │   ├── Role.java                    The 4 roles — shared so typos are compile errors
│   │   ├── JwtClaims.java               Claim name constants ("roles", "uid")
│   │   ├── JwtProperties.java           Secret/issuer/TTL + the ≥32-char guard
│   │   ├── CurrentUser.java             Reads identity from the verified token
│   │   ├── servlet/
│   │   │   ├── ResourceServerAutoConfiguration.java  Default-deny chain for all services
│   │   │   └── SecurityErrorHandlers.java            401/403 as ApiError JSON
│   │   ├── reactive/
│   │   │   └── ReactiveJwtAutoConfiguration.java     WebFlux decoder for the gateway
│   │   └── feign/
│   │       ├── BearerTokenRelayInterceptor.java      Forwards the caller's token
│   │       └── FeignTokenRelayAutoConfiguration.java Security context across the CB thread
│   ├── resources/META-INF/spring/...AutoConfiguration.imports
│   └── test/.../CurrentUserTest.java     Ownership predicate, incl. anonymous + null cases
│
├── discovery-server/            Eureka registry (:8761)
├── api-gateway/                 Routing + Swagger aggregation (:8080)
│   └── .../gateway/
│       ├── ApiGatewayApplication.java
│       ├── GatewaySecurityConfig.java        Route-level role rules, default deny
│       └── GatewaySecurityErrorHandlers.java Reactive 401/403 JSON
│
├── auth-service/                (:8087, authdb) ── the only thing that SIGNS tokens ──
│   ├── main/.../auth/
│   │   ├── AuthServiceApplication.java
│   │   ├── AuthUser.java                @Entity — username, bcrypt hash, roles
│   │   ├── AuthUserRepository.java
│   │   ├── AuthService.java             Registration guard, login, timing-safe failure
│   │   ├── TokenService.java            Mints the JWT
│   │   ├── AuthController.java          /register, /login, /me, admin role management
│   │   ├── AuthSecurityConfig.java      Its own chain: login must be public
│   │   ├── AccountAccess.java           Ownership predicate for @PreAuthorize
│   │   └── BootstrapAdmin.java          Seeds the first admin on an empty database
│   └── test/.../auth/
│       ├── AuthServiceTest.java         Hashing, escalation guard, indistinguishable failures
│       └── TokenServiceTest.java        Sign/verify round trip + tampering + wrong key/issuer
│
├── user-service/                (:8081, userdb)
│   └── .../user/
│       ├── UserServiceApplication.java
│       ├── User.java                    @Entity — id IS the auth account id, not generated
│       ├── UserRepository.java          JpaRepository
│       └── UserController.java          REST endpoints + @PreAuthorize
│
├── restaurant-service/          (:8082, restaurantdb)
│   └── Restaurant (with ownerAccountId), MenuItem, 2 repositories, RestaurantController
│
├── order-service/               (:8083, orderdb) ── the most complex service ──
│   ├── main/.../order/
│   │   ├── OrderServiceApplication.java  @EnableFeignClients
│   │   ├── FoodOrder.java                @Entity ("order" is a SQL keyword)
│   │   ├── OrderItem.java                @Embeddable
│   │   ├── OrderStatus.java              enum: PLACED → PAID → ASSIGNED → DELIVERED
│   │   ├── OrderRepository.java
│   │   ├── OrderService.java             Business logic + validation + publish
│   │   ├── OrderController.java
│   │   ├── OrderEventsListener.java      Consumes payment + delivery events
│   │   └── client/
│   │       ├── UserClient.java + UserClientFallback.java
│   │       └── RestaurantClient.java + RestaurantClientFallback.java
│   └── test/.../order/
│       ├── OrderServiceTest.java         Unit tests (Mockito)
│       ├── OrderAuthorizationTest.java   Ownership rules: 404-not-403, scoped listing
│       └── OrderFlowIntegrationTest.java Integration tests (Testcontainers), now token-signed
│
├── payment-service/             (:8084, paymentdb)
│   ├── Payment, PaymentRepository, PaymentController
│   ├── PaymentListener.java              Consumes order-placed → publishes payment-processed
│   └── test/PaymentListenerTest.java     2 unit tests
│
├── delivery-service/            (:8085, deliverydb)
│   ├── Delivery, DeliveryRepository, DeliveryController
│   ├── DeliveryService.java              Driver assignment + publishing
│   └── DeliveryListener.java             Consumes payment-processed
│
├── notification-service/        (:8086, in-memory — no database)
│   ├── NotificationStore.java            CopyOnWriteArrayList of notifications
│   ├── NotificationListener.java         Consumes ALL THREE topics
│   └── NotificationController.java
│
└── frontend/                    (:3000, nginx — no build step)
    ├── index.html                        The only page; views swap inside it
    ├── env.js                            API base URL — overwritten in the Docker image
    ├── styles.css                        Design tokens, light + dark
    ├── nginx.conf                        Serves files + proxies /api (so: no CORS)
    ├── Dockerfile                        One stage; nothing to compile
    ├── serve.sh                          Static server for the IDE workflow
    └── js/
        ├── app.js                        Routes, navigation, session lifecycle
        ├── api.js                        Every HTTP call; attaches the token, handles 401
        ├── auth.js                       Token storage + role helpers (UX only, not security)
        ├── router.js                     Hash routing
        ├── ui.js                         HTML escaping, toasts, error translation
        └── views/                        login, restaurants, orders, deliveries, profile, admin
```

**Why `FoodOrder` and not `Order`?** `ORDER` is a reserved SQL keyword (`ORDER BY`). Naming the
entity `FoodOrder` while mapping it to a quoted `orders` table avoids a category of confusing
errors.

---

## 19. Glossary

| Term | Meaning |
|---|---|
| **Authentication** | Proving *who* you are (401 when it fails) |
| **Authorization** | Deciding *what you may do* (403 when it fails) |
| **bcrypt** | A deliberately slow, salted password hash — slow is the security property |
| **Bean** | An object Spring creates and manages for you |
| **BOM** | Bill of Materials — a curated set of compatible library versions |
| **Broker** | A single Kafka server |
| **Cascading failure** | One service's failure taking down its callers, and theirs |
| **Circuit breaker** | A guard that stops calling a failing dependency |
| **Consumer group** | Kafka consumers that share work; each message goes to one member |
| **DTO** | Data Transfer Object — a shape used for transport, not persistence |
| **Eventual consistency** | Data converges to correct shortly after a change, not instantly |
| **Claim** | A key/value fact inside a JWT (`sub`, `roles`, `exp`) |
| **Default deny** | Everything is forbidden unless explicitly allowed — the safe default |
| **Defense in depth** | Multiple independent checks, so one bypass isn't a breach |
| **Fallback** | The alternative response when a call fails or the circuit is open |
| **HS256** | Symmetric JWT signing: one shared secret both signs and verifies |
| **IDOR** | Insecure Direct Object Reference — reading someone else's row by changing an id |
| **Idempotent** | Doing it twice has the same effect as doing it once |
| **JPA** | Java's API for mapping objects to database tables |
| **JWKS** | A published set of public keys, letting services verify RS256 tokens |
| **JWT** | JSON Web Token — signed (not encrypted) claims carried in the request |
| **KRaft** | Kafka's built-in consensus, replacing ZooKeeper |
| **Offset** | A consumer group's bookmark within a topic partition |
| **ORM** | Object-Relational Mapping — objects ↔ tables (Hibernate) |
| **Outbox pattern** | Writing events to a DB table in-transaction, relayed to Kafka later |
| **Partition** | A subdivision of a Kafka topic; ordering holds within one |
| **Privilege escalation** | Gaining rights you shouldn't have (e.g. self-assigning ADMIN) |
| **RBAC** | Role-Based Access Control — permissions attached to roles, not individuals |
| **Reactor build** | Maven building multiple modules in dependency order |
| **RS256** | Asymmetric JWT signing: a private key signs, a public key verifies |
| **Salt** | Random per-password data, so identical passwords hash differently |
| **Saga** | A sequence of local transactions with compensating actions for rollback |
| **Service discovery** | Finding a service's address by name at runtime |
| **Stateless auth** | No server-side session; the token carries the proof |
| **Timing attack** | Inferring secrets from how long a response takes |
| **Topic** | A named stream of Kafka messages |

---

## Where to go next

If you want to extend the project, roughly in order of value:

1. **The transactional outbox** — fixes the real correctness gap in section 8
2. **RS256 with a JWKS endpoint** — so only auth-service can mint tokens (section 12)
3. **Refresh tokens and revocation** — the other half of the stateless-auth trade-off
4. **Flyway migrations** — replace `ddl-auto: update` with versioned schema changes
5. **Idempotent consumers** — dedupe on event ID so redelivery is safe
6. **Rate limiting at the gateway** — Bucket4j on `/api/auth/login` first
7. **Distributed tracing** (Micrometer + OpenTelemetry) — follow one request across all services
8. **Kubernetes manifests** — deploy it somewhere real
