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
        .hasSize(7)
        .satisfies(
            n -> {
              assertThat(n.get(0)).contains("understood from free text by claude-opus-5");
              assertThat(n.get(1))
                  .contains("US_STANDARD_TRAVEL v12")
                  .contains("ALLOW_WITH_APPROVAL");
              assertThat(n.get(2)).contains("ortools-cpsat-9.15").contains("91.4");
              // Slice 5: the example ran learning in SHADOW mode; the ledger says so and says what
              // was executed (the baseline), never implying the learned pick was a better one.
              assertThat(n.get(3))
                  .contains("SHADOW")
                  .contains("lp_01K4Q0N7S6Z2X8G5H3J9M1P7RF")
                  .contains("baseline ranking was executed")
                  .contains("recorded, not applied");
              assertThat(n.get(4)).contains("Approval was required").contains("USD 820.00");
              assertThat(n.get(5)).contains("approved by human/bob");
              assertThat(n.get(6)).contains("confirmed at sandbox-air").contains("SBX-4F9K2Q");
            });
    assertThat(ledger.get("learning").get("mode").asString()).isEqualTo("SHADOW");
    assertThat(ledger.get("learning").get("applied").asBoolean()).isFalse();
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

  // ------------------------------------------------------------------ Phase 9: reports

  @Test
  @org.junit.jupiter.api.Order(6)
  void reportsSumWhatTheEventsSaidByCostCenterAndCountTheExceptions() throws Exception {
    // a second trip: created with an allocation, booked, paid, partly refunded, disrupted and
    // recovered at a cost, a case opened and resolved, an approval escalated
    String trip = "trip_01K4Q0N7S6Z2X8G5H3J9M1P7RZ";
    java.util.function.BiFunction<String, java.util.function.Consumer<ObjectNode>, String> ev =
        (type, edit) -> {
          ObjectNode node = (ObjectNode) json.readTree(EventSchemas.example(type));
          clock = clock.plusSeconds(60);
          node.put("eventId", Ids.newId(IdPrefix.EVENT));
          node.put("occurredAt", clock.toString());
          node.put("correlationId", trip);
          ((ObjectNode) node.get("data")).put("tripId", trip);
          edit.accept((ObjectNode) node.get("data"));
          String payload = json.writeValueAsString(node);
          assertThat(EventSchemas.violations(payload)).as(type).isEmpty();
          return payload;
        };
    Instant start = clock;
    publishRaw(
        "travel.trip.created",
        trip,
        ev.apply(
            "travel.trip.created",
            d -> {
              d.put("travelerId", "emp_1004");
              d.put("destination", "SEA");
              ObjectNode a = d.putObject("allocation");
              a.put("costCenterId", "cc_sales");
              a.put("projectId", "prj_apollo");
              a.put("departmentId", "dep_sales");
            }));
    publishRaw("travel.policy.violation", trip, ev.apply("travel.policy.violation", d -> {}));
    publishRaw(
        "travel.approval.requested",
        trip,
        ev.apply(
            "travel.approval.requested",
            d -> d.put("approvalId", "apr_01K4Q0N7S6Z2X8G5H3J9M1P7RZ")));
    publishRaw(
        "travel.approval.escalated",
        trip,
        ev.apply(
            "travel.approval.escalated",
            d -> d.put("approvalId", "apr_01K4Q0N7S6Z2X8G5H3J9M1P7RZ")));
    publishRaw(
        "travel.approval.approved",
        trip,
        ev.apply(
            "travel.approval.approved",
            d -> d.put("approvalId", "apr_01K4Q0N7S6Z2X8G5H3J9M1P7RZ")));
    publishRaw(
        "travel.trip.booked",
        trip,
        ev.apply(
            "travel.trip.booked",
            d -> {
              d.putObject("total").put("currency", "USD").put("amountMinor", 120000);
              d.putObject("allocation").put("costCenterId", "cc_sales");
            }));
    publishRaw(
        "travel.finance.payment-captured",
        trip,
        ev.apply(
            "travel.finance.payment-captured",
            d -> d.putObject("amount").put("currency", "USD").put("amountMinor", 120000)));
    publishRaw(
        "travel.finance.payment-refunded",
        trip,
        ev.apply(
            "travel.finance.payment-refunded",
            d -> d.putObject("amount").put("currency", "USD").put("amountMinor", 20000)));
    publishRaw(
        "travel.disruption.impact-confirmed",
        trip,
        ev.apply("travel.disruption.impact-confirmed", d -> {}));
    publishRaw(
        "travel.disruption.resolved",
        trip,
        ev.apply(
            "travel.disruption.resolved",
            d -> d.putObject("incrementalCost").put("currency", "USD").put("amountMinor", 7300)));
    publishRaw(
        "travel.assistance.case-opened",
        trip,
        ev.apply(
            "travel.assistance.case-opened",
            d -> d.put("caseId", "cas_01K4Q0N7S6Z2X8G5H3J9M1P7TZ")));
    publishRaw(
        "travel.assistance.case-resolved",
        trip,
        ev.apply(
            "travel.assistance.case-resolved",
            d -> d.put("caseId", "cas_01K4Q0N7S6Z2X8G5H3J9M1P7TZ")));
    producer.flush();
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(() -> assertThat(repository.trail(TenantId.of("acme"), trip)).hasSize(12));
    Instant end = clock.plusSeconds(1);
    String window = "from=" + start.minusSeconds(1) + "&to=" + end;

    // spend by cost center: the trip landed where its allocation says, with what money moved
    ResponseEntity<String> spend =
        get("/api/v1/reports/spend?groupBy=costCenter&" + window, TestTokens.carol());
    assertThat(spend.getStatusCode().value()).as(spend.getBody()).isEqualTo(200);
    JsonNode rows = json.readTree(spend.getBody()).get("rows");
    JsonNode sales = null;
    for (JsonNode r : rows) {
      if (r.get("key").asString().equals("cc_sales")) {
        sales = r;
      }
    }
    assertThat(sales).as(spend.getBody()).isNotNull();
    assertThat(sales.get("trips").asLong()).isEqualTo(1);
    assertThat(sales.get("bookedMinor").asLong()).isEqualTo(120000);
    assertThat(sales.get("capturedMinor").asLong()).isEqualTo(120000);
    assertThat(sales.get("refundedMinor").asLong()).isEqualTo(20000);
    assertThat(sales.get("incrementalMinor").asLong()).isEqualTo(7300);
    assertThat(sales.get("netMinor").asLong()).isEqualTo(107300);
    JsonNode byProject =
        json.readTree(
                get("/api/v1/reports/spend?groupBy=project&" + window, TestTokens.carol())
                    .getBody())
            .get("rows");
    assertThat(byProject.valueStream().map(r -> r.get("key").asString()).toList())
        .contains("prj_apollo");
    assertThat(
            get("/api/v1/reports/spend?groupBy=colour", TestTokens.carol()).getStatusCode().value())
        .isEqualTo(422);
    assertThat(get("/api/v1/reports/spend", TestTokens.alice()).getStatusCode().value())
        .as("a traveler reads no reports")
        .isEqualTo(403);
    assertThat(get("/api/v1/reports/spend", TestTokens.bob()).getStatusCode().value())
        .as("a manager neither")
        .isEqualTo(403);
    // CSV export
    ResponseEntity<String> csv =
        get("/api/v1/reports/spend?groupBy=costCenter&format=csv&" + window, TestTokens.carol());
    assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
    assertThat(csv.getBody())
        .startsWith("costCenter,trips,currency,bookedMinor")
        .contains("\r\ncc_sales,1,USD,120000,120000,20000,0,7300,107300\r\n");

    // outcomes and exceptions count what happened
    ResponseEntity<String> outcomesResponse =
        get("/api/v1/reports/outcomes?" + window, TestTokens.carol());
    assertThat(outcomesResponse.getStatusCode().value())
        .as(outcomesResponse.getBody())
        .isEqualTo(200);
    JsonNode outcomes = json.readTree(outcomesResponse.getBody()).get("outcomes");
    assertThat(outcomes.get("booked").asLong()).isGreaterThanOrEqualTo(1);
    assertThat(outcomes.get("disruptions").asLong()).isEqualTo(1);
    assertThat(outcomes.get("recoveriesResolved").asLong()).isEqualTo(1);
    assertThat(outcomes.get("autonomousRecoveries").asLong()).isEqualTo(1);
    assertThat(outcomes.get("avgRecoverySeconds").asDouble())
        .isCloseTo(4.18, org.assertj.core.data.Offset.offset(0.01));
    assertThat(outcomes.get("medianHoursToBook").asDouble())
        .isCloseTo(5.0 / 60.0, org.assertj.core.data.Offset.offset(0.01));
    ResponseEntity<String> exceptionsResponse =
        get("/api/v1/reports/exceptions?" + window, TestTokens.carol());
    assertThat(exceptionsResponse.getStatusCode().value())
        .as(exceptionsResponse.getBody())
        .isEqualTo(200);
    JsonNode x = json.readTree(exceptionsResponse.getBody()).get("exceptions");
    assertThat(x.get("policyViolationsByReason").get(0).get("key").asString())
        .isEqualTo("CABIN_NOT_PERMITTED");
    assertThat(x.get("approvalsRequested").asLong()).isEqualTo(1);
    assertThat(x.get("approvalsEscalated").asLong()).isEqualTo(1);
    assertThat(x.get("approvalsApproved").asLong()).isEqualTo(1);
    assertThat(x.get("avgApprovalHours").asDouble())
        .isCloseTo(2.0 / 60.0, org.assertj.core.data.Offset.offset(0.01));
    assertThat(x.get("casesByKind").get(0).get("key").asString()).isEqualTo("EXPOSURE");
    assertThat(x.get("casesResolved").asLong()).isEqualTo(1);
    assertThat(x.get("avgCaseResolutionHours").asDouble())
        .isCloseTo(1.0 / 60.0, org.assertj.core.data.Offset.offset(0.01));
    ResponseEntity<String> suppliersResponse =
        get("/api/v1/reports/suppliers?" + window, TestTokens.carol());
    assertThat(suppliersResponse.getStatusCode().value())
        .as(suppliersResponse.getBody())
        .isEqualTo(200);
    JsonNode suppliers = json.readTree(suppliersResponse.getBody()).get("rows");
    assertThat(suppliers.valueStream().map(r -> r.get("provider").asString()).toList())
        .contains("sandbox-air");
    assertThat(
            json.readTree(
                    get(
                            "/api/v1/reports/exceptions?format=json&from=" + end + "&to=" + start,
                            TestTokens.carol())
                        .getBody())
                .get("code")
                .asString())
        .isEqualTo("PERIOD_INVALID");
  }

  private void publishRaw(String type, String key, String payload) throws Exception {
    producer.send(new ProducerRecord<>(Topics.topicFor(type), key, payload)).get();
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
