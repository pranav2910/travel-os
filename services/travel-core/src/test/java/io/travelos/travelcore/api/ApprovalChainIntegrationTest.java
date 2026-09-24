package io.travelos.travelcore.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import io.travelos.travelcore.approval.ApprovalSweeper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 7: approval chains (sequential steps from the policy decision, the workflow signalled by
 * the last), delegated authority (a colleague decides in the manager's name, recorded as such) and
 * expiry (an unanswered step escalates to a travel admin, then expires as a rejection).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestTokens.class, TestClock.class, RecordingApprovalSignaler.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalChainIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  private static final String INTENT =
      """
      {"origin":"BOS","destination":"SEA",
       "earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T17:00:00Z",
       "returnAfter":"2026-10-07T20:00:00Z","latestReturn":"2026-10-08T06:00:00Z",
       "purpose":"customer meeting","hotelRequired":false}
      """;
  private static final String FINANCE_ONLY =
      TestTokens.user("fin", "acme", "emp_1005", List.of("TRAVELER", "FINANCE"));

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired KafkaConnectionDetails kafkaConnection;
  @Autowired ApprovalSweeper sweeper;

  private final JsonMapper json = JsonMapper.builder().build();
  private RestClient http;
  private ManagedChannel channel;
  private TravelCoreServiceGrpc.TravelCoreServiceBlockingStub core;
  private KafkaConsumer<String, String> consumer;
  private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

  @BeforeAll
  void setUp() {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    core = TravelCoreServiceGrpc.newBlockingStub(channel);
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnection.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "chain-test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(Topics.APPROVAL));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
    channel.shutdownNow();
    TestClock.reset();
  }

  @AfterEach
  void resetClock() {
    TestClock.reset();
  }

  @Test
  void aChainIsDecidedStepByStepAndTheWorkflowHearsOnlyTheLast() {
    RecordingApprovalSignaler.SIGNALS.clear();
    String tripId = create(TestTokens.alice());
    transition(tripId, TripStatus.PLANNING, b -> {});
    Trip awaiting =
        transition(
            tripId,
            TripStatus.AWAITING_APPROVAL,
            b ->
                b.setSelectedBundleId("bdl_01ARZ3NDEKTSV4RRFFQ69G5FAB")
                    .setPolicyDecisionId("pd_01ARZ3NDEKTSV4RRFFQ69G5FAD")
                    .setTotal(usd(250000))
                    .setApproverRole("MANAGER")
                    .addAllApprovalChain(List.of("MANAGER", "FINANCE"))
                    .setApprovalExpiresAfterSeconds(86_400)
                    .setReason("above the manager threshold; above the finance threshold"));
    assertThat(awaiting.getStatus()).isEqualTo(TripStatus.AWAITING_APPROVAL);
    JsonNode step1 =
        json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody()).get("approval");
    assertThat(step1.get("step").asInt()).isEqualTo(1);
    assertThat(step1.get("chainLength").asInt()).isEqualTo(2);
    assertThat(step1.get("requiredRole").asString()).isEqualTo("MANAGER");
    assertThat(step1.get("chainRoles").valueStream().map(JsonNode::asString).toList())
        .containsExactly("MANAGER", "FINANCE");
    Instant expires = Instant.parse(step1.get("expiresAt").asString());
    assertThat(Duration.between(TestClock.now(), expires))
        .isBetween(Duration.ofHours(23), Duration.ofHours(25));

    // Finance cannot take the manager's step; the manager can, and the workflow hears nothing yet
    ResponseEntity<String> early = decide(tripId, FINANCE_ONLY, "APPROVE", "fin-early");
    assertThat(early.getStatusCode().value()).as(early.getBody()).isEqualTo(403);
    assertThat(json.readTree(early.getBody()).get("code").asString()).isEqualTo("NOT_THE_APPROVER");
    ResponseEntity<String> first = decide(tripId, TestTokens.bob(), "APPROVE", "bob-1");
    assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(200);
    assertThat(json.readTree(first.getBody()).get("status").asString()).isEqualTo("APPROVED");
    assertThat(json.readTree(first.getBody()).get("step").asInt()).isEqualTo(1);
    assertThat(RecordingApprovalSignaler.SIGNALS).as("the chain is not complete").isEmpty();
    JsonNode view = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody());
    assertThat(view.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
    assertThat(view.get("approval").get("status").asString()).isEqualTo("PENDING");
    assertThat(view.get("approval").get("step").asInt()).isEqualTo(2);
    assertThat(view.get("approval").get("requiredRole").asString()).isEqualTo("FINANCE");
    // the manager cannot take Finance's step; Finance can, and the workflow is signalled once
    assertThat(decide(tripId, TestTokens.bob(), "APPROVE", "bob-2").getStatusCode().value())
        .isEqualTo(403);
    ResponseEntity<String> second = decide(tripId, FINANCE_ONLY, "APPROVE", "fin-1");
    assertThat(second.getStatusCode().value()).as(second.getBody()).isEqualTo(200);
    assertThat(RecordingApprovalSignaler.SIGNALS)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.tripId()).isEqualTo(tripId);
              assertThat(s.decision().decision()).isEqualTo("APPROVED");
              assertThat(s.decision().decidedBy()).isEqualTo("human/fin");
            });
    JsonNode steps =
        json.readTree(get("/api/v1/trips/" + tripId + "/approvals", TestTokens.alice()).getBody());
    assertThat(steps).hasSize(2);
    assertThat(steps.valueStream().map(s -> s.get("status").asString()).toList())
        .containsExactly("APPROVED", "APPROVED");
    assertThat(steps.get(1).get("decidedBy").asString()).isEqualTo("human/fin");
    // the story on the broker: two requests, two approvals, the last one final
    awaitEvents(tripId, "travel.approval.approved", 2);
    List<JsonNode> approved = events(tripId, "travel.approval.approved");
    assertThat(approved.get(0).get("data").get("finalStep").asBoolean()).isFalse();
    assertThat(approved.get(1).get("data").get("finalStep").asBoolean()).isTrue();
    assertThat(events(tripId, "travel.approval.requested")).hasSize(2);
    assertThat(events(tripId, "travel.approval.requested").get(1).get("data").get("step").asInt())
        .isEqualTo(2);
  }

  @Test
  void aDelegateDecidesInTheManagersNameWhileTheDelegationLasts() {
    RecordingApprovalSignaler.SIGNALS.clear();
    String tripId = awaitingApproval(create(TestTokens.alice()), 86_400);
    // dan holds no approver role and no delegation: he cannot even see the trip
    assertThat(decide(tripId, TestTokens.dan(), "APPROVE", "dan-0").getStatusCode().value())
        .isEqualTo(404);
    // a traveler cannot delegate what she does not have; a manager can
    assertThat(
            post(
                    "/api/v1/approvals/delegates",
                    TestTokens.alice(),
                    "{\"delegateEmployeeId\":\"emp_1004\",\"validUntil\":\""
                        + TestClock.now().plus(Duration.ofDays(7))
                        + "\"}")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    ResponseEntity<String> delegated =
        post(
            "/api/v1/approvals/delegates",
            TestTokens.bob(),
            "{\"delegateEmployeeId\":\"emp_1004\",\"validUntil\":\""
                + TestClock.now().plus(Duration.ofDays(7))
                + "\"}");
    assertThat(delegated.getStatusCode().value()).as(delegated.getBody()).isEqualTo(201);
    String delegateId = json.readTree(delegated.getBody()).get("delegateId").asString();
    assertThat(json.readTree(get("/api/v1/approvals/delegates", TestTokens.dan()).getBody()))
        .hasSize(1);
    // dan decides in bob's name, and the record says so
    ResponseEntity<String> byDelegate = decide(tripId, TestTokens.dan(), "APPROVE", "dan-1");
    assertThat(byDelegate.getStatusCode().value()).as(byDelegate.getBody()).isEqualTo(200);
    JsonNode a = json.readTree(byDelegate.getBody());
    assertThat(a.get("decidedBy").asString()).isEqualTo("human/dan");
    assertThat(a.get("onBehalfOf").asString()).isEqualTo("emp_1002");
    assertThat(RecordingApprovalSignaler.SIGNALS)
        .singleElement()
        .satisfies(s -> assertThat(s.decision().decidedBy()).isEqualTo("human/dan"));
    awaitEvents(tripId, "travel.approval.approved", 1);
    assertThat(
            events(tripId, "travel.approval.approved")
                .get(0)
                .get("data")
                .get("onBehalfOf")
                .asString())
        .isEqualTo("emp_1002");
    // revoked: the next trip is not dan's to decide
    assertThat(
            http.delete()
                .uri("/api/v1/approvals/delegates/" + delegateId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.bob())
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(204);
    String another = awaitingApproval(create(TestTokens.alice()), 86_400);
    assertThat(decide(another, TestTokens.dan(), "APPROVE", "dan-2").getStatusCode().value())
        .isEqualTo(404);
  }

  @Test
  void anUnansweredStepEscalatesThenExpiresAsARejection() {
    RecordingApprovalSignaler.SIGNALS.clear();
    String tripId = awaitingApproval(create(TestTokens.alice()), 3_600);
    sweeper.sweep();
    assertThat(
            json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody())
                .get("approval")
                .get("escalatedToRole")
                .isNull())
        .as("not due yet")
        .isTrue();
    TestClock.advance(Duration.ofHours(2));
    sweeper.sweep();
    JsonNode escalated =
        json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody()).get("approval");
    assertThat(escalated.get("status").asString()).isEqualTo("PENDING");
    assertThat(escalated.get("escalatedToRole").asString()).isEqualTo("TRAVEL_ADMIN");
    assertThat(escalated.get("escalatedAt").isNull()).isFalse();
    assertThat(
            Duration.between(TestClock.now(), Instant.parse(escalated.get("expiresAt").asString())))
        .isBetween(Duration.ofMinutes(55), Duration.ofMinutes(65));
    assertThat(RecordingApprovalSignaler.SIGNALS).isEmpty();
    // a manager may still decide an escalated step; a travel admin may too; nobody does
    TestClock.advance(Duration.ofHours(4));
    sweeper.sweep();
    JsonNode expired =
        json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody()).get("approval");
    assertThat(expired.get("status").asString()).isEqualTo("REJECTED");
    assertThat(expired.get("decidedBy").asString()).isEqualTo("service/travel-core");
    assertThat(expired.get("comment").asString()).contains("expired");
    assertThat(RecordingApprovalSignaler.SIGNALS)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.tripId()).isEqualTo(tripId);
              assertThat(s.decision().decision()).isEqualTo("REJECTED");
            });
    awaitEvents(tripId, "travel.approval.expired", 1);
    assertThat(events(tripId, "travel.approval.escalated")).hasSize(1);
    for (ConsumerRecord<String, String> r : received) {
      assertThat(EventSchemas.violations(r.value())).as(r.value()).isEmpty();
    }
  }

  // ------------------------------------------------------------------ helpers

  private String awaitingApproval(String tripId, long expiresAfterSeconds) {
    transition(tripId, TripStatus.PLANNING, b -> {});
    transition(
        tripId,
        TripStatus.AWAITING_APPROVAL,
        b ->
            b.setSelectedBundleId("bdl_01ARZ3NDEKTSV4RRFFQ69G5FAB")
                .setPolicyDecisionId("pd_01ARZ3NDEKTSV4RRFFQ69G5FAD")
                .setTotal(usd(150000))
                .setApproverRole("MANAGER")
                .setApprovalExpiresAfterSeconds(expiresAfterSeconds));
    return tripId;
  }

  private Trip transition(
      String trip,
      TripStatus to,
      java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
    TransitionTripRequest.Builder b =
        TransitionTripRequest.newBuilder().setCtx(ctx()).setTripId(trip).setTo(to);
    customize.accept(b);
    return core.transitionTrip(b.build());
  }

  private String create(String token) {
    ResponseEntity<String> created =
        http.post()
            .uri("/api/v1/trips")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("{\"intent\":" + INTENT + "}")
            .retrieve()
            .toEntity(String.class);
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(202);
    return json.readTree(created.getBody()).get("tripId").asString();
  }

  private ResponseEntity<String> decide(String trip, String token, String decision, String key) {
    return http.post()
        .uri("/api/v1/trips/" + trip + "/approval")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key)
        .body("{\"decision\":\"" + decision + "\",\"comment\":\"ok\"}")
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> post(String path, String token, String body) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> get(String path, String token) {
    return http.get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }

  private void awaitEvents(String tripId, String type, int count) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              return events(tripId, type).size() >= count;
            });
  }

  private List<JsonNode> events(String tripId, String type) {
    return received.stream()
        .filter(r -> r.key().equals(tripId))
        .map(r -> json.readTree(r.value()))
        .filter(n -> n.get("eventType").asString().equals(type))
        .toList();
  }

  private static RequestContext ctx() {
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setCausationId("cmd_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId("agent/trip-planner/v1"))
        .build();
  }

  private static Money usd(long cents) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(cents).build();
  }
}
