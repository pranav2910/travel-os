package io.travelos.assistance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.web.testing.TestTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 6: real Postgres, real Kafka in (the platform's events, in the shapes the contracts define)
 * and out (every assistance event contract-checked), real REST for the people who work cases.
 * Nothing here touches an order, a trip or a payment: the test proves the cases, their owners,
 * statuses, next actions, SLAs and escalations, and that the platform's own facts settle them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssistanceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  static final TenantId ACME = TenantId.of("acme");
  static final String TRIP = "trip_01K4Q0N7S6Z2X8G5H3J9M1P7RB";
  static final String ORDER = "ord_01K4Q0N7S6Z2X8G5H3J9M1P7RG";
  static final String EXPOSURE = "exp_01K4Q0N7S6Z2X8G5H3J9M1P7RH";
  static final String DISRUPTION = "dsr_01K4Q0N7S6Z2X8G5H3J9M1P7RD";
  static final String TRIP_2 = "trip_01K4Q0N7S6Z2X8G5H3J9M1P7RC";
  static final String ORDER_2 = "ord_01K4Q0N7S6Z2X8G5H3J9M1P7RJ";

  @Autowired Environment environment;
  @Autowired KafkaConnectionDetails kafkaConnection;
  @Autowired KafkaTemplate<String, String> kafka;
  private final JsonMapper json = JsonMapper.builder().build();
  private final EventCodec codec = new EventCodec();
  private final Clock clock = Clock.systemUTC();
  private RestClient http;
  private KafkaConsumer<String, String> consumer;
  private final List<EventEnvelope> published = new ArrayList<>();
  private String exposureCase;
  private String approvalCase;

  @BeforeAll
  void setUp() {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
    Properties c = new Properties();
    c.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnection.getBootstrapServers()));
    c.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
    c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    c.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringDeserializer");
    c.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringDeserializer");
    c.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "1000");
    consumer = new KafkaConsumer<>(c);
    consumer.subscribe(List.of(Topics.ASSISTANCE));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void anExposureBecomesOneCaseHoweverOftenItIsToldAndSettlesWhenTheOrderSaysSo() {
    // the platform knows the trip's traveler; the case will name her
    send(
        event(
            "travel.trip.created",
            TRIP,
            "travel-core",
            Map.of(
                "tripId",
                TRIP,
                "travelerId",
                "emp_1001",
                "status",
                "SUBMITTED",
                "source",
                "WEB",
                "requestedBy",
                "human/alice")));
    EventEnvelope failed =
        event("travel.order.compensation-failed", TRIP, "order", compensationFailed());
    send(failed);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(cases(TestTokens.carol(), "")).hasSize(1));
    JsonNode c = cases(TestTokens.carol(), "").get(0);
    exposureCase = c.get("caseId").asString();
    assertThat(exposureCase).startsWith("cas_");
    assertThat(c.get("kind").asString()).isEqualTo("EXPOSURE");
    assertThat(c.get("status").asString()).isEqualTo("OPEN");
    assertThat(c.get("priority").asString()).isEqualTo("HIGH");
    assertThat(c.get("queue").asString()).isEqualTo("FINANCE");
    assertThat(c.get("travelerId").asString()).as("from the trip index").isEqualTo("emp_1001");
    assertThat(c.get("exposureId").asString()).isEqualTo(EXPOSURE);
    assertThat(c.get("title").asString()).contains("USD 468.00").contains("sandbox-hotel");
    assertThat(c.get("nextAction").asString()).contains("/exposures/" + EXPOSURE + "/resolution");
    assertThat(c.get("nextActionRole").asString()).isEqualTo("TRAVEL_ADMIN");
    assertThat(c.get("owner").isNull()).isTrue();
    Instant opened = Instant.parse(c.get("openedAt").asString());
    Instant due = Instant.parse(c.get("dueAt").asString());
    assertThat(Duration.between(opened, due)).as("HIGH: 4h SLA").isEqualTo(Duration.ofHours(4));
    assertThat(c.get("overdue").asBoolean()).isFalse();

    // the very same event again (Kafka redelivery) and the exposure told by a second event: one
    // case
    send(codec.toJson(failed));
    send(event("travel.order.compensation-failed", TRIP, "order", compensationFailed()));
    send(
        event(
            "travel.order.failed",
            TRIP,
            "order",
            Map.of(
                "orderId",
                ORDER,
                "tripId",
                TRIP,
                "status",
                "FAILED",
                "reasonCode",
                "X",
                "message",
                "m",
                "compensated",
                true,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        "itm_01K4Q0N7S6Z2X8G5H3J9M1P7RJ",
                        "type",
                        "AIR",
                        "status",
                        "CANCELLED")))));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(events(TestTokens.carol(), exposureCase))
                    .hasSizeGreaterThanOrEqualTo(2));
    assertThat(cases(TestTokens.carol(), "")).hasSize(1);
    assertThat(events(TestTokens.carol(), exposureCase).stream().map(e -> e.get("kind").asString()))
        .containsExactly("OPENED", "LINKED");

    // the traveler sees her own case, read-only; a colleague sees nothing; another tenant nothing
    assertThat(cases(TestTokens.alice(), "")).hasSize(1);
    assertThat(cases(TestTokens.dan(), "")).isEmpty();
    assertThat(get("/api/v1/cases/" + exposureCase, TestTokens.dan()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(get("/api/v1/cases/" + exposureCase, TestTokens.zoe()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(
            post(
                    "/api/v1/cases/" + exposureCase + "/assignment",
                    TestTokens.alice(),
                    "{\"owner\":\"me\"}")
                .getStatusCode()
                .value())
        .as("a traveler cannot work a case")
        .isEqualTo(403);
    assertThat(get("/api/v1/cases/summary", TestTokens.alice()).getStatusCode().value())
        .isEqualTo(403);

    // carol takes it, notes what she did; the summary counts it
    ResponseEntity<String> assigned =
        post(
            "/api/v1/cases/" + exposureCase + "/assignment",
            TestTokens.carol(),
            "{\"owner\":\"me\"}");
    assertThat(assigned.getStatusCode().value()).as(assigned.getBody()).isEqualTo(200);
    JsonNode a = json.readTree(assigned.getBody());
    assertThat(a.get("owner").asString()).isEqualTo("human/carol");
    assertThat(a.get("status").asString()).isEqualTo("IN_PROGRESS");
    assertThat(
            post(
                    "/api/v1/cases/" + exposureCase + "/notes",
                    TestTokens.carol(),
                    "{\"text\":\"called the property; they will confirm by email\"}")
                .getStatusCode()
                .value())
        .isEqualTo(201);
    assertThat(
            put(
                    "/api/v1/cases/" + exposureCase + "/status",
                    TestTokens.carol(),
                    "{\"status\":\"WAITING\",\"reason\":\"waiting on the property\"}")
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            post("/api/v1/cases/" + exposureCase + "/closure", TestTokens.carol(), "{}")
                .getStatusCode()
                .value())
        .as("a case is closed only once resolved")
        .isEqualTo(409);
    JsonNode summary = json.readTree(get("/api/v1/cases/summary", TestTokens.carol()).getBody());
    assertThat(summary.get("open").asLong()).isEqualTo(1);
    assertThat(summary.get("byQueue").get("FINANCE").get("WAITING").asLong()).isEqualTo(1);
    assertThat(cases(TestTokens.carol(), "?owner=me")).hasSize(1);
    assertThat(cases(TestTokens.carol(), "?queue=TRAVEL_OPS")).isEmpty();

    // the order says the exposure is resolved: the case settles by itself; carol closes it
    send(
        event(
            "travel.order.exposure-resolved",
            TRIP,
            "order",
            Map.of(
                "orderId",
                ORDER,
                "tripId",
                TRIP,
                "exposureId",
                EXPOSURE,
                "resolvedBy",
                "human/carol",
                "resolution",
                "cancelled by phone with the property, refund confirmed",
                "amount",
                Map.of("currency", "USD", "amountMinor", 46800))));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(status(TestTokens.carol(), exposureCase)).isEqualTo("RESOLVED"));
    JsonNode resolved =
        json.readTree(get("/api/v1/cases/" + exposureCase, TestTokens.carol()).getBody());
    assertThat(resolved.get("resolution").asString()).contains("refund confirmed");
    assertThat(resolved.get("resolvedAt").isNull()).isFalse();
    assertThat(
            post(
                    "/api/v1/cases/" + exposureCase + "/closure",
                    TestTokens.carol(),
                    "{\"reason\":\"traveler told\"}")
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(status(TestTokens.carol(), exposureCase)).isEqualTo("CLOSED");
    assertThat(cases(TestTokens.carol(), "")).as("closed cases leave the board").isEmpty();
    assertThat(cases(TestTokens.carol(), "?includeClosed=true")).hasSize(1);
    assertThat(events(TestTokens.carol(), exposureCase).stream().map(e -> e.get("kind").asString()))
        .containsExactly("OPENED", "LINKED", "ASSIGNED", "NOTE", "STATUS", "RESOLVED", "CLOSED");
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void aRecoveryWaitingOnAnApproverIsCriticalEscalatesWhenOverdueAndSettlesWhenResolved() {
    send(
        event(
            "travel.disruption.approval-required",
            TRIP_2,
            "disruption",
            Map.of(
                "disruptionId",
                DISRUPTION,
                "tripId",
                TRIP_2,
                "orderId",
                ORDER_2,
                "approvalId",
                "apr_01K4Q0N7S6Z2X8G5H3J9M1P7RM",
                "role",
                "MANAGER",
                "incrementalCost",
                Map.of("currency", "USD", "amountMinor", 18000),
                "policyDecisionId",
                "pd_01K4Q0N7S6Z2X8G5H3J9M1P7RK",
                "reasonCodes",
                List.of("INCREMENTAL_COST_ABOVE_AUTONOMY_LIMIT"))));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(cases(TestTokens.carol(), "?queue=APPROVALS")).hasSize(1));
    JsonNode c = cases(TestTokens.carol(), "?queue=APPROVALS").get(0);
    approvalCase = c.get("caseId").asString();
    assertThat(c.get("kind").asString()).isEqualTo("RECOVERY_APPROVAL");
    assertThat(c.get("priority").asString()).isEqualTo("CRITICAL");
    assertThat(c.get("nextActionRole").asString()).isEqualTo("MANAGER");
    assertThat(c.get("disruptionId").asString()).isEqualTo(DISRUPTION);
    assertThat(c.get("summary").asString()).contains("USD 180.00");

    // the test profile gives CRITICAL a 2s SLA and sweeps every 500ms: the case escalates by itself
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              JsonNode e =
                  json.readTree(get("/api/v1/cases/" + approvalCase, TestTokens.carol()).getBody());
              assertThat(e.get("status").asString()).isEqualTo("ESCALATED");
              assertThat(e.get("escalationLevel").asInt()).isGreaterThanOrEqualTo(1);
            });
    // escalation gives the case a fresh due time at its raised priority; the board lists it as
    // escalated
    assertThat(cases(TestTokens.carol(), "?status=ESCALATED")).isNotEmpty();
    assertThat(events(TestTokens.carol(), approvalCase).stream().map(e -> e.get("kind").asString()))
        .contains("ESCALATED");
    // a person escalates it further; nothing escalates past level 3
    assertThat(
            post(
                    "/api/v1/cases/" + approvalCase + "/escalation",
                    TestTokens.carol(),
                    "{\"reason\":\"the traveler is at the airport\"}")
                .getStatusCode()
                .value())
        .isEqualTo(200);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              JsonNode e =
                  json.readTree(get("/api/v1/cases/" + approvalCase, TestTokens.carol()).getBody());
              assertThat(e.get("escalationLevel").asInt()).isEqualTo(3);
              assertThat(e.get("nextActionRole").asString()).isEqualTo("TRAVEL_ADMIN+FINANCE");
            });

    // the disruption is resolved: the case settles, whoever held it
    send(
        event(
            "travel.disruption.resolved",
            TRIP_2,
            "disruption",
            Map.of(
                "disruptionId",
                DISRUPTION,
                "tripId",
                TRIP_2,
                "orderId",
                ORDER_2,
                "replacementBundleId",
                "bdl_01K4Q0N7S6Z2X8G5H3J9M1P7RD",
                "incrementalCost",
                Map.of("currency", "USD", "amountMinor", 7300),
                "autonomyOutcome",
                "APPROVED",
                "resolvedBy",
                "human/bob",
                "externalOrderId",
                "SBX-1",
                "recordLocator",
                "TM3KA6",
                "durationMs",
                4180)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(status(TestTokens.carol(), approvalCase)).isEqualTo("RESOLVED"));
    assertThat(
            json.readTree(get("/api/v1/cases/" + approvalCase, TestTokens.carol()).getBody())
                .get("resolution")
                .asString())
        .contains("human/bob");
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void everyKindOfUnfinishedBusinessOpensACaseWithItsOwnNextAction() {
    String t3 = "trip_01K4Q0N7S6Z2X8G5H3J9M1P7RE";
    String o3 = "ord_01K4Q0N7S6Z2X8G5H3J9M1P7RK";
    send(
        event(
            "travel.order.failed",
            t3,
            "order",
            Map.of(
                "orderId",
                o3,
                "tripId",
                t3,
                "status",
                "FAILED",
                "reasonCode",
                "OUTCOME_UNKNOWN",
                "message",
                "the hotel may hold a booking",
                "compensated",
                false,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        "itm_01K4Q0N7S6Z2X8G5H3J9M1P7RM",
                        "type",
                        "HOTEL",
                        "status",
                        "UNKNOWN",
                        "provider",
                        "sandbox-hotel",
                        "failureCode",
                        "OUTCOME_UNKNOWN")))));
    send(
        event(
            "travel.trip.failed",
            t3,
            "travel-core",
            Map.of(
                "tripId",
                t3,
                "stage",
                "BOOKING",
                "reasonCode",
                "OUTCOME_UNKNOWN",
                "message",
                "booking failed")));
    send(
        event(
            "travel.disruption.recovery-failed",
            t3,
            "disruption",
            Map.of(
                "disruptionId",
                "dsr_01K4Q0N7S6Z2X8G5H3J9M1P7RF",
                "tripId",
                t3,
                "orderId",
                o3,
                "status",
                "NO_ALTERNATIVE",
                "stage",
                "SEARCH",
                "reasonCode",
                "NO_OFFERS",
                "message",
                "no itineraries")));
    send(
        event(
            "travel.finance.payment-declined",
            t3,
            "order",
            Map.of(
                "paymentId",
                "pay_01K4Q0N7S6Z2X8G5H3J9M1P7RH",
                "orderId",
                o3,
                "tripId",
                t3,
                "provider",
                "sandbox-payments",
                "providerRef",
                "spi_1",
                "instrumentId",
                "pmi_01K4Q0N7S6Z2X8G5H3J9M1P7RJ",
                "amount",
                Map.of("currency", "USD", "amountMinor", 82000),
                "status",
                "DECLINED",
                "reasonCode",
                "CARD_DECLINED",
                "message",
                "declined")));
    send(
        event(
            "travel.trip.cancellation-incomplete",
            t3,
            "travel-core",
            Map.of(
                "tripId",
                t3,
                "orderId",
                o3,
                "reasonCode",
                "CANCELLATION_INCOMPLETE",
                "message",
                "hotel refused")));
    send(
        event(
            "travel.trip.components-released",
            t3,
            "travel-core",
            Map.of(
                "tripId",
                t3,
                "orderId",
                o3,
                "components",
                List.of(
                    Map.of(
                        "componentId",
                        "cmp_01K4Q0N7S6Z2X8G5H3J9M1P7RD",
                        "type",
                        "HOTEL",
                        "status",
                        "CANCEL_FAILED",
                        "provider",
                        "sandbox-hotel",
                        "externalRef",
                        "HB-1",
                        "failureCode",
                        "CANCELLATION_REFUSED",
                        "summary",
                        "Harbor Suites")))));
    send(
        event(
            "travel.order.items-released",
            t3,
            "order",
            Map.of(
                "orderId",
                o3,
                "tripId",
                t3,
                "items",
                List.of(),
                "refused",
                List.of(
                    Map.of(
                        "exposureId",
                        "exp_01K4Q0N7S6Z2X8G5H3J9M1P7RN",
                        "itemId",
                        "itm_01K4Q0N7S6Z2X8G5H3J9M1P7RP",
                        "componentId",
                        "cmp_01K4Q0N7S6Z2X8G5H3J9M1P7RD",
                        "provider",
                        "sandbox-hotel",
                        "externalRef",
                        "HB-1",
                        "amount",
                        Map.of("currency", "USD", "amountMinor", 23800),
                        "reason",
                        "CANCELLATION_REFUSED",
                        "detail",
                        "non-refundable rate",
                        "status",
                        "OPEN")),
                "reason",
                "hotel not needed",
                "releasedBy",
                "human/alice")));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(cases(TestTokens.carol(), "?tripId=" + t3)).hasSize(7));
    Map<String, JsonNode> byKind = new LinkedHashMap<>();
    for (JsonNode c : cases(TestTokens.carol(), "?tripId=" + t3)) {
      byKind.put(
          c.get("kind").asString()
              + (c.get("kind").asString().equals("CANCELLATION_INCOMPLETE")
                      && c.has("componentId")
                      && !c.get("componentId").isNull()
                  ? ":component"
                  : "")
              + (c.get("kind").asString().equals("EXPOSURE") ? "" : ""),
          c);
    }
    assertThat(byKind.keySet())
        .containsExactlyInAnyOrder(
            "OUTCOME_UNKNOWN",
            "BOOKING_FAILED",
            "RECOVERY_FAILED",
            "PAYMENT_DECLINED",
            "CANCELLATION_INCOMPLETE",
            "CANCELLATION_INCOMPLETE:component",
            "EXPOSURE");
    assertThat(byKind.get("OUTCOME_UNKNOWN").get("priority").asString()).isEqualTo("CRITICAL");
    assertThat(byKind.get("OUTCOME_UNKNOWN").get("nextAction").asString())
        .contains("sandbox-hotel")
        .contains("whether the booking exists");
    assertThat(byKind.get("RECOVERY_FAILED").get("queue").asString()).isEqualTo("TRAVEL_OPS");
    assertThat(byKind.get("PAYMENT_DECLINED").get("queue").asString()).isEqualTo("FINANCE");
    assertThat(byKind.get("EXPOSURE").get("exposureId").asString())
        .isEqualTo("exp_01K4Q0N7S6Z2X8G5H3J9M1P7RN");
    assertThat(byKind.get("CANCELLATION_INCOMPLETE:component").get("componentId").asString())
        .isEqualTo("cmp_01K4Q0N7S6Z2X8G5H3J9M1P7RD");
    // the platform settles: payment authorized, trip cancelled, component released, exposure
    // resolved
    send(
        event(
            "travel.finance.payment-authorized",
            t3,
            "order",
            Map.of(
                "paymentId",
                "pay_01K4Q0N7S6Z2X8G5H3J9M1P7RH",
                "orderId",
                o3,
                "tripId",
                t3,
                "provider",
                "sandbox-payments",
                "providerRef",
                "spi_2",
                "instrumentId",
                "pmi_01K4Q0N7S6Z2X8G5H3J9M1P7RJ",
                "amount",
                Map.of("currency", "USD", "amountMinor", 82000),
                "status",
                "AUTHORIZED")));
    send(
        event(
            "travel.trip.cancelled",
            t3,
            "travel-core",
            Map.of("tripId", t3, "reason", "done", "cancelledBy", "human/carol")));
    send(
        event(
            "travel.trip.components-released",
            t3,
            "travel-core",
            Map.of(
                "tripId",
                t3,
                "orderId",
                o3,
                "components",
                List.of(
                    Map.of(
                        "componentId",
                        "cmp_01K4Q0N7S6Z2X8G5H3J9M1P7RD",
                        "type",
                        "HOTEL",
                        "status",
                        "CANCELLED")))));
    send(
        event(
            "travel.order.exposure-resolved",
            t3,
            "order",
            Map.of(
                "orderId",
                o3,
                "tripId",
                t3,
                "exposureId",
                "exp_01K4Q0N7S6Z2X8G5H3J9M1P7RN",
                "resolvedBy",
                "human/carol",
                "resolution",
                "released by phone",
                "amount",
                Map.of("currency", "USD", "amountMinor", 23800))));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(cases(TestTokens.carol(), "?tripId=" + t3)).hasSize(3));
    assertThat(
            cases(TestTokens.carol(), "?tripId=" + t3).stream().map(c -> c.get("kind").asString()))
        .containsExactlyInAnyOrder("OUTCOME_UNKNOWN", "BOOKING_FAILED", "RECOVERY_FAILED");
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void aTravelerOpensACaseOnTheirOwnTripOnlyAndAWorkerOnAnything() {
    String body =
        "{\"kind\":\"TRAVELER_REQUEST\",\"title\":\"Please add my frequent flyer number to the booking\",\"tripId\":\""
            + TRIP
            + "\"}";
    ResponseEntity<String> opened = post("/api/v1/cases", TestTokens.alice(), body, "req-1");
    assertThat(opened.getStatusCode().value()).as(opened.getBody()).isEqualTo(201);
    JsonNode c = json.readTree(opened.getBody());
    assertThat(c.get("kind").asString()).isEqualTo("TRAVELER_REQUEST");
    assertThat(c.get("travelerId").asString()).isEqualTo("emp_1001");
    assertThat(c.get("priority").asString()).isEqualTo("NORMAL");
    assertThat(c.get("queue").asString()).isEqualTo("TRAVEL_OPS");
    // the same key again is the same case
    assertThat(
            json.readTree(post("/api/v1/cases", TestTokens.alice(), body, "req-1").getBody())
                .get("caseId")
                .asString())
        .isEqualTo(c.get("caseId").asString());
    // dan is not the traveler of that trip: 404
    assertThat(post("/api/v1/cases", TestTokens.dan(), body, "req-2").getStatusCode().value())
        .isEqualTo(404);
    // a safety case is critical and lands on the safety queue
    JsonNode safety =
        json.readTree(
            post(
                    "/api/v1/cases",
                    TestTokens.carol(),
                    "{\"kind\":\"SAFETY\",\"title\":\"Traveler unreachable after the airport closure\",\"tripId\":\""
                        + TRIP
                        + "\"}",
                    "saf-1")
                .getBody());
    assertThat(safety.get("priority").asString()).isEqualTo("CRITICAL");
    assertThat(safety.get("queue").asString()).isEqualTo("SAFETY");
    assertThat(safety.get("nextAction").asString()).contains("confirm they are safe");
  }

  @Test
  @org.junit.jupiter.api.Order(99)
  void everyAssistanceEventIsContractValid() {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer
                  .poll(Duration.ofMillis(250))
                  .forEach(r -> published.add(codec.fromJson(r.value())));
              assertThat(published.stream().map(EventEnvelope::eventType).distinct())
                  .contains(
                      "travel.assistance.case-opened",
                      "travel.assistance.case-assigned",
                      "travel.assistance.case-escalated",
                      "travel.assistance.case-resolved",
                      "travel.assistance.case-closed");
            });
    for (EventEnvelope e : published) {
      assertThat(EventSchemas.violations(codec.toJson(e))).as(codec.toJson(e)).isEmpty();
      assertThat(e.producer()).isEqualTo("assistance");
    }
    EventEnvelope escalated =
        published.stream()
            .filter(e -> e.eventType().equals("travel.assistance.case-escalated"))
            .findFirst()
            .orElseThrow();
    assertThat(escalated.data().get("escalatedBy")).isEqualTo("service/assistance");
    assertThat(escalated.correlationId()).isEqualTo(TRIP_2);
  }

  // ------------------------------------------------------------------ helpers

  private Map<String, Object> compensationFailed() {
    Map<String, Object> exposure = new LinkedHashMap<>();
    exposure.put("exposureId", EXPOSURE);
    exposure.put("itemId", "itm_01K4Q0N7S6Z2X8G5H3J9M1P7RJ");
    exposure.put("componentId", "cmp_01K4Q0N7S6Z2X8G5H3J9M1P7RF");
    exposure.put("provider", "sandbox-hotel");
    exposure.put("externalRef", "SBH-01K4Q0N7S6Z2X8G5H3J9M1P7RK");
    exposure.put("amount", Map.of("currency", "USD", "amountMinor", 46800));
    exposure.put("reason", "COMPENSATION_FAILED");
    exposure.put(
        "detail", "CANCELLATION_REFUSED: property does not accept cancellations within 24h");
    exposure.put("status", "OPEN");
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("orderId", ORDER);
    d.put("tripId", TRIP);
    d.put("reasonCode", "COMPENSATION_INCOMPLETE");
    d.put("message", "sandbox-hotel refused to cancel; the transfer booking had failed");
    d.put("requiredRole", "TRAVEL_ADMIN");
    d.put("exposures", List.of(exposure));
    return d;
  }

  private EventEnvelope event(
      String type, String correlation, String producer, Map<String, Object> data) {
    return EventEnvelope.create(type, 1, ACME, correlation, null, producer, data, clock);
  }

  private void send(EventEnvelope e) {
    send(codec.toJson(e));
  }

  private void send(String payload) {
    EventEnvelope e = codec.fromJson(payload);
    assertThat(EventSchemas.violations(payload))
        .as("the test sends contract-valid platform events only: " + payload)
        .isEmpty();
    kafka.send(Topics.topicFor(e.eventType()), e.correlationId(), payload).join();
  }

  private List<JsonNode> cases(String token, String query) {
    ResponseEntity<String> r = get("/api/v1/cases" + query, token);
    assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(200);
    return json.readTree(r.getBody()).valueStream().toList();
  }

  private List<JsonNode> events(String token, String caseId) {
    return json.readTree(get("/api/v1/cases/" + caseId + "/events", token).getBody())
        .valueStream()
        .toList();
  }

  private String status(String token, String caseId) {
    return json.readTree(get("/api/v1/cases/" + caseId, token).getBody()).get("status").asString();
  }

  private ResponseEntity<String> get(String path, String token) {
    return http.get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> post(String path, String token, String body) {
    return post(path, token, body, UUID.randomUUID().toString());
  }

  private ResponseEntity<String> post(String path, String token, String body, String key) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> put(String path, String token, String body) {
    return http.put()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }
}
