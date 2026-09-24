package io.travelos.travelcore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.ModelCall;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.trip.v1.ApplyIntentExtractionRequest;
import io.travelos.contracts.trip.v1.GetTripRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.TravelIntent;
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
@Import({TestTokens.class, TestClock.class, RecordingApprovalSignaler.class})
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
    consumer.subscribe(List.of(Topics.TRIP, Topics.APPROVAL, Topics.INTENT));
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

    // an approved plan whose quote expired is planned again (replanned event), and a supplier gone
    // for good after approval ends the trip as FAILED instead of leaving it looking approved
    String expired = createAsAlice();
    transition(expired, TripStatus.PLANNING, b -> {});
    transition(
        expired,
        TripStatus.APPROVED,
        b ->
            b.setSelectedBundleId(BUNDLE)
                .setOptimizationRunId(OPT_RUN)
                .setPolicyDecisionId(DECISION)
                .setTotal(usd(99000)));
    assertThat(
            transition(
                    expired,
                    TripStatus.PLANNING,
                    b ->
                        b.setReason("the hotel quote expired; re-planning")
                            .setReplanReason("QUOTE_EXPIRED"))
                .getStatus())
        .isEqualTo(TripStatus.PLANNING);
    transition(
        expired, TripStatus.APPROVED, b -> b.setOptimizationRunId(OPT_RUN).setTotal(usd(99000)));
    Trip gone =
        transition(
            expired,
            TripStatus.FAILED,
            b -> b.setFailureStage("REVALIDATION").setFailureCode("OFFER_GONE").setReason("gone"));
    assertThat(gone.getStatus()).isEqualTo(TripStatus.FAILED);
    JsonNode goneView =
        json.readTree(get("/api/v1/trips/" + expired, TestTokens.alice()).getBody());
    assertThat(goneView.get("status").asString()).isEqualTo("FAILED");
    assertThat(goneView.get("failureCode").asString()).isEqualTo("OFFER_GONE");
  }

  @Test
  @org.junit.jupiter.api.Order(7)
  void cancellingABookedTripReleasesTheReservationBeforeItIsCancelled() {
    // BUG-08: the trip said CANCELLED while the order stayed CONFIRMED at the supplier
    String booked = createAsAlice();
    transition(booked, TripStatus.PLANNING, b -> {});
    transition(
        booked,
        TripStatus.APPROVED,
        b ->
            b.setSelectedBundleId(BUNDLE)
                .setOptimizationRunId(OPT_RUN)
                .setPolicyDecisionId(DECISION)
                .setTotal(usd(99000)));
    transition(booked, TripStatus.BOOKING, b -> {});
    transition(booked, TripStatus.BOOKED, b -> b.setOrderId(ORDER));
    assertThatThrownBy(() -> transition(booked, TripStatus.CANCELLED, b -> {}))
        .as("nobody, not even the workflow, cancels a booked trip without releasing it")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));

    ResponseEntity<String> first = cancel(booked, TestTokens.alice(), "Meeting moved to video");
    assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(200);
    assertThat(json.readTree(first.getBody()).get("status").asString()).isEqualTo("CANCELLING");
    ConsumerRecord<String, String> requested =
        awaitEvent(booked, "travel.trip.cancellation-requested");
    JsonNode requestedData = json.readTree(requested.value()).get("data");
    assertThat(requestedData.get("orderId").asString()).isEqualTo(ORDER);
    assertThat(requestedData.get("requestedBy").asString()).isEqualTo("human/alice");
    ResponseEntity<String> again = cancel(booked, TestTokens.alice(), "Meeting moved to video");
    assertThat(again.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(again.getBody()).get("status").asString()).isEqualTo("CANCELLING");

    // a supplier refused part of it: still CANCELLING, and it says why; the same report twice is
    // one
    Trip incomplete =
        transition(
            booked,
            TripStatus.CANCELLING,
            b ->
                b.setFailureStage("CANCELLATION")
                    .setFailureCode("CANCELLATION_INCOMPLETE")
                    .setReason("the hotel refused to release the room"));
    assertThat(incomplete.getStatus()).isEqualTo(TripStatus.CANCELLING);
    assertThat(incomplete.getFailureCode()).isEqualTo("CANCELLATION_INCOMPLETE");
    transition(
        booked,
        TripStatus.CANCELLING,
        b ->
            b.setFailureStage("CANCELLATION")
                .setFailureCode("CANCELLATION_INCOMPLETE")
                .setReason("the hotel refused to release the room"));
    awaitEvent(booked, "travel.trip.cancellation-incomplete");
    JsonNode stuck = json.readTree(get("/api/v1/trips/" + booked, TestTokens.alice()).getBody());
    assertThat(stuck.get("status").asString()).isEqualTo("CANCELLING");
    assertThat(stuck.get("failureCode").asString()).isEqualTo("CANCELLATION_INCOMPLETE");

    // released at last: CANCELLED now, and the failure is over
    Trip cancelled =
        transition(booked, TripStatus.CANCELLED, b -> b.setReason("every component released"));
    assertThat(cancelled.getStatus()).isEqualTo(TripStatus.CANCELLED);
    assertThat(cancelled.getFailureCode()).isEmpty();
    awaitEvent(booked, "travel.trip.cancelled");
    JsonNode history =
        json.readTree(get("/api/v1/trips/" + booked + "/history", TestTokens.alice()).getBody());
    List<String> moves = new ArrayList<>();
    history.forEach(h -> moves.add(h.get("from").asString() + ">" + h.get("to").asString()));
    assertThat(moves)
        .containsSubsequence("BOOKED>CANCELLING", "CANCELLING>CANCELLING", "CANCELLING>CANCELLED");
    assertThat(moves.stream().filter("CANCELLING>CANCELLING"::equals)).hasSize(1);
    consumer.poll(Duration.ofMillis(500)).forEach(received::add);
    assertThat(
            received.stream()
                .filter(r -> booked.equals(r.key()))
                .map(r -> json.readTree(r.value()).get("eventType").asString())
                .filter("travel.trip.cancellation-requested"::equals))
        .as("one request to release, however often the traveler clicks")
        .hasSize(1);
  }

  @Test
  @org.junit.jupiter.api.Order(8)
  void aWithdrawnTripCanNeitherBeApprovedNorBooked() {
    String withdrawn = createAsAlice();
    transition(withdrawn, TripStatus.PLANNING, b -> {});
    transition(
        withdrawn,
        TripStatus.AWAITING_APPROVAL,
        b ->
            b.setSelectedBundleId(BUNDLE)
                .setOptimizationRunId(OPT_RUN)
                .setPolicyDecisionId(DECISION)
                .setTotal(usd(99000)));
    ResponseEntity<String> cancelled = cancel(withdrawn, TestTokens.alice(), "plans changed");
    assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(cancelled.getBody()).get("status").asString()).isEqualTo("CANCELLED");
    ResponseEntity<String> late = decide(withdrawn, TestTokens.bob(), "APPROVE", "late-1");
    assertThat(late.getStatusCode().value()).isEqualTo(409);
    assertThat(json.readTree(late.getBody()).get("code").asString())
        .isEqualTo("TRIP_NOT_AWAITING_APPROVAL");
    assertThatThrownBy(() -> transition(withdrawn, TripStatus.BOOKING, b -> {}))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
  }

  @Test
  @org.junit.jupiter.api.Order(9)
  void aTripArrangedForSomeoneElseIsBookedInTheirName() {
    // BUG-04: the workflow read an empty traveler snapshot for arranged trips
    String named =
        "{\"travelerId\":\"emp_1004\",\"traveler\":{\"givenName\":\"Dan\","
            + "\"familyName\":\"Okafor\",\"email\":\"dan@acme.example\"},\"intent\":"
            + INTENT
            + "}";
    ResponseEntity<String> created = postTrip(TestTokens.bob(), named);
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(202);
    String tripId = json.readTree(created.getBody()).get("tripId").asString();
    Trip seen =
        core.getTrip(GetTripRequest.newBuilder().setCtx(ctx("acme")).setTripId(tripId).build());
    assertThat(seen.getTraveler().getTravelerId()).isEqualTo("emp_1004");
    assertThat(seen.getTraveler().getGivenName()).isEqualTo("Dan");
    assertThat(seen.getTraveler().getFamilyName()).isEqualTo("Okafor");
    assertThat(seen.getTraveler().getEmail()).isEqualTo("dan@acme.example");
    ResponseEntity<String> halfNamed =
        postTrip(
            TestTokens.bob(),
            "{\"travelerId\":\"emp_1004\",\"traveler\":{\"givenName\":\"Dan\"},\"intent\":"
                + INTENT
                + "}");
    assertThat(halfNamed.getStatusCode().value()).isEqualTo(422);
    assertThat(json.readTree(halfNamed.getBody()).get("code").asString())
        .isEqualTo("TRAVELER_IDENTITY_REQUIRED");
  }

  @Test
  @org.junit.jupiter.api.Order(10)
  void aServiceArrangingATripThroughGrpcNamesTheTravelerToo() {
    // BUG-04 on the internal door: Enterprise Context converts a colleague's demand on the
    // manager's
    // behalf and must say who travels (the verified HRIS identity); without it the trip is refused
    TravelIntent intent =
        extraction("x", "EXTRACTED", "llm_01ARZ3NDEKTSV4RRFFQ69G5FB9").getIntent();
    io.travelos.contracts.trip.v1.CreateTripRequest.Builder request =
        io.travelos.contracts.trip.v1.CreateTripRequest.newBuilder()
            .setCtx(
                RequestContext.newBuilder()
                    .setTenantId("acme")
                    .setCorrelationId("dmd_01ARZ3NDEKTSV4RRFFQ69G5FB9")
                    .setIdempotencyKey(
                        "demand:dmd_01ARZ3NDEKTSV4RRFFQ69G5FB9:CONVERT:" + UUID.randomUUID())
                    .setPrincipal(
                        Principal.newBuilder().setKind(Principal.Kind.HUMAN).setId("human/bob")))
            .setTravelerId("emp_1001")
            .setIntent(intent)
            .setSource("DEMAND")
            .setSourceReference("dmd_01ARZ3NDEKTSV4RRFFQ69G5FB9")
            .addActorRoles("MANAGER")
            .setActorEmployeeId("emp_1002");
    assertThatThrownBy(() -> core.createTrip(request.build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).contains("TRAVELER_IDENTITY_REQUIRED");
            });
    Trip created =
        core.createTrip(
            request
                .setCtx(
                    request.getCtx().toBuilder()
                        .setIdempotencyKey("demand:convert:" + UUID.randomUUID()))
                .setTraveler(
                    io.travelos.contracts.trip.v1.TravelerIdentity.newBuilder()
                        .setGivenName("Alice")
                        .setFamilyName("Nguyen")
                        .setEmail("alice@acme.example"))
                .build());
    assertThat(created.getTravelerId()).isEqualTo("emp_1001");
    assertThat(created.getTraveler().getGivenName()).isEqualTo("Alice");
    assertThat(created.getTraveler().getEmail()).isEqualTo("alice@acme.example");
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
                      "travel.trip.replanned",
                      "travel.trip.booked",
                      "travel.trip.failed",
                      "travel.trip.cancellation-requested",
                      "travel.trip.cancellation-incomplete",
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

  @Test
  @org.junit.jupiter.api.Order(7)
  void freeTextIntentIsFrozenOnceWithEvidenceAndLedgered() {
    String trip =
        createFreeText(TestTokens.alice(), "Fly BOS to SEA on 2026-10-06, back 2026-10-08");
    assertThat(
            core.getTrip(GetTripRequest.newBuilder().setCtx(ctx("acme")).setTripId(trip).build())
                .hasIntent())
        .isFalse();

    ApplyIntentExtractionRequest extracted =
        extraction(trip, "EXTRACTED", "llm_01ARZ3NDEKTSV4RRFFQ69G5FA7");
    Trip frozen = core.applyIntentExtraction(extracted);
    assertThat(frozen.getIntent().getOrigin()).isEqualTo("BOS");
    assertThat(frozen.getIntent().getDestination()).isEqualTo("SEA");
    assertThat(frozen.getVersion()).isEqualTo(1);
    assertThat(frozen.getStatus()).isEqualTo(TripStatus.SUBMITTED);

    // The same model call again (a retried activity): nothing changes, nothing is re-ledgered.
    Trip again = core.applyIntentExtraction(extracted);
    assertThat(again.getVersion()).isEqualTo(1);

    // A different conclusion for a trip whose intent is frozen is a precondition failure.
    ApplyIntentExtractionRequest other =
        extraction(trip, "EXTRACTED", "llm_01ARZ3NDEKTSV4RRFFQ69G5FA8").toBuilder()
            .setIntent(extracted.getIntent().toBuilder().setDestination("SFO"))
            .build();
    assertThatThrownBy(() -> core.applyIntentExtraction(other))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e ->
                assertThat(((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));

    JsonNode view = json.readTree(get("/api/v1/trips/" + trip, TestTokens.alice()).getBody());
    assertThat(view.get("intent").get("origin").asString()).isEqualTo("BOS");
    JsonNode history =
        json.readTree(get("/api/v1/trips/" + trip + "/history", TestTokens.alice()).getBody());
    assertThat(history).hasSize(2);
    assertThat(history.get(1).get("reason").asString())
        .contains("intent extracted")
        .contains("fake-rules-v1");
    assertThat(history.get(1).get("actor").asString()).isEqualTo("agent/trip-planner/v1");

    JsonNode ledger =
        json.readTree(get("/api/v1/trips/" + trip + "/decisions", TestTokens.alice()).getBody());
    assertThat(ledger).hasSize(1);
    assertThat(ledger.get(0).get("decisionType").asString()).isEqualTo("INTENT_EXTRACTION");
    assertThat(ledger.get(0).get("result").asString()).isEqualTo("EXTRACTED");
    assertThat(ledger.get(0).get("call").get("callId").asString())
        .isEqualTo("llm_01ARZ3NDEKTSV4RRFFQ69G5FA7");
    assertThat(ledger.get(0).get("call").get("promptVersion").asInt()).isEqualTo(1);
    assertThat(ledger.get(0).get("call").get("costMicros").asLong()).isEqualTo(11000);
    assertThat(ledger.get(0).get("assumptions").get(0).asString()).contains("06:00");
    // Another tenant sees nothing, not even the ledger.
    assertThat(
            get("/api/v1/trips/" + trip + "/decisions", TestTokens.zoe()).getStatusCode().value())
        .isEqualTo(404);

    ConsumerRecord<String, String> event = awaitEvent(trip, "travel.intent.detected");
    assertThat(EventSchemas.violations(event.value())).isEmpty();
    JsonNode data = json.readTree(event.value()).get("data");
    assertThat(data.get("modelCallId").asString()).isEqualTo("llm_01ARZ3NDEKTSV4RRFFQ69G5FA7");
    assertThat(data.get("intent").get("destination").asString()).isEqualTo("SEA");
  }

  @Test
  @org.junit.jupiter.api.Order(8)
  void unclearTextIsLedgeredAndPublishedButChangesNothing() {
    String trip = createFreeText(TestTokens.alice(), "I need to be in Seattle sometime");
    ApplyIntentExtractionRequest unclear =
        extraction(trip, "NEEDS_CLARIFICATION", "llm_01ARZ3NDEKTSV4RRFFQ69G5FA9").toBuilder()
            .clearIntent()
            .addMissingFields("travel_date")
            .setClarifyingQuestion("Which day do you need to be in Seattle?")
            .build();
    Trip unchanged = core.applyIntentExtraction(unclear);
    assertThat(unchanged.hasIntent()).isFalse();
    assertThat(unchanged.getVersion()).isZero();

    JsonNode ledger =
        json.readTree(get("/api/v1/trips/" + trip + "/decisions", TestTokens.alice()).getBody());
    assertThat(ledger).hasSize(1);
    assertThat(ledger.get(0).get("result").asString()).isEqualTo("NEEDS_CLARIFICATION");
    assertThat(ledger.get(0).get("detail").get("clarifyingQuestion").asString())
        .contains("Which day");

    ConsumerRecord<String, String> event = awaitEvent(trip, "travel.intent.rejected");
    assertThat(EventSchemas.violations(event.value())).isEmpty();
    assertThat(json.readTree(event.value()).get("data").get("missingFields").get(0).asString())
        .isEqualTo("travel_date");

    // No evidence, no ledger entry: the call is refused, not silently accepted.
    assertThatThrownBy(() -> core.applyIntentExtraction(unclear.toBuilder().clearCall().build()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e ->
                assertThat(((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  private Trip transition(
      String trip,
      TripStatus to,
      java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
    TransitionTripRequest.Builder b =
        TransitionTripRequest.newBuilder().setCtx(ctx("acme")).setTripId(trip).setTo(to);
    customize.accept(b);
    if (to == TripStatus.BOOKING && !b.getAutonomousPurchase()) {
      // These scenarios simulate the workflow under the seed policy, which grants autonomous
      // purchase authority (Phase 3); PurchaseApiIntegrationTest covers the gate itself.
      b.setAutonomousPurchase(true);
    }
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
    ResponseEntity<String> created = postTrip(token, "{\"intent\":" + INTENT + "}");
    assertThat(created.getStatusCode().value()).isEqualTo(202);
    return json.readTree(created.getBody()).get("tripId").asString();
  }

  private ResponseEntity<String> postTrip(String token, String body) {
    return http.post()
        .uri("/api/v1/trips")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> cancel(String trip, String token, String reason) {
    return http.post()
        .uri("/api/v1/trips/" + trip + "/cancellation")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body("{\"reason\":\"" + reason + "\"}")
        .retrieve()
        .toEntity(String.class);
  }

  private ApplyIntentExtractionRequest extraction(String trip, String result, String callId) {
    return ApplyIntentExtractionRequest.newBuilder()
        .setCtx(ctx("acme"))
        .setTripId(trip)
        .setResult(result)
        .setIntent(
            TravelIntent.newBuilder()
                .setOrigin("BOS")
                .setDestination("SEA")
                .setEarliestDeparture(Timestamp.newBuilder().setSeconds(1_791_100_800L))
                .setArrivalDeadline(Timestamp.newBuilder().setSeconds(1_791_162_000L))
                .setReturnAfter(Timestamp.newBuilder().setSeconds(1_791_273_600L))
                .setLatestReturn(Timestamp.newBuilder().setSeconds(1_791_338_340L))
                .setHotelRequired(true)
                .setTravelers(1))
        .setConfidence(0.85)
        .addAssumptions("earliest departure assumed 06:00 local on the travel day")
        .setCall(
            ModelCall.newBuilder()
                .setCallId(callId)
                .setProvider("fake")
                .setModel("fake-rules-v1")
                .setPromptId("intent-extraction")
                .setPromptVersion(1)
                .setInputTokens(1200)
                .setOutputTokens(180)
                .setCacheReadTokens(1000)
                .setLatencyMs(420)
                .setCostMicros(11000))
        .build();
  }

  private ConsumerRecord<String, String> awaitEvent(String trip, String type) {
    List<ConsumerRecord<String, String>> matches = new ArrayList<>();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              received.stream()
                  .filter(r -> trip.equals(r.key()))
                  .filter(r -> json.readTree(r.value()).get("eventType").asString().equals(type))
                  .forEach(matches::add);
              assertThat(matches).isNotEmpty();
            });
    return matches.getFirst();
  }

  private String createFreeText(String token, String text) {
    ResponseEntity<String> created =
        http.post()
            .uri("/api/v1/trips")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("{\"request\":\"" + text + "\"}")
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
