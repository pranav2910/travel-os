package io.travelos.travelcore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripAlternative;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import io.travelos.workflows.TripPlanning;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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

/**
 * Phase 3 (ADR-0015): planning is not purchasing. Drafts, the QUOTED state, a person's purchase
 * authorization bound to plan and price, idempotent confirmation, selection between alternatives,
 * the booking gate that consumes exactly one authorization, and conversations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestTokens.class, TestClock.class, RecordingApprovalSignaler.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseApiIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  private static final String INTENT =
      """
      {"origin":"BOS","destination":"SEA",
       "earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T17:00:00Z",
       "returnAfter":"2026-10-07T20:00:00Z","latestReturn":"2026-10-08T06:00:00Z",
       "purpose":"customer meeting","hotelRequired":false,
       "preferences":{"cabin":"ECONOMY","nonstopOnly":true,"refundableOnly":false,"preferredCarriers":["DL"]}}
      """;
  private static final String CHEAP = "bdl_01ARZ3NDEKTSV4RRFFQ69G5FA1";
  private static final String PRICEY = "bdl_01ARZ3NDEKTSV4RRFFQ69G5FA3";
  private static final String OPT_RUN = "opt_01ARZ3NDEKTSV4RRFFQ69G5FAC";
  private static final String DECISION = "pd_01ARZ3NDEKTSV4RRFFQ69G5FAD";

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired JdbcClient jdbc;
  private final JsonMapper json = JsonMapper.builder().build();
  private RestClient http;
  private ManagedChannel channel;
  private TravelCoreServiceGrpc.TravelCoreServiceBlockingStub core;

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
  }

  @AfterAll
  void tearDown() {
    channel.shutdownNow();
  }

  // ================================================================== drafts

  @Test
  void aDraftPlansNothingUntilItIsSubmitted() {
    ResponseEntity<String> created =
        post(
            "/api/v1/trips",
            TestTokens.alice(),
            "{\"intent\":" + INTENT + ",\"draft\":true,\"purchaseMode\":\"CONFIRM\"}");
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(202);
    JsonNode draft = json.readTree(created.getBody());
    String tripId = draft.get("tripId").asString();
    assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
    assertThat(draft.get("purchaseMode").asString()).isEqualTo("CONFIRM");
    assertThat(draft.get("intent").get("preferences").get("nonstopOnly").asBoolean()).isTrue();
    assertThat(outboxCount(tripId, "travel.trip.created")).as("no request yet").isZero();

    // edit it, then submit; only the submission announces the request
    ResponseEntity<String> edited =
        http.method(HttpMethod.PUT)
            .uri("/api/v1/trips/" + tripId + "/draft")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.alice())
            .body(
                "{\"intent\":"
                    + INTENT.replace("\"cabin\":\"ECONOMY\"", "\"cabin\":\"PREMIUM_ECONOMY\"")
                    + ",\"purchaseMode\":\"CONFIRM\"}")
            .retrieve()
            .toEntity(String.class);
    assertThat(edited.getStatusCode().value()).as(edited.getBody()).isEqualTo(200);
    assertThat(
            json.readTree(edited.getBody())
                .get("intent")
                .get("preferences")
                .get("cabin")
                .asString())
        .isEqualTo("PREMIUM_ECONOMY");
    assertThat(
            post("/api/v1/trips/" + tripId + "/submission", TestTokens.bob(), "")
                .getStatusCode()
                .value())
        .as(
            "a manager by role may see the draft (Slice 1 rule without an allocation) but is not its requester")
        .isEqualTo(403);
    ResponseEntity<String> submitted =
        post("/api/v1/trips/" + tripId + "/submission", TestTokens.alice(), "");
    assertThat(submitted.getStatusCode().value()).isEqualTo(202);
    assertThat(json.readTree(submitted.getBody()).get("status").asString()).isEqualTo("SUBMITTED");
    assertThat(outboxCount(tripId, "travel.trip.created")).isEqualTo(1);
    // submitting again changes nothing
    assertThat(
            post("/api/v1/trips/" + tripId + "/submission", TestTokens.alice(), "")
                .getStatusCode()
                .value())
        .isEqualTo(202);
    assertThat(outboxCount(tripId, "travel.trip.created")).isEqualTo(1);
  }

  // ================================================================== quote, confirm, book

  @Test
  void aPersonAuthorizesThePurchaseOfTheQuotedPlanAndBookingConsumesItOnce() {
    String tripId = create(TestTokens.alice(), "CONFIRM");
    transition(tripId, TripStatus.PLANNING, b -> {});
    Trip quoted =
        transition(
            tripId,
            TripStatus.QUOTED,
            b ->
                b.setSelectedBundleId(CHEAP)
                    .setOptimizationRunId(OPT_RUN)
                    .setPolicyDecisionId(DECISION)
                    .setTotal(usd(47500))
                    .setConditions("air: non-refundable, change fee USD 75.00")
                    .addAlternatives(alternative(CHEAP, 47500, 1))
                    .addAlternatives(alternative(PRICEY, 70000, 2))
                    .setReason("planned; a person confirms the purchase"));
    assertThat(quoted.getStatus()).isEqualTo(TripStatus.QUOTED);
    assertThat(quoted.hasPurchase()).as("nothing authorized yet").isFalse();
    assertThat(outboxCount(tripId, "travel.trip.quoted")).isEqualTo(1);
    JsonNode view = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody());
    assertThat(view.get("status").asString()).isEqualTo("QUOTED");
    assertThat(view.get("alternatives")).hasSize(2);
    assertThat(view.get("alternatives").get(0).get("selected").asBoolean()).isTrue();
    assertThat(view.has("purchase")).isFalse();

    // an approver by role is not the buyer
    ResponseEntity<String> notBuyer =
        post("/api/v1/trips/" + tripId + "/purchase", TestTokens.bob(), "{}");
    assertThat(notBuyer.getStatusCode().value()).isEqualTo(403);
    assertThat(notBuyer.getBody()).contains("NOT_THE_BUYER");
    // a confirmation of a plan that is not the quoted one conflicts
    ResponseEntity<String> stale =
        post(
            "/api/v1/trips/" + tripId + "/purchase",
            TestTokens.alice(),
            "{\"bundleId\":\"" + PRICEY + "\"}");
    assertThat(stale.getStatusCode().value()).isEqualTo(409);
    assertThat(stale.getBody()).contains("SELECTION_CHANGED");

    // the traveler confirms: bound to plan, price and conditions; idempotent by key and by state
    String key = "confirm-" + UUID.randomUUID();
    ResponseEntity<String> first =
        post(
            "/api/v1/trips/" + tripId + "/purchase",
            TestTokens.alice(),
            key,
            "{\"bundleId\":\"" + CHEAP + "\"}");
    assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(200);
    JsonNode authorization = json.readTree(first.getBody());
    String authorizationId = authorization.get("authorizationId").asString();
    assertThat(authorizationId).startsWith("pau_");
    assertThat(authorization.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(authorization.get("basis").asString()).isEqualTo("HUMAN");
    assertThat(authorization.get("authorizedBy").asString()).isEqualTo("human/alice");
    assertThat(authorization.get("total").get("amountMinor").asLong()).isEqualTo(47500);
    assertThat(authorization.get("conditions").asString()).contains("non-refundable");
    ResponseEntity<String> replay =
        post("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice(), key, "{}");
    assertThat(json.readTree(replay.getBody()).get("authorizationId").asString())
        .isEqualTo(authorizationId);
    ResponseEntity<String> again =
        post("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice(), "{}");
    assertThat(json.readTree(again.getBody()).get("authorizationId").asString())
        .as("confirming twice is one authorization")
        .isEqualTo(authorizationId);
    assertThat(RecordingApprovalSignaler.OTHERS)
        .filteredOn(
            o ->
                o.tripId().equals(tripId)
                    && o.signal().equals(TripPlanning.SIGNAL_PURCHASE_AUTHORIZED))
        .hasSize(1);
    assertThat(outboxCount(tripId, "travel.trip.purchase-authorized")).isEqualTo(1);
    view = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody());
    assertThat(view.get("purchase").get("authorizationId").asString()).isEqualTo(authorizationId);

    // the workflow proceeds: approved, then booking consumes the authorization, once
    transition(tripId, TripStatus.APPROVED, b -> b.setReason("in policy"));
    Trip booking = transition(tripId, TripStatus.BOOKING, b -> b.setReason("booking"));
    assertThat(booking.getStatus()).isEqualTo(TripStatus.BOOKING);
    JsonNode history =
        json.readTree(get("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice()).getBody());
    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("status").asString()).isEqualTo("CONSUMED");
    // a second BOOKING move (the workflow retrying after a lost answer) is the same state, not a
    // second consumption; a fresh attempt would need a fresh authorization
    assertThat(transition(tripId, TripStatus.BOOKING, b -> {}).getStatus())
        .isEqualTo(TripStatus.BOOKING);
    assertThat(
            json.readTree(
                get("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice()).getBody()))
        .hasSize(1);
    // confirming a trip that is being booked is not possible
    assertThat(
            post("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice(), "{}")
                .getStatusCode()
                .value())
        .isEqualTo(409);
  }

  @Test
  void aChangedSelectionOrPriceSupersedesTheAuthorization() {
    String tripId = create(TestTokens.alice(), "CONFIRM");
    transition(tripId, TripStatus.PLANNING, b -> {});
    transition(
        tripId,
        TripStatus.QUOTED,
        b ->
            b.setSelectedBundleId(CHEAP)
                .setOptimizationRunId(OPT_RUN)
                .setPolicyDecisionId(DECISION)
                .setTotal(usd(47500))
                .addAlternatives(alternative(CHEAP, 47500, 1))
                .addAlternatives(alternative(PRICEY, 70000, 2)));
    String firstId =
        json.readTree(
                post("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice(), "{}").getBody())
            .get("authorizationId")
            .asString();

    // choosing the other plan re-quotes it at once and the earlier authorization no longer stands
    ResponseEntity<String> unknown =
        post(
            "/api/v1/trips/" + tripId + "/selection",
            TestTokens.alice(),
            "{\"bundleId\":\"bdl_01ARZ3NDEKTSV4RRFFQ69G5FA9\"}");
    assertThat(unknown.getStatusCode().value()).isEqualTo(422);
    ResponseEntity<String> selected =
        post(
            "/api/v1/trips/" + tripId + "/selection",
            TestTokens.alice(),
            "{\"bundleId\":\"" + PRICEY + "\"}");
    assertThat(selected.getStatusCode().value()).as(selected.getBody()).isEqualTo(200);
    JsonNode view = json.readTree(selected.getBody());
    assertThat(view.get("total").get("amountMinor").asLong()).isEqualTo(70000);
    assertThat(view.get("alternatives").get(1).get("selected").asBoolean()).isTrue();
    assertThat(RecordingApprovalSignaler.OTHERS)
        .filteredOn(
            o ->
                o.tripId().equals(tripId)
                    && o.signal().equals(TripPlanning.SIGNAL_SELECTION_CHANGED))
        .hasSize(1);
    JsonNode history =
        json.readTree(get("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice()).getBody());
    assertThat(history.get(0).get("authorizationId").asString()).isEqualTo(firstId);
    assertThat(history.get(0).get("status").asString()).isEqualTo("SUPERSEDED");
    // a refresh is a signal to the workflow
    assertThat(
            post("/api/v1/trips/" + tripId + "/quote-refresh", TestTokens.alice(), "")
                .getStatusCode()
                .value())
        .isEqualTo(202);
    assertThat(RecordingApprovalSignaler.OTHERS)
        .filteredOn(
            o -> o.tripId().equals(tripId) && o.signal().equals(TripPlanning.SIGNAL_QUOTE_REFRESH))
        .hasSize(1);

    // confirmed at 700.00; the workflow re-quotes it higher: superseded again, a person decides
    String secondId =
        json.readTree(
                post("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice(), "{}").getBody())
            .get("authorizationId")
            .asString();
    assertThat(secondId).isNotEqualTo(firstId);
    Trip requoted =
        transition(
            tripId,
            TripStatus.QUOTED,
            b -> b.setSelectedBundleId(PRICEY).setTotal(usd(72000)).setReason("quote refreshed"));
    assertThat(requoted.hasPurchase()).isFalse();
    history =
        json.readTree(get("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice()).getBody());
    assertThat(history.get(0).get("status").asString()).isEqualTo("SUPERSEDED");
    assertThat(history.get(0).get("supersededReason").asString()).contains("price changed");
    // a lower re-quote is within what was authorized
    String thirdId =
        json.readTree(
                post("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice(), "{}").getBody())
            .get("authorizationId")
            .asString();
    Trip cheaper =
        transition(
            tripId,
            TripStatus.QUOTED,
            b -> b.setSelectedBundleId(PRICEY).setTotal(usd(69000)).setReason("quote refreshed"));
    assertThat(cheaper.getPurchase().getAuthorizationId()).isEqualTo(thirdId);
    assertThat(cheaper.getPurchase().getStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void bookingWithoutAnAuthorizationIsRefusedSoAQuoteReservesNothing() {
    String tripId = create(TestTokens.alice(), "CONFIRM");
    transition(tripId, TripStatus.PLANNING, b -> {});
    transition(
        tripId,
        TripStatus.QUOTED,
        b ->
            b.setSelectedBundleId(CHEAP)
                .setOptimizationRunId(OPT_RUN)
                .setPolicyDecisionId(DECISION)
                .setTotal(usd(47500))
                .addAlternatives(alternative(CHEAP, 47500, 1)));
    transition(tripId, TripStatus.APPROVED, b -> b.setReason("in policy"));
    assertThatThrownBy(() -> transition(tripId, TripStatus.BOOKING, b -> {}))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e -> {
              assertThat(((StatusRuntimeException) e).getStatus().getCode())
                  .isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(((StatusRuntimeException) e).getStatus().getDescription())
                  .contains("PURCHASE_NOT_AUTHORIZED");
            });
    JsonNode view = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody());
    assertThat(view.get("status").asString())
        .as("the refusal changed nothing")
        .isEqualTo("APPROVED");
    assertThat(
            json.readTree(
                get("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice()).getBody()))
        .isEmpty();
    // and a stale authorization (another plan) does not open the gate either
    transition(
        tripId,
        TripStatus.QUOTED,
        b ->
            b.setSelectedBundleId(CHEAP)
                .setTotal(usd(47500))
                .addAlternatives(alternative(CHEAP, 47500, 1)));
    post("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice(), "{}");
    transition(
        tripId,
        TripStatus.QUOTED,
        b -> b.setSelectedBundleId(PRICEY).setTotal(usd(70000)).setReason("re-selected"));
    transition(tripId, TripStatus.APPROVED, b -> b.setReason("in policy"));
    assertThatThrownBy(() -> transition(tripId, TripStatus.BOOKING, b -> {}))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e ->
                assertThat(((StatusRuntimeException) e).getStatus().getDescription())
                    .contains("PURCHASE_NOT_AUTHORIZED"));
  }

  @Test
  void policyGrantedAutonomyIsRecordedAsAnAuditableAuthorization() {
    String tripId = create(TestTokens.alice(), null);
    transition(tripId, TripStatus.PLANNING, b -> {});
    Trip approved =
        transition(
            tripId,
            TripStatus.APPROVED,
            b ->
                b.setSelectedBundleId(CHEAP)
                    .setOptimizationRunId(OPT_RUN)
                    .setPolicyDecisionId(DECISION)
                    .setTotal(usd(47500))
                    .setAutonomousPurchase(true)
                    .setConditions("air: refundable")
                    .setReason("in policy, no approval required"));
    assertThat(approved.getPurchase().getBasis()).isEqualTo("POLICY_AUTONOMY");
    assertThat(approved.getPurchase().getAuthorizedBy()).isEqualTo("agent/trip-planner/v1");
    JsonNode history =
        json.readTree(get("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice()).getBody());
    assertThat(history.get(0).get("basis").asString()).isEqualTo("POLICY_AUTONOMY");
    assertThat(outboxCount(tripId, "travel.trip.purchase-authorized")).isEqualTo(1);
    assertThat(
            transition(tripId, TripStatus.BOOKING, b -> b.setAutonomousPurchase(true)).getStatus())
        .isEqualTo(TripStatus.BOOKING);
    history =
        json.readTree(get("/api/v1/trips/" + tripId + "/purchase", TestTokens.alice()).getBody());
    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("status").asString()).isEqualTo("CONSUMED");
  }

  // ================================================================== conversations

  @Test
  void aConversationPersistsEveryTurnAndPlansThroughTheSameApis() {
    ResponseEntity<String> started =
        post(
            "/api/v1/conversations",
            TestTokens.alice(),
            "{\"text\":\"I need to be in Seattle next week for a customer visit\"}");
    assertThat(started.getStatusCode().value()).as(started.getBody()).isEqualTo(202);
    JsonNode c = json.readTree(started.getBody());
    String conversationId = c.get("conversationId").asString();
    assertThat(conversationId).startsWith("cnv_");
    String firstTrip = c.get("currentTripId").asString();
    JsonNode trip = json.readTree(get("/api/v1/trips/" + firstTrip, TestTokens.alice()).getBody());
    assertThat(trip.get("source").asString()).isEqualTo("CONVERSATION");
    assertThat(trip.get("sourceReference").asString()).isEqualTo(conversationId);
    assertThat(c.get("messages")).hasSize(2);

    // the platform asks for detail: the conversation waits for the person
    transition(
        firstTrip,
        TripStatus.FAILED,
        b ->
            b.setFailureStage("INTENT")
                .setFailureCode("NEEDS_CLARIFICATION")
                .setReason("Which day next week, and when do you return?"));
    JsonNode waiting =
        json.readTree(get("/api/v1/conversations/" + conversationId, TestTokens.alice()).getBody());
    assertThat(waiting.get("status").asString()).isEqualTo("AWAITING_USER");
    JsonNode last = waiting.get("messages").get(waiting.get("messages").size() - 1);
    assertThat(last.get("role").asString()).isEqualTo("ASSISTANT");
    assertThat(last.get("kind").asString()).isEqualTo("QUESTION");
    assertThat(last.get("text").asString()).contains("Which day");

    // the answer becomes a new trip whose request carries the whole transcript
    ResponseEntity<String> replied =
        post(
            "/api/v1/conversations/" + conversationId + "/messages",
            TestTokens.alice(),
            "{\"text\":\"Tuesday the 6th, back Thursday evening\"}");
    assertThat(replied.getStatusCode().value()).as(replied.getBody()).isEqualTo(202);
    JsonNode after = json.readTree(replied.getBody());
    String secondTrip = after.get("currentTripId").asString();
    assertThat(secondTrip).isNotEqualTo(firstTrip);
    JsonNode second =
        json.readTree(get("/api/v1/trips/" + secondTrip, TestTokens.alice()).getBody());
    assertThat(second.get("request").asString())
        .contains("Seattle")
        .contains("Clarification: Tuesday the 6th");
    assertThat(after.get("status").asString()).isEqualTo("OPEN");
    // nobody else reads it
    assertThat(
            get("/api/v1/conversations/" + conversationId, TestTokens.dan())
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(get("/api/v1/conversations", TestTokens.alice()).getBody()).contains(conversationId);
  }

  // ================================================================== helpers

  private String create(String token, String purchaseMode) {
    String body =
        "{\"intent\":"
            + INTENT
            + (purchaseMode == null ? "" : ",\"purchaseMode\":\"" + purchaseMode + "\"")
            + "}";
    ResponseEntity<String> created = post("/api/v1/trips", token, body);
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(202);
    return json.readTree(created.getBody()).get("tripId").asString();
  }

  private Trip transition(
      String trip,
      TripStatus to,
      java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
    TransitionTripRequest.Builder b =
        TransitionTripRequest.newBuilder()
            .setCtx(
                RequestContext.newBuilder()
                    .setTenantId("acme")
                    .setCorrelationId(trip)
                    .setCausationId("cmd_01ARZ3NDEKTSV4RRFFQ69G5FAV")
                    .setPrincipal(
                        Principal.newBuilder()
                            .setKind(Principal.Kind.AGENT)
                            .setId("agent/trip-planner/v1")))
            .setTripId(trip)
            .setTo(to);
    customize.accept(b);
    return core.transitionTrip(b.build());
  }

  private static TripAlternative alternative(String bundle, long cents, int rank) {
    return TripAlternative.newBuilder()
        .setBundleId(bundle)
        .setTotal(usd(cents))
        .setSummary("DL 123 BOS-SEA 08:05-11:30 nonstop")
        .setRank(rank)
        .setRefundable(false)
        .setConditions("air: non-refundable, change fee USD 75.00")
        .build();
  }

  private static Money usd(long cents) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(cents).build();
  }

  private long outboxCount(String tripId, String eventType) {
    Long n =
        jdbc.sql(
                "SELECT count(*) FROM outbox WHERE payload->>'eventType' = :type AND payload->'data'->>'tripId' = :trip")
            .param("type", eventType)
            .param("trip", tripId)
            .query(Long.class)
            .single();
    return n == null ? 0 : n;
  }

  private ResponseEntity<String> post(String path, String token, String body) {
    return post(path, token, UUID.randomUUID().toString(), body);
  }

  private ResponseEntity<String> post(
      String path, String token, String idempotencyKey, String body) {
    RestClient.RequestBodySpec spec =
        http.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey);
    return spec.body(body).retrieve().toEntity(String.class);
  }

  private ResponseEntity<String> get(String path, String token) {
    return http.get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }
}
