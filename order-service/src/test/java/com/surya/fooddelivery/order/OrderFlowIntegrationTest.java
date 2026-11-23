package com.surya.fooddelivery.order;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.surya.fooddelivery.events.Topics;
import com.surya.fooddelivery.order.client.RestaurantClient;
import com.surya.fooddelivery.order.client.UserClient;
import com.surya.fooddelivery.security.JwtClaims;
import com.surya.fooddelivery.security.JwtProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the order-placement path against real infrastructure:
 * a throwaway PostgreSQL and Kafka started by Testcontainers.
 *
 * <p>The two downstream services are mocked at the Feign-client boundary so this test
 * stays focused on order-service's own behaviour: persist the order, then publish the
 * event other services react to.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFlowIntegrationTest {

    /**
     * Skips (rather than fails) this class where the Docker daemon isn't reachable, so
     * {@code mvn verify} stays green on machines without Docker while still running for real in CI.
     */
    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    @ServiceConnection
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    /** Shared with the running application through {@code security.jwt.secret} below. */
    private static final String TEST_SECRET = "integration-test-secret-long-enough-for-hs256-signing";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // No service registry in tests; Feign clients are mocked out below.
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.discovery.enabled", () -> "false");
        // The application verifies tokens with this secret; the test signs them with it.
        registry.add("security.jwt.secret", () -> TEST_SECRET);
        registry.add("security.jwt.issuer", () -> "food-delivery-auth");
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private UserClient userClient;
    @MockBean
    private RestaurantClient restaurantClient;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();

        when(userClient.getUser(anyLong()))
                .thenReturn(new UserClient.UserSummary(1L, "Surya", "surya@example.com", "221B Baker St"));
        when(restaurantClient.getRestaurant(anyLong()))
                .thenReturn(new RestaurantClient.RestaurantSummary(1L, "Curry House", "Indian", "12 Spice Rd", true));

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafka.getBootstrapServers(), "test-consumer", "true");
        consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer();
        consumer.subscribe(java.util.List.of(Topics.ORDER_PLACED));
        consumer.poll(Duration.ofMillis(100)); // force partition assignment before we produce
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("POST /api/orders persists the order and publishes an order-placed event")
    void placingAnOrderPersistsAndPublishes() {
        // No userId in the body any more — the order is placed for whoever the token says you are.
        String body = """
                {
                  "restaurantId": 1,
                  "deliveryAddress": "221B Baker St",
                  "items": [{"name": "Butter Chicken", "quantity": 2, "price": 12.50}]
                }
                """;

        ResponseEntity<FoodOrder> response = postJson("/api/orders", body, FoodOrder.class, customerToken(1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        FoodOrder created = response.getBody();
        assertThat(created).isNotNull();
        assertThat(created.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(created.getStatus()).isEqualTo(OrderStatus.PLACED.name());

        // Persisted in Postgres, items included.
        FoodOrder stored = orderRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(1L);
        assertThat(stored.getItems()).hasSize(1);
        assertThat(stored.getItems().getFirst().getName()).isEqualTo("Butter Chicken");

        // Published to Kafka for payment-service and notification-service to consume.
        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(consumer, Topics.ORDER_PLACED, Duration.ofSeconds(15));
        assertThat(record.key()).isEqualTo(String.valueOf(created.getId()));
        assertThat(record.value())
                .contains("\"orderId\":" + created.getId())
                .contains("\"userId\":1")
                .contains("\"deliveryAddress\":\"221B Baker St\"");
    }

    @Test
    @DisplayName("POST /api/orders rejects an empty item list with 400 and a field error")
    void rejectsInvalidPayload() {
        String body = """
                {"restaurantId": 1, "deliveryAddress": "221B Baker St", "items": []}
                """;

        ResponseEntity<String> response = postJson("/api/orders", body, String.class, customerToken(1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Shared ApiError contract from common-web.
        assertThat(response.getBody()).contains("fieldErrors").contains("items");
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    @DisplayName("POST /api/orders without a token is rejected before anything is written")
    void rejectsUnauthenticatedRequest() {
        String body = """
                {"restaurantId": 1, "deliveryAddress": "221B Baker St",
                 "items": [{"name": "Butter Chicken", "quantity": 2, "price": 12.50}]}
                """;

        ResponseEntity<String> response = postJson("/api/orders", body, String.class, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    @DisplayName("a restaurant owner's token is refused: placing orders is a customer action")
    void rejectsWrongRole() {
        String body = """
                {"restaurantId": 1, "deliveryAddress": "221B Baker St",
                 "items": [{"name": "Butter Chicken", "quantity": 2, "price": 12.50}]}
                """;

        ResponseEntity<String> response =
                postJson("/api/orders", body, String.class, tokenFor(1L, "RESTAURANT_OWNER"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(orderRepository.count()).isZero();
    }

    /** Signs a token the running application will accept, exactly as auth-service would. */
    private String tokenFor(long accountId, String role) {
        JwtProperties properties = new JwtProperties(TEST_SECRET, "food-delivery-auth", Duration.ofHours(1));
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(properties.secretKey()));

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject("account-" + accountId)
                .claim(JwtClaims.USER_ID, accountId)
                .claim(JwtClaims.ROLES, java.util.List.of(role))
                .build();

        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String customerToken(long accountId) {
        return tokenFor(accountId, "CUSTOMER");
    }

    private <T> ResponseEntity<T> postJson(String path, String body, Class<T> responseType, String bearerToken) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.postForEntity(path, new org.springframework.http.HttpEntity<>(body, headers), responseType);
    }
}
