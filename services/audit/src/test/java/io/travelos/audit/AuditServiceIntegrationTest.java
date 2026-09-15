package io.travelos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.travelos.audit.store.AuditRepository;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.web.testing.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real Kafka in, real Postgres, real HTTP out. The contract examples are the fixtures: every event
 * type the platform declares is replayed through the consumer and must come back in the trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditServiceIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  /** The trip every contract example refers to; alice (emp_1001) is its traveler. */
  private static final String TRIP = "trip_01K4Q0N7S6Z2X8G5H3J9M1P7RB";

  @Autowired Environment environment;
  @Autowired KafkaConnectionDetails kafka;
  @Autowired JdbcClient jdbc;
  @Autowired AuditRepository repository;

  private final JsonMapper json = JsonMapper.builder().build();
  private RestClient http;
  private KafkaProducer<String, String> producer;
  private Instant clock = Instant.parse("2026-09-09T20:00:00Z");

  @BeforeAll
  void setUp() throws Exception {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
    String bootstrap = String.join(",", kafka.getBootstrapServers());
    Properties admin = new Properties();
    admin.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    try (AdminClient client = AdminClient.create(admin)) {
      client
          .createTopics(Topics.ALL.stream().map(t -> new NewTopic(t, 1, (short) 1)).toList())
          .all()
          .get();
    }
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producer = new KafkaProducer<>(props);
  }

  @AfterAll
  void tearDown() {
    producer.close();
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void everyDeclaredEventTypeIsStoredOnceInOrder() throws Exception {
    // A full life, in order, using the contract examples as the fixtures (fresh ids, increasing
    // time).
    List<String> life =
        List.of(
            "travel.trip.created",
            "travel.intent.detected",
            "travel.policy.evaluated",
            "travel.policy.violation",
            "travel.optimization.completed",
            "travel.trip.planned",
            "travel.approval.requested",
            "travel.approval.approved",
            "travel.order.created",
            "travel.order.confirmed",
            "travel.trip.booked");
    String created = null;
    for (String type : life) {
      String payload = example(type);
      if (created == null) {
        created = payload;
      }
      publish(type, payload);
    }
    // Redelivery: the same created event again, byte for byte.
    publish("travel.trip.created", created);
    // Poison: not an envelope at all.
    producer.send(new ProducerRecord<>(Topics.TRIP, TRIP, "{\"not\":\"an envelope\"}")).get();
    producer.flush();

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              assertThat(repository.trail(TenantId.of("acme"), TRIP)).hasSize(life.size());
              assertThat(repository.quarantined()).isEqualTo(1);
            });

    ResponseEntity<String> trail = get("/api/v1/audit/trips/" + TRIP, TestTokens.alice());
    assertThat(trail.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json.readTree(trail.getBody());
    assertThat(body.get("travelerId").asString()).isEqualTo("emp_1001");
    List<String> types = new java.util.ArrayList<>();
    body.get("events").forEach(e -> types.add(e.get("eventType").asString()));
    assertThat(types).as("stored once each, in the order they happened").isEqualTo(life);
    assertThat(body.get("events").get(0).get("producer").asString()).isEqualTo("travel-core");
    assertThat(body.get("events").get(0).get("data").get("source").asString()).isEqualTo("WEB");
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void theLedgerAnswersWhyFromTheTrailAlone() {
    ResponseEntity<String> response =
        get("/api/v1/audit/trips/" + TRIP + "/decisions", TestTokens.alice());
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    JsonNode ledger = json.readTree(response.getBody());
    assertThat(ledger.get("status").asString()).isEqualTo("BOOKED");
    assertThat(ledger.get("eventCount").asInt()).isEqualTo(11);
    assertThat(ledger.get("intent").get("model").asString()).isEqualTo("claude-opus-5");
    assertThat(ledger.get("policy").get("outcome").asString()).isEqualTo("ALLOW_WITH_APPROVAL");
    assertThat(ledger.get("policy").get("candidatesEvaluated").asInt()).isEqualTo(1);
    assertThat(ledger.get("policy").get("candidatesDenied").asInt()).isEqualTo(1);
    assertThat(ledger.get("optimization").get("solver").asString()).isEqualTo("ortools-cpsat-9.15");
    assertThat(ledger.get("approval").get("status").asString()).isEqualTo("APPROVED");
    assertThat(ledger.get("approval").get("decidedBy").asString()).isEqualTo("human/bob");
    assertThat(ledger.get("order").get("status").asString()).isEqualTo("CONFIRMED");
    assertThat(ledger.get("order").get("externalOrderId").asString()).isEqualTo("SBX-4F9K2Q");
    List<String> narrative = new java.util.ArrayList<>();
    ledger.get("narrative").forEach(n -> narrative.add(n.asString()));
    assertThat(narrative)
        .hasSize(6)
        .satisfies(
            n -> {
              assertThat(n.get(0)).contains("understood from free text by claude-opus-5");
              assertThat(n.get(1))
                  .contains("US_STANDARD_TRAVEL v12")
                  .contains("ALLOW_WITH_APPROVAL");
              assertThat(n.get(2)).contains("ortools-cpsat-9.15").contains("91.4");
              assertThat(n.get(3)).contains("Approval was required").contains("USD 820.00");
              assertThat(n.get(4)).contains("approved by human/bob");
              assertThat(n.get(5)).contains("confirmed at sandbox-air").contains("SBX-4F9K2Q");
            });
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void accessMirrorsTravelCore() {
    assertThat(get("/api/v1/audit/trips/" + TRIP, TestTokens.dan()).getStatusCode().value())
        .as("another traveler in the tenant")
        .isEqualTo(404);
    assertThat(get("/api/v1/audit/trips/" + TRIP, TestTokens.zoe()).getStatusCode().value())
        .as("another tenant")
        .isEqualTo(404);
    assertThat(get("/api/v1/audit/trips/" + TRIP, TestTokens.bob()).getStatusCode().value())
        .as("a manager")
        .isEqualTo(200);
    assertThat(
            get("/api/v1/audit/trips/trip_01K4Q0N7S6Z2X8G5H3J9M1P7RZ", TestTokens.alice())
                .getStatusCode()
                .value())
        .as("unknown trip")
        .isEqualTo(404);
    assertThat(get("/api/v1/audit/trips/" + TRIP, null).getStatusCode().value()).isEqualTo(401);

    ResponseEntity<String> asAdmin =
        get("/api/v1/audit/events?type=travel.order.confirmed", TestTokens.carol());
    assertThat(asAdmin.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(asAdmin.getBody())).hasSize(1);
    assertThat(
            get("/api/v1/audit/events?type=travel.order.confirmed", TestTokens.alice())
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            json.readTree(
                get(
                        "/api/v1/audit/events?type=travel.order.confirmed",
                        TestTokens.user("zed", "globex", "emp_9", List.of("FINANCE")))
                    .getBody()))
        .as("tenant-scoped even for auditors")
        .isEmpty();
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void historyCannotBeRewrittenEvenByTheOwnerRole() {
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE audit_event SET producer = 'attacker' WHERE correlation_id = :t")
                    .param("t", TRIP)
                    .update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM audit_event WHERE correlation_id = :t")
                    .param("t", TRIP)
                    .update())
        .hasMessageContaining("append-only");
    assertThat(repository.trail(TenantId.of("acme"), TRIP)).hasSize(11);
  }

  // ---------------------------------------------------------------- helpers

  /** The contract example for a type, with a fresh event id and a strictly increasing clock. */
  @Test
  @org.junit.jupiter.api.Order(5)
  void slice3EventsAreLedgeredAsReplansComponentsAndCompensation() throws Exception {
    // a stale approval, a failed compensation and its resolution, all on the same trip
    for (String type :
        List.of(
            "travel.trip.replanned",
            "travel.order.compensation-failed",
            "travel.order.exposure-resolved")) {
      publish(type, example(type));
    }
    producer.flush();
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () ->
                assertThat(repository.trail(TenantId.of("acme"), TRIP))
                    .hasSizeGreaterThanOrEqualTo(14));
    JsonNode ledger =
        json.readTree(
            get("/api/v1/audit/trips/" + TRIP + "/decisions", TestTokens.alice()).getBody());
    assertThat(ledger.get("replans")).hasSize(1);
    assertThat(ledger.get("replans").get(0).get("reason").asString()).isEqualTo("PRICE_CHANGED");
    assertThat(ledger.get("compensation").get("open").asLong()).isZero();
    JsonNode exposure = ledger.get("compensation").get("exposures").get(0);
    assertThat(exposure.get("status").asString()).isEqualTo("RESOLVED");
    assertThat(exposure.get("resolvedBy").asString()).isEqualTo("human/carol");
    assertThat(exposure.get("amount").get("amountMinor").asLong()).isEqualTo(46800);
    List<String> narrative = new java.util.ArrayList<>();
    ledger.get("narrative").forEach(n -> narrative.add(n.asString()));
    assertThat(narrative)
        .anySatisfy(n -> assertThat(n).contains("Revalidation").contains("PRICE_CHANGED"))
        .anySatisfy(n -> assertThat(n).contains("Compensation could not release everything"))
        .anySatisfy(n -> assertThat(n).contains("resolved by human/carol"));
  }

  private String example(String type) {
    ObjectNode node = (ObjectNode) json.readTree(EventSchemas.example(type));
    clock = clock.plusSeconds(1);
    node.put("eventId", Ids.newId(IdPrefix.EVENT));
    node.put("occurredAt", clock.toString());
    String payload = json.writeValueAsString(node);
    assertThat(EventSchemas.violations(payload)).as(type).isEmpty();
    return payload;
  }

  private void publish(String type, String payload) throws Exception {
    producer.send(new ProducerRecord<>(Topics.topicFor(type), TRIP, payload)).get();
  }

  private ResponseEntity<String> get(String path, String token) {
    RestClient.RequestHeadersSpec<?> spec = http.get().uri(path);
    if (token != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return spec.retrieve().toEntity(String.class);
  }
}
