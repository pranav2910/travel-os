package io.travelos.assistance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.travelos.assistance.notify.NotificationChannel;
import io.travelos.assistance.notify.NotificationRecords;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 8: notifications are rows before they are messages (one per fact and recipient), reach the
 * inbox at once and the outside world through the dispatcher and the person's preferences; a safety
 * advisory finds the booked travelers in its places and window, tells them, takes their check-ins,
 * and opens a case for whoever asks for help or stays silent past the grace period. Email goes
 * through a recording channel here: no provider is contacted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestTokens.class, NotificationsAndSafetyIntegrationTest.Channels.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationsAndSafetyIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @TestConfiguration
  static class Channels {
    static final List<String> EMAILS = new CopyOnWriteArrayList<>();
    static volatile boolean down;

    @Bean
    NotificationChannel recordingEmail() {
      return new NotificationChannel() {
        @Override
        public NotificationRecords.Channel channel() {
          return NotificationRecords.Channel.EMAIL;
        }

        @Override
        public String send(NotificationRecords.Notification n, String address) {
          if (down) {
            throw new ChannelException("provider down", true);
          }
          EMAILS.add(address + "|" + n.title());
          return "msg-" + EMAILS.size();
        }
      };
    }
  }

  static final TenantId ACME = TenantId.of("acme");
  static final String TRIP = "trip_01K4Q0N7S6Z2X8G5H3J9M1P7RB";
  static final String TRIP_2 = "trip_01K4Q0N7S6Z2X8G5H3J9M1P7RC";
  static final String TRIP_3 = "trip_01K4Q0N7S6Z2X8G5H3J9M1P7RE";

  @Autowired Environment environment;
  @Autowired KafkaConnectionDetails kafkaConnection;
  @Autowired KafkaTemplate<String, String> kafka;
  private final JsonMapper json = JsonMapper.builder().build();
  private final EventCodec codec = new EventCodec();
  private RestClient http;
  private KafkaConsumer<String, String> consumer;
  private final List<EventEnvelope> published = new ArrayList<>();
  private String advisoryId;

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
  @Order(1)
  void aTripsStoryReachesTheTravelerOnceInTheInboxAndByEmail() {
    Instant departs = Instant.now().plus(Duration.ofDays(10));
    Instant returns = departs.plus(Duration.ofDays(3));
    EventEnvelope created =
        event(
            "travel.trip.created",
            TRIP,
            "travel-core",
            map(
                "tripId",
                TRIP,
                "travelerId",
                "emp_1001",
                "status",
                "SUBMITTED",
                "source",
                "WEB",
                "requestedBy",
                "human/alice",
                "travelerEmail",
                "alice@acme.example",
                "travelerName",
                "Alice Nguyen",
                "origin",
                "BOS",
                "destination",
                "SEA",
                "departsAt",
                departs.toString(),
                "returnsAt",
                returns.toString(),
                "cities",
                List.of("SEA")));
    send(created);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(inbox(TestTokens.alice(), false)).hasSize(1));
    JsonNode first = inbox(TestTokens.alice(), false).get(0);
    assertThat(first.get("category").asString()).isEqualTo("TRIP");
    assertThat(first.get("title").asString()).isEqualTo("We are planning your trip to SEA");
    assertThat(first.get("linkId").asString()).isEqualTo(TRIP);
    assertThat(first.get("readAt").isNull()).isTrue();
    // the same event again: nothing new; a colleague sees nothing
    send(codec.toJson(created));
    send(
        event(
            "travel.trip.booked",
            TRIP,
            "travel-core",
            map(
                "tripId",
                TRIP,
                "orderId",
                "ord_01K4Q0N7S6Z2X8G5H3J9M1P7RG",
                "total",
                map("currency", "USD", "amountMinor", 82000),
                "destination",
                "SEA",
                "departsAt",
                departs.toString(),
                "returnsAt",
                returns.toString(),
                "cities",
                List.of("SEA"))));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(inbox(TestTokens.alice(), false)).hasSize(2));
    assertThat(inbox(TestTokens.dan(), false)).isEmpty();
    assertThat(inbox(TestTokens.alice(), false).get(0).get("title").asString())
        .isEqualTo("Your trip is booked to SEA");
    // email went out through the channel (the address learned from the event), once per
    // notification
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(Channels.EMAILS).hasSize(2));
    assertThat(Channels.EMAILS).allMatch(e -> e.startsWith("alice@acme.example|"));
    String bookedId = inbox(TestTokens.alice(), false).get(0).get("notificationId").asString();
    JsonNode deliveries =
        json.readTree(
            get("/api/v1/notifications/" + bookedId + "/deliveries", TestTokens.alice()).getBody());
    assertThat(
            deliveries
                .valueStream()
                .map(d -> d.get("channel").asString() + ":" + d.get("status").asString())
                .toList())
        .containsExactlyInAnyOrder("IN_APP:SENT", "EMAIL:SENT");
    assertThat(
            deliveries
                .valueStream()
                .filter(d -> d.get("channel").asString().equals("EMAIL"))
                .findFirst()
                .orElseThrow()
                .get("address")
                .asString())
        .as("addresses are masked")
        .isEqualTo("a***@acme.example");
    // read state is the person's
    assertThat(
            json.readTree(
                    post("/api/v1/notifications/" + bookedId + "/read", TestTokens.alice(), "")
                        .getBody())
                .get("readAt")
                .isNull())
        .isFalse();
    assertThat(inbox(TestTokens.alice(), true)).hasSize(1);
    assertThat(
            post("/api/v1/notifications/" + bookedId + "/read", TestTokens.dan(), "")
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            json.readTree(post("/api/v1/notifications/read-all", TestTokens.alice(), "").getBody())
                .get("marked")
                .asInt())
        .isEqualTo(1);
  }

  @Test
  @Order(2)
  void approversHearWhatWaitsOnThemAndPreferencesTurnEmailOff() {
    // the manager named by the allocation is addressed directly; a role step reaches everyone with
    // the role
    send(
        event(
            "travel.approval.requested",
            TRIP,
            "travel-core",
            map(
                "approvalId",
                "apr_01K4Q0N7S6Z2X8G5H3J9M1P7RH",
                "tripId",
                TRIP,
                "requestedFrom",
                "emp_1002",
                "role",
                "MANAGER",
                "policyDecisionId",
                "pd_01K4Q0N7S6Z2X8G5H3J9M1P7RF",
                "total",
                map("currency", "USD", "amountMinor", 82000),
                "step",
                1,
                "chainLength",
                2)));
    send(
        event(
            "travel.approval.requested",
            TRIP,
            "travel-core",
            map(
                "approvalId",
                "apr_01K4Q0N7S6Z2X8G5H3J9M1P7RJ",
                "tripId",
                TRIP,
                "requestedFrom",
                "role:FINANCE",
                "role",
                "FINANCE",
                "policyDecisionId",
                "pd_01K4Q0N7S6Z2X8G5H3J9M1P7RF",
                "total",
                map("currency", "USD", "amountMinor", 82000),
                "step",
                2,
                "chainLength",
                2)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(inbox(TestTokens.bob(), false)).hasSize(1));
    assertThat(inbox(TestTokens.bob(), false).get(0).get("title").asString())
        .startsWith("Approval needed: trip");
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(inbox(TestTokens.carol(), false)).as("carol holds FINANCE").hasSize(1));
    assertThat(inbox(TestTokens.carol(), false).get(0).get("recipientRole").asString())
        .isEqualTo("FINANCE");
    assertThat(inbox(TestTokens.alice(), false))
        .as("the traveler is not told about internal steps")
        .hasSize(2);
    // bob turns email off for approvals; the next one stays in-app only
    JsonNode prefs =
        json.readTree(get("/api/v1/notifications/preferences", TestTokens.bob()).getBody());
    assertThat(prefs.get("email").asString())
        .as("learned from the sign-in claims")
        .isEqualTo("bob@acme.example");
    ResponseEntity<String> updated =
        put(
            "/api/v1/notifications/preferences",
            TestTokens.bob(),
            "{\"channels\":{\"APPROVAL\":[\"IN_APP\"]}}");
    assertThat(updated.getStatusCode().value()).as(updated.getBody()).isEqualTo(200);
    assertThat(
            put(
                    "/api/v1/notifications/preferences",
                    TestTokens.bob(),
                    "{\"channels\":{\"APPROVAL\":[\"EMAIL\"]}}")
                .getStatusCode()
                .value())
        .as("in-app cannot be switched off")
        .isEqualTo(422);
    int emailsBefore = Channels.EMAILS.size();
    send(
        event(
            "travel.approval.requested",
            TRIP_2,
            "travel-core",
            map(
                "approvalId",
                "apr_01K4Q0N7S6Z2X8G5H3J9M1P7RK",
                "tripId",
                TRIP_2,
                "requestedFrom",
                "emp_1002",
                "role",
                "MANAGER",
                "policyDecisionId",
                "pd_01K4Q0N7S6Z2X8G5H3J9M1P7RF",
                "total",
                map("currency", "USD", "amountMinor", 50000))));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(inbox(TestTokens.bob(), false)).hasSize(2));
    String id = inbox(TestTokens.bob(), false).get(0).get("notificationId").asString();
    JsonNode deliveries =
        json.readTree(
            get("/api/v1/notifications/" + id + "/deliveries", TestTokens.bob()).getBody());
    assertThat(deliveries.valueStream().map(d -> d.get("channel").asString()).toList())
        .containsExactly("IN_APP");
    assertThat(Channels.EMAILS).hasSize(emailsBefore);
    // an outage is retried, then given up and visible to a travel admin
    Channels.down = true;
    send(
        event(
            "travel.trip.cancelled",
            TRIP,
            "travel-core",
            map("tripId", TRIP, "reason", "meeting moved", "cancelledBy", "human/alice")));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        json.readTree(
                            get("/api/v1/notifications/deliveries/failed", TestTokens.carol())
                                .getBody()))
                    .hasSize(1));
    JsonNode failed =
        json.readTree(get("/api/v1/notifications/deliveries/failed", TestTokens.carol()).getBody())
            .get(0);
    assertThat(failed.get("attempts").asInt()).isEqualTo(2);
    assertThat(failed.get("lastError").asString()).contains("provider down");
    assertThat(
            get("/api/v1/notifications/deliveries/failed", TestTokens.alice())
                .getStatusCode()
                .value())
        .isEqualTo(403);
    Channels.down = false;
  }

  @Test
  @Order(3)
  void anAdvisoryReachesTheTravelersInItsPlacesTakesCheckinsAndOpensCasesForTheSilent() {
    Instant departs = Instant.now().plus(Duration.ofDays(10));
    Instant returns = departs.plus(Duration.ofDays(3));
    // dan is booked to Seattle in the window; a third trip goes to Boston and is not affected
    send(
        event(
            "travel.trip.created",
            TRIP_2,
            "travel-core",
            map(
                "tripId",
                TRIP_2,
                "travelerId",
                "emp_1004",
                "status",
                "SUBMITTED",
                "source",
                "WEB",
                "requestedBy",
                "human/dan")));
    send(
        event(
            "travel.trip.booked",
            TRIP_2,
            "travel-core",
            map(
                "tripId",
                TRIP_2,
                "orderId",
                "ord_01K4Q0N7S6Z2X8G5H3J9M1P7RJ",
                "total",
                map("currency", "USD", "amountMinor", 50000),
                "travelerEmail",
                "dan@acme.example",
                "destination",
                "SEA",
                "departsAt",
                departs.toString(),
                "returnsAt",
                returns.toString(),
                "cities",
                List.of("SEA"))));
    send(
        event(
            "travel.trip.created",
            TRIP_3,
            "travel-core",
            map(
                "tripId",
                TRIP_3,
                "travelerId",
                "emp_1005",
                "status",
                "SUBMITTED",
                "source",
                "WEB",
                "requestedBy",
                "human/erin",
                "destination",
                "BOS",
                "departsAt",
                departs.toString(),
                "returnsAt",
                returns.toString(),
                "cities",
                List.of("BOS"))));
    send(
        event(
            "travel.trip.booked",
            TRIP_3,
            "travel-core",
            map(
                "tripId",
                TRIP_3,
                "orderId",
                "ord_01K4Q0N7S6Z2X8G5H3J9M1P7RM",
                "total",
                map("currency", "USD", "amountMinor", 40000))));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(inbox(TestTokens.dan(), false)).isNotEmpty());
    String body =
        "{\"title\":\"Severe storm: Seattle area\",\"severity\":\"HIGH\",\"cities\":[\"SEA\"],\"countries\":[],\"startsAt\":\""
            + departs.minus(Duration.ofDays(1))
            + "\",\"endsAt\":\""
            + returns.plus(Duration.ofDays(1))
            + "\",\"text\":\"Flights and roads may be disrupted; stay at your hotel and check in.\",\"source\":\"NWS\"}";
    assertThat(post("/api/v1/safety/advisories", TestTokens.alice(), body).getStatusCode().value())
        .isEqualTo(403);
    ResponseEntity<String> issued = post("/api/v1/safety/advisories", TestTokens.carol(), body);
    assertThat(issued.getStatusCode().value()).as(issued.getBody()).isEqualTo(201);
    JsonNode detail = json.readTree(issued.getBody());
    advisoryId = detail.get("advisory").get("advisoryId").asString();
    assertThat(advisoryId).startsWith("adv_");
    assertThat(
            detail.get("affected").valueStream().map(a -> a.get("travelerId").asString()).toList())
        .as("alice's trip was cancelled; erin goes to Boston")
        .containsExactly("emp_1004");
    assertThat(detail.get("advisory").get("checkinDueAt").isNull()).isFalse();
    // dan was told, as a CRITICAL safety notification; erin sees no advisory; a travel admin sees
    // all
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(inbox(TestTokens.dan(), false).get(0).get("category").asString())
                    .isEqualTo("SAFETY"));
    assertThat(json.readTree(get("/api/v1/safety/advisories", TestTokens.dan()).getBody()))
        .hasSize(1);
    assertThat(
            json.readTree(
                get(
                        "/api/v1/safety/advisories",
                        TestTokens.user("erin", "acme", "emp_1005", List.of("TRAVELER")))
                    .getBody()))
        .isEmpty();
    assertThat(
            get(
                    "/api/v1/safety/advisories/" + advisoryId,
                    TestTokens.user("erin", "acme", "emp_1005", List.of("TRAVELER")))
                .getStatusCode()
                .value())
        .isEqualTo(404);
    // the grace period (2s in the test profile) passes without a check-in: a SAFETY case opens for
    // dan
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(cases(TestTokens.carol(), "?kind=SAFETY")).hasSize(1));
    JsonNode silent = cases(TestTokens.carol(), "?kind=SAFETY").get(0);
    assertThat(silent.get("travelerId").asString()).isEqualTo("emp_1004");
    assertThat(silent.get("priority").asString()).isEqualTo("CRITICAL");
    assertThat(silent.get("title").asString()).startsWith("No check-in from emp_1004");
    // dan checks in as safe: the case settles; then asks for help: a new case, at once
    ResponseEntity<String> safe =
        post(
            "/api/v1/safety/advisories/" + advisoryId + "/checkin",
            TestTokens.dan(),
            "{\"status\":\"SAFE\",\"note\":\"at the hotel\"}");
    assertThat(safe.getStatusCode().value()).as(safe.getBody()).isEqualTo(200);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(cases(TestTokens.carol(), "?kind=SAFETY")).isEmpty());
    assertThat(
            post(
                    "/api/v1/safety/advisories/" + advisoryId + "/checkin",
                    TestTokens.dan(),
                    "{\"status\":\"NEEDS_HELP\",\"note\":\"roads closed, need a car\"}")
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(cases(TestTokens.carol(), "?kind=SAFETY")).hasSize(1);
    assertThat(cases(TestTokens.carol(), "?kind=SAFETY").get(0).get("title").asString())
        .contains("asked for help");
    JsonNode after =
        json.readTree(get("/api/v1/safety/advisories/" + advisoryId, TestTokens.carol()).getBody());
    assertThat(after.get("needHelp").asInt()).isEqualTo(1);
    assertThat(after.get("affected").get(0).get("checkin").asString()).isEqualTo("NEEDS_HELP");
    assertThat(after.get("affected").get(0).get("caseId").isNull()).isFalse();
    assertThat(
            http.delete()
                .uri("/api/v1/safety/advisories/" + advisoryId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.carol())
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(204);
  }

  @Test
  @Order(99)
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
                      "travel.assistance.notification-sent",
                      "travel.assistance.advisory-issued",
                      "travel.assistance.checkin-recorded",
                      "travel.assistance.case-opened");
            });
    for (EventEnvelope e : published) {
      assertThat(EventSchemas.violations(codec.toJson(e))).as(codec.toJson(e)).isEmpty();
    }
    EventEnvelope advisory =
        published.stream()
            .filter(e -> e.eventType().equals("travel.assistance.advisory-issued"))
            .findFirst()
            .orElseThrow();
    assertThat(advisory.data().get("affectedTravelers")).isEqualTo(1);
    assertThat(
            published.stream()
                .filter(e -> e.eventType().equals("travel.assistance.notification-sent"))
                .map(e -> e.data().get("status")))
        .contains("SENT", "FAILED");
  }

  // ------------------------------------------------------------------ helpers

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return m;
  }

  private EventEnvelope event(
      String type, String tripId, String producer, Map<String, Object> data) {
    return EventEnvelope.create(type, 1, ACME, tripId, null, producer, data, Clock.systemUTC());
  }

  private void send(EventEnvelope e) {
    send(codec.toJson(e));
  }

  private void send(String payload) {
    EventEnvelope e = codec.fromJson(payload);
    assertThat(EventSchemas.violations(payload)).as(payload).isEmpty();
    kafka.send(Topics.topicFor(e.eventType()), e.correlationId(), payload).join();
  }

  private List<JsonNode> inbox(String token, boolean unread) {
    ResponseEntity<String> r = get("/api/v1/notifications?unread=" + unread, token);
    assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(200);
    return json.readTree(r.getBody()).valueStream().toList();
  }

  private List<JsonNode> cases(String token, String query) {
    return json.readTree(get("/api/v1/cases" + query, token).getBody()).valueStream().toList();
  }

  private ResponseEntity<String> get(String path, String token) {
    return http.get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> post(String path, String token, String body) {
    RestClient.RequestBodySpec spec =
        http.post()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString());
    if (!body.isEmpty()) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return spec.retrieve().toEntity(String.class);
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
