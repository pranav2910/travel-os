package io.travelos.travelcore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.trip.v1.GetTripRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lifecycle as the workflow drives it (real gRPC) and as people see it (real HTTP): planning,
 * approval by a manager, booking, failure, rejection, and the events each step publishes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestTokens.class, RecordingApprovalSignaler.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TripLifecycleIntegrationTest {

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
  private static final String BUNDLE = "bdl_01ARZ3NDEKTSV4RRFFQ69G5FAB";
  private static final String OPT_RUN = "opt_01ARZ3NDEKTSV4RRFFQ69G5FAC";
  private static final String DECISION = "pd_01ARZ3NDEKTSV4RRFFQ69G5FAD";
  private static final String ORDER = "ord_01ARZ3NDEKTSV4RRFFQ69G5FAE";

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired KafkaConnectionDetails kafkaConnection;

  private final JsonMapper json = JsonMapper.builder().build();
  private RestClient http;
  private ManagedChannel channel;
  private TravelCoreServiceGrpc.TravelCoreServiceBlockingStub core;
  private KafkaConsumer<String, String> consumer;
  private final List<ConsumerRecord<String, String>> received = new ArrayList<>();
  private String tripId;

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
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "lifecycle-test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(Topics.TRIP, Topics.APPROVAL));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
    channel.shutdownNow();
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void theWorkflowReadsTheTripWithTheTravelerSnapshot() {
    tripId = createAsAlice();
    Trip trip =
        core.getTrip(GetTripRequest.newBuilder().setCtx(ctx("acme")).setTripId(tripId).build());
    assertThat(trip.getStatus()).isEqualTo(TripStatus.SUBMITTED);
    assertThat(trip.getIntent().getDestination()).isEqualTo("SEA");
    assertThat(trip.getIntent().hasReturnAfter()).isTrue();
    assertThat(trip.getTraveler().getTravelerId()).isEqualTo("emp_1001");
    assertThat(trip.getTraveler().getGivenName()).isEqualTo("Alice");
    assertThat(trip.getTraveler().getEmail()).isEqualTo("alice@acme.example");
    assertThatThrownBy(
            () ->
                core.getTrip(
                    GetTripRequest.newBuilder().setCtx(ctx("globex")).setTripId(tripId).build()))
        .as("another tenant cannot read it even over the internal API")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void planningEndsInAwaitingApprovalWithEvidenceAndAnApprovalRecord() {
    assertThat(transition(tripId, TripStatus.PLANNING, b -> {}).getStatus())
        .isEqualTo(TripStatus.PLANNING);
    assertThat(transition(tripId, TripStatus.PLANNING, b -> {}).getStatus())
        .as("idempotent")
        .isEqualTo(TripStatus.PLANNING);

    Trip awaiting =
        transition(
            tripId,
            TripStatus.AWAITING_APPROVAL,
            b ->
                b.setSelectedBundleId(BUNDLE)
                    .setOptimizationRunId(OPT_RUN)
                    .setPolicyDecisionId(DECISION)
                    .setTotal(usd(82000))
                    .setApproverRole("MANAGER")
                    .setReason("policy requires manager approval"));
    assertThat(awaiting.getStatus()).isEqualTo(TripStatus.AWAITING_APPROVAL);
    assertThat(awaiting.getApprovalId()).startsWith("apr_");
    assertThat(awaiting.getSelectedBundleId()).isEqualTo(BUNDLE);
    assertThat(awaiting.getTotal().getAmountMinor()).isEqualTo(82000);

    JsonNode view = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody());
    assertThat(view.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
    assertThat(view.get("approval").get("status").asString()).isEqualTo("PENDING");
    assertThat(view.get("approval").get("requiredRole").asString()).isEqualTo("MANAGER");
    assertThat(view.get("total").get("display").asString()).isEqualTo("USD 820.00");
    assertThat(view.get("evidence").get("policyDecisionId").asString()).isEqualTo(DECISION);

    assertThatThrownBy(() -> transition(tripId, TripStatus.BOOKED, b -> b.setOrderId(ORDER)))
        .as("no shortcuts through the lifecycle")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void onlyAManagerWhoIsNotTheTravelerDecides() {
    assertThat(decide(tripId, TestTokens.alice(), "APPROVE", "k1").getStatusCode().value())
        .isEqualTo(403);
    assertThat(
            json.readTree(decide(tripId, TestTokens.alice(), "APPROVE", "k1").getBody())
                .get("code")
                .asString())
        .isEqualTo("NOT_AN_APPROVER");
    assertThat(decide(tripId, TestTokens.dan(), "APPROVE", "k1").getStatusCode().value())
        .as("cannot even see it")
        .isEqualTo(404);
    assertThat(decide(tripId, TestTokens.zoe(), "APPROVE", "k1").getStatusCode().value())
        .isEqualTo(404);

    String bobsOwn = create(TestTokens.bob());
    transition(bobsOwn, TripStatus.PLANNING, b -> {});
    transition(
        bobsOwn,
        TripStatus.AWAITING_APPROVAL,
        b ->
            b.setSelectedBundleId(BUNDLE)
                .setOptimizationRunId(OPT_RUN)
                .setPolicyDecisionId(DECISION)
                .setTotal(usd(50000)));
    ResponseEntity<String> self = decide(bobsOwn, TestTokens.bob(), "APPROVE", "k2");
    assertThat(self.getStatusCode().value()).isEqualTo(403);
    assertThat(json.readTree(self.getBody()).get("code").asString()).isEqualTo("SELF_APPROVAL");
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void aManagerApprovesOnceAndTheWorkflowIsSignalled() {
    RecordingApprovalSignaler.SIGNALS.clear();
    String key = "approve-" + UUID.randomUUID();
    ResponseEntity<String> approved = decide(tripId, TestTokens.bob(), "APPROVE", key);
    assertThat(approved.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json.readTree(approved.getBody());
    assertThat(body.get("status").asString()).isEqualTo("APPROVED");
    assertThat(body.get("decidedBy").asString()).isEqualTo("human/bob");
    assertThat(RecordingApprovalSignaler.SIGNALS)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.tripId()).isEqualTo(tripId);
              assertThat(s.decision().decision()).isEqualTo("APPROVED");
              assertThat(s.decision().decidedBy()).isEqualTo("human/bob");
            });

    assertThat(
            json.readTree(decide(tripId, TestTokens.bob(), "APPROVE", key).getBody())
                .get("status")
                .asString())
        .as("same key replays")
        .isEqualTo("APPROVED");
    assertThat(RecordingApprovalSignaler.SIGNALS).as("a replay does not signal twice").hasSize(1);
    ResponseEntity<String> again = decide(tripId, TestTokens.bob(), "REJECT", "another-key");
    assertThat(again.getStatusCode().value()).isEqualTo(409);
    assertThat(json.readTree(again.getBody()).get("code").asString())
        .isEqualTo("APPROVAL_ALREADY_DECIDED");

    JsonNode view = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody());
    assertThat(view.get("status").asString())
        .as("the workflow moves the trip, not the approver")
        .isEqualTo("AWAITING_APPROVAL");
    assertThat(view.get("approval").get("status").asString()).isEqualTo("APPROVED");
  }

  @Test
  @org.junit.jupiter.api.Order(5)
  void bookingCompletesTheTrip() {
    assertThat(transition(tripId, TripStatus.APPROVED, b -> {}).getStatus())
        .isEqualTo(TripStatus.APPROVED);
    assertThat(transition(tripId, TripStatus.BOOKING, b -> {}).getStatus())
        .isEqualTo(TripStatus.BOOKING);
    Trip booked = transition(tripId, TripStatus.BOOKED, b -> b.setOrderId(ORDER));
    assertThat(booked.getStatus()).isEqualTo(TripStatus.BOOKED);
    assertThat(booked.getOrderId()).isEqualTo(ORDER);
    JsonNode view = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody());
    assertThat(view.get("status").asString()).isEqualTo("BOOKED");
    assertThat(view.get("evidence").get("orderId").asString()).isEqualTo(ORDER);
    JsonNode history =
        json.readTree(get("/api/v1/trips/" + tripId + "/history", TestTokens.alice()).getBody());
    assertThat(history)
        .extracting(n -> n.get("to").asString())
        .containsExactly(
            "SUBMITTED", "PLANNING", "AWAITING_APPROVAL", "APPROVED", "BOOKING", "BOOKED");
    assertThat(history.get(1).get("actor").asString()).isEqualTo("agent/trip-planner/v1");
  }

  @Test
  @org.junit.jupiter.api.Order(6)
  void failuresAndRejectionsAreExplicit() {
    String failing = createAsAlice();
    transition(failing, TripStatus.PLANNING, b -> {});
    assertThatThrownBy(() -> transition(failing, TripStatus.FAILED, b -> {}))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    Trip failed =
        transition(
            failing,
            TripStatus.FAILED,
            b ->
                b.setFailureStage("SEARCH")
                    .setFailureCode("NO_OFFERS")
                    .setReason("no supplier returned offers"));
    assertThat(failed.getStatus()).isEqualTo(TripStatus.FAILED);
    JsonNode view = json.readTree(get("/api/v1/trips/" + failing, TestTokens.alice()).getBody());
    assertThat(view.get("failureStage").asString()).isEqualTo("SEARCH");
    assertThat(view.get("failureCode").asString()).isEqualTo("NO_OFFERS");

    String rejected = createAsAlice();
    transition(rejected, TripStatus.PLANNING, b -> {});
    transition(
        rejected,
        TripStatus.AWAITING_APPROVAL,
        b ->
            b.setSelectedBundleId(BUNDLE)
                .setOptimizationRunId(OPT_RUN)
                .setPolicyDecisionId(DECISION)
                .setTotal(usd(99000)));
    assertThat(
            json.readTree(decide(rejected, TestTokens.bob(), "REJECT", "reject-1").getBody())
                .get("status")
                .asString())
        .isEqualTo("REJECTED");
    assertThat(
            transition(rejected, TripStatus.CANCELLED, b -> b.setReason("rejected by human/bob"))
                .getStatus())
        .isEqualTo(TripStatus.CANCELLED);
  }

  @Test
  @org.junit.jupiter.api.Order(99)
  void everyLifecycleEventIsContractValid() {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              List<String> types =
                  received.stream()
                      .map(r -> json.readTree(r.value()).get("eventType").asString())
                      .toList();
              assertThat(types)
                  .contains(
                      "travel.trip.created",
                      "travel.trip.planned",
                      "travel.trip.booked",
                      "travel.trip.failed",
                      "travel.trip.cancelled",
                      "travel.approval.requested",
                      "travel.approval.approved",
                      "travel.approval.rejected");
            });
    for (ConsumerRecord<String, String> record : received) {
      assertThat(EventSchemas.violations(record.value())).as(record.value()).isEmpty();
    }
  }

  // ------------------------------------------------------------------ helpers

  private Trip transition(
      String trip,
      TripStatus to,
      java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
    TransitionTripRequest.Builder b =
        TransitionTripRequest.newBuilder().setCtx(ctx("acme")).setTripId(trip).setTo(to);
    customize.accept(b);
    return core.transitionTrip(b.build());
  }

  private static RequestContext ctx(String tenant) {
    return RequestContext.newBuilder()
        .setTenantId(tenant)
        .setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setCausationId("cmd_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId("agent/trip-planner/v1"))
        .build();
  }

  private static Money usd(long cents) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(cents).build();
  }

  private String createAsAlice() {
    return create(TestTokens.alice());
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
    assertThat(created.getStatusCode().value()).isEqualTo(202);
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

  private ResponseEntity<String> get(String path, String token) {
    return http.get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }
}
