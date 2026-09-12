package io.travelos.workflows.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Timestamp;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.travelos.contracts.common.v1.ModelCall;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.llm.v1.ExplainTripRequest;
import io.travelos.contracts.llm.v1.ExplainTripResponse;
import io.travelos.contracts.llm.v1.ExtractIntentRequest;
import io.travelos.contracts.llm.v1.ExtractIntentResponse;
import io.travelos.contracts.offer.v1.AirOffer;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.optimization.v1.OptimizeTripRequest;
import io.travelos.contracts.optimization.v1.OptimizeTripResponse;
import io.travelos.contracts.optimization.v1.RankedCandidate;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.contracts.policy.v1.ApproverRequirement;
import io.travelos.contracts.policy.v1.CandidateDecision;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.Outcome;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.policy.v1.ReasonCode;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierError;
import io.travelos.contracts.trip.v1.ApplyIntentExtractionRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.TravelerSnapshot;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.workflows.TripPlanning;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The workflow on Temporal's time-skipping test server with scripted activities. Every path the
 * trip can take, and the exact sequence of lifecycle transitions each one records.
 */
class TripWorkflowTest {

  private static final String TENANT = "acme";
  private static final String TRIP = "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV";
  private static final String OFFER_CHEAP = "off_01ARZ3NDEKTSV4RRFFQ69G5FA1";
  private static final String OFFER_BIZ = "off_01ARZ3NDEKTSV4RRFFQ69G5FA2";
  private static final String OFFER_PRICEY = "off_01ARZ3NDEKTSV4RRFFQ69G5FA3";

  private TestWorkflowEnvironment env;
  private TripActivities activities;
  private WorkflowClient client;
  private final List<TransitionTripRequest> transitions = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(TripPlanning.TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TripWorkflowImpl.class);
    activities = mock(TripActivities.class);
    worker.registerActivitiesImplementations(activities);
    env.start();
    client = env.getWorkflowClient();

    when(activities.loadTrip(anyString(), anyString()))
        .thenReturn(trip(TripStatus.SUBMITTED, true));
    when(activities.transition(any()))
        .thenAnswer(
            inv -> {
              TransitionTripRequest r = inv.getArgument(0);
              transitions.add(r);
              Trip.Builder b = trip(r.getTo(), true).toBuilder();
              if (r.getTo() == TripStatus.AWAITING_APPROVAL) {
                b.setApprovalId("apr_01ARZ3NDEKTSV4RRFFQ69G5FAV").setApprovalStatus("PENDING");
              }
              return b.build();
            });
    when(activities.search(any())).thenReturn(threeOffers());
    when(activities.evaluatePolicy(any())).thenAnswer(inv -> policy(inv.getArgument(0), true));
    when(activities.optimize(any()))
        .thenAnswer(inv -> optimized(inv.getArgument(0), "bdl_" + OFFER_CHEAP.substring(4)));
    when(activities.createOrder(any())).thenReturn(order(OrderStatus.CONFIRMED, ""));
    when(activities.explain(any()))
        .thenReturn(
            ExplainTripResponse.newBuilder().setExplanation("Chosen because cheapest.").build());
    when(activities.extractIntent(any()))
        .thenReturn(extracted(ExtractIntentResponse.Result.EXTRACTED));
    when(activities.applyIntentExtraction(any())).thenReturn(trip(TripStatus.SUBMITTED, true));
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void bookedAfterAManagerApproves() {
    TripWorkflow workflow = start();
    env.sleep(java.time.Duration.ofSeconds(1));
    assertThat(workflow.stage()).isEqualTo(TripPlanning.Stage.AWAITING_APPROVAL);
    workflow.approvalDecided(
        new TripPlanning.ApprovalDecision(
            "apr_01ARZ3NDEKTSV4RRFFQ69G5FAV", "APPROVED", "human/bob", "ok"));

    TripWorkflow.Outcome outcome = result(workflow);

    assertThat(outcome.finalStatus()).isEqualTo("BOOKED");
    assertThat(outcome.orderId()).startsWith("ord_");
    assertThat(statuses())
        .containsExactly(
            TripStatus.PLANNING,
            TripStatus.AWAITING_APPROVAL,
            TripStatus.APPROVED,
            TripStatus.BOOKING,
            TripStatus.BOOKED);
    TransitionTripRequest awaiting = transitions.get(1);
    assertThat(awaiting.getSelectedBundleId()).isEqualTo("bdl_" + OFFER_CHEAP.substring(4));
    assertThat(awaiting.getPolicyDecisionId()).startsWith("pd_");
    assertThat(awaiting.getApproverRole()).isEqualTo("MANAGER");
    assertThat(awaiting.getTotal().getAmountMinor()).isEqualTo(47500);
    assertThat(transitions.get(2).getReason()).contains("human/bob");

    ArgumentCaptor<CreateOrderCommand> command = ArgumentCaptor.forClass(CreateOrderCommand.class);
    verify(activities).createOrder(command.capture());
    assertThat(command.getValue().getCtx().getIdempotencyKey()).isEqualTo(TRIP + ":CREATE-ORDER:1");
    assertThat(command.getValue().getCtx().getPrincipal().getId())
        .isEqualTo("agent/trip-planner/v1");
    assertThat(command.getValue().getApprovalId()).isEqualTo("apr_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    assertThat(command.getValue().getPassengers(0).getGivenName()).isEqualTo("Alice");
    assertThat(command.getValue().getBundle().getOffers(0).getOfferId()).isEqualTo(OFFER_CHEAP);

    ArgumentCaptor<EvaluateTripRequest> policyRequest =
        ArgumentCaptor.forClass(EvaluateTripRequest.class);
    verify(activities).evaluatePolicy(policyRequest.capture());
    assertThat(policyRequest.getValue().getCandidatesCount())
        .as("every offer is a candidate")
        .isEqualTo(3);
    ArgumentCaptor<OptimizeTripRequest> optRequest =
        ArgumentCaptor.forClass(OptimizeTripRequest.class);
    verify(activities).optimize(optRequest.capture());
    assertThat(optRequest.getValue().getCandidatesCount())
        .as("denied candidates never reach the optimizer")
        .isEqualTo(2);
  }

  @Test
  void bookedWithoutApprovalWhenPolicySaysAllow() {
    doAnswer(inv -> policy(inv.getArgument(0), false)).when(activities).evaluatePolicy(any());
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("BOOKED");
    assertThat(statuses())
        .containsExactly(
            TripStatus.PLANNING, TripStatus.APPROVED, TripStatus.BOOKING, TripStatus.BOOKED);
    assertThat(transitions.get(1).getSelectedBundleId()).isNotBlank();
  }

  @Test
  void rejectedTripsAreCancelledAndNeverBooked() {
    TripWorkflow workflow = start();
    env.sleep(java.time.Duration.ofSeconds(1));
    workflow.approvalDecided(
        new TripPlanning.ApprovalDecision(
            "apr_01ARZ3NDEKTSV4RRFFQ69G5FAV", "REJECTED", "human/bob", "attend remotely"));
    TripWorkflow.Outcome outcome = result(workflow);
    assertThat(outcome.finalStatus()).isEqualTo("CANCELLED");
    assertThat(statuses())
        .containsExactly(TripStatus.PLANNING, TripStatus.AWAITING_APPROVAL, TripStatus.CANCELLED);
    assertThat(transitions.get(2).getReason()).contains("human/bob");
    verify(activities, never()).createOrder(any());
  }

  @Test
  void aLostSignalIsRecoveredByReReadingTheTrip() {
    when(activities.loadTrip(anyString(), anyString()))
        .thenReturn(trip(TripStatus.SUBMITTED, true))
        .thenReturn(
            trip(TripStatus.AWAITING_APPROVAL, true).toBuilder()
                .setApprovalId("apr_01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .setApprovalStatus("APPROVED")
                .build());
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("BOOKED");
    assertThat(statuses())
        .contains(TripStatus.AWAITING_APPROVAL, TripStatus.APPROVED, TripStatus.BOOKED);
  }

  @Test
  void noDecisionWithinTheTimeoutFailsTheTrip() {
    when(activities.loadTrip(anyString(), anyString()))
        .thenReturn(trip(TripStatus.SUBMITTED, true))
        .thenReturn(
            trip(TripStatus.AWAITING_APPROVAL, true).toBuilder()
                .setApprovalStatus("PENDING")
                .build());
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("FAILED");
    assertThat(outcome.failureStage()).isEqualTo("APPROVAL");
    assertThat(outcome.failureCode()).isEqualTo("APPROVAL_TIMED_OUT");
    assertThat(statuses().getLast()).isEqualTo(TripStatus.FAILED);
  }

  @Test
  void noOffersFailsAtSearchWithTheSupplierReason() {
    when(activities.search(any()))
        .thenReturn(
            SearchAirResponse.newBuilder()
                .setSearchSessionId("srch_x")
                .addErrors(
                    SupplierError.newBuilder()
                        .setProvider("sandbox-air")
                        .setCode("UNAVAILABLE")
                        .setRetryable(true))
                .build());
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("FAILED");
    assertThat(outcome.failureStage()).isEqualTo("SEARCH");
    assertThat(outcome.failureCode()).isEqualTo("NO_OFFERS");
    assertThat(statuses()).containsExactly(TripStatus.PLANNING, TripStatus.FAILED);
    assertThat(transitions.get(1).getReason()).contains("sandbox-air: UNAVAILABLE");
  }

  @Test
  void everyCandidateDeniedFailsAtPolicy() {
    doAnswer(
            inv -> {
              EvaluateTripRequest r = inv.getArgument(0);
              EvaluateTripResponse.Builder b =
                  EvaluateTripResponse.newBuilder().setEvaluationId("dec_x");
              r.getCandidatesList()
                  .forEach(
                      c ->
                          b.addCandidates(
                              CandidateDecision.newBuilder()
                                  .setBundleId(c.getBundleId())
                                  .setDecision(
                                      decision(Outcome.DENY, false, "CABIN_NOT_PERMITTED"))));
              return b.build();
            })
        .when(activities)
        .evaluatePolicy(any());
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.failureStage()).isEqualTo("POLICY");
    assertThat(outcome.failureCode()).isEqualTo("ALL_CANDIDATES_DENIED");
    assertThat(transitions.getLast().getReason()).contains("CABIN_NOT_PERMITTED");
    verify(activities, never()).optimize(any());
  }

  @Test
  void nothingFeasibleFailsAtOptimization() {
    doAnswer(inv -> optimized(inv.getArgument(0), "")).when(activities).optimize(any());
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.failureStage()).isEqualTo("OPTIMIZATION");
    assertThat(outcome.failureCode()).isEqualTo("NO_FEASIBLE_CANDIDATE");
  }

  @Test
  void aFailedOrderFailsTheTripWithTheOrdersReason() {
    doAnswer(inv -> policy(inv.getArgument(0), false)).when(activities).evaluatePolicy(any());
    when(activities.createOrder(any()))
        .thenReturn(order(OrderStatus.FAILED, "SEAT_NO_LONGER_AVAILABLE"));
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("FAILED");
    assertThat(outcome.failureStage()).isEqualTo("BOOKING");
    assertThat(outcome.failureCode()).isEqualTo("SEAT_NO_LONGER_AVAILABLE");
    assertThat(statuses())
        .containsExactly(
            TripStatus.PLANNING, TripStatus.APPROVED, TripStatus.BOOKING, TripStatus.FAILED);
  }

  @Test
  void freeTextIsUnderstoodBeforePlanning() {
    when(activities.loadTrip(anyString(), anyString()))
        .thenReturn(trip(TripStatus.SUBMITTED, false));
    doAnswer(inv -> policy(inv.getArgument(0), false)).when(activities).evaluatePolicy(any());

    TripWorkflow.Outcome outcome = result(start());

    assertThat(outcome.finalStatus()).isEqualTo("BOOKED");
    ArgumentCaptor<ExtractIntentRequest> extract =
        ArgumentCaptor.forClass(ExtractIntentRequest.class);
    verify(activities).extractIntent(extract.capture());
    assertThat(extract.getValue().getRequestText()).contains("BOS to SEA");
    assertThat(extract.getValue().getTimezone()).isEqualTo("America/New_York");
    assertThat(extract.getValue().getReferenceTime().getSeconds()).isPositive();
    assertThat(extract.getValue().getCtx().getPrincipal().getId())
        .isEqualTo("agent/trip-planner/v1");
    ArgumentCaptor<ApplyIntentExtractionRequest> apply =
        ArgumentCaptor.forClass(ApplyIntentExtractionRequest.class);
    verify(activities).applyIntentExtraction(apply.capture());
    assertThat(apply.getValue().getResult()).isEqualTo("EXTRACTED");
    assertThat(apply.getValue().getIntent().getDestination()).isEqualTo("SEA");
    assertThat(apply.getValue().getCall().getCallId()).startsWith("llm_");
    assertThat(statuses())
        .containsExactly(
            TripStatus.PLANNING, TripStatus.APPROVED, TripStatus.BOOKING, TripStatus.BOOKED);
  }

  @Test
  void aTripWithNeitherIntentNorTextFailsWithoutCallingAnyone() {
    when(activities.loadTrip(anyString(), anyString()))
        .thenReturn(trip(TripStatus.SUBMITTED, false).toBuilder().clearRequestText().build());
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.failureStage()).isEqualTo("INTENT");
    assertThat(outcome.failureCode()).isEqualTo("INTENT_REQUIRED");
    verify(activities, never()).extractIntent(any());
    verify(activities, never()).search(any());
  }

  @Test
  void unclearTextFailsWithTheClarifyingQuestionAndIsLedgered() {
    when(activities.loadTrip(anyString(), anyString()))
        .thenReturn(trip(TripStatus.SUBMITTED, false));
    when(activities.extractIntent(any()))
        .thenReturn(
            extracted(ExtractIntentResponse.Result.NEEDS_CLARIFICATION).toBuilder()
                .clearIntent()
                .addMissingFields("travel_date")
                .setClarifyingQuestion("Which day do you need to be in Seattle?")
                .build());
    when(activities.applyIntentExtraction(any())).thenReturn(trip(TripStatus.SUBMITTED, false));

    TripWorkflow.Outcome outcome = result(start());

    assertThat(outcome.finalStatus()).isEqualTo("FAILED");
    assertThat(outcome.failureStage()).isEqualTo("INTENT");
    assertThat(outcome.failureCode()).isEqualTo("NEEDS_CLARIFICATION");
    assertThat(transitions.getLast().getReason())
        .isEqualTo("Which day do you need to be in Seattle?");
    ArgumentCaptor<ApplyIntentExtractionRequest> apply =
        ArgumentCaptor.forClass(ApplyIntentExtractionRequest.class);
    verify(activities).applyIntentExtraction(apply.capture());
    assertThat(apply.getValue().getResult()).isEqualTo("NEEDS_CLARIFICATION");
    assertThat(apply.getValue().getMissingFieldsList()).containsExactly("travel_date");
    verify(activities, never()).search(any());
  }

  @Test
  void anUnreachableGatewayFailsTheTripAtIntentNotSilently() {
    when(activities.loadTrip(anyString(), anyString()))
        .thenReturn(trip(TripStatus.SUBMITTED, false));
    when(activities.extractIntent(any()))
        .thenThrow(ApplicationFailure.newNonRetryableFailure("gateway down", "UNAVAILABLE"));
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.failureStage()).isEqualTo("INTENT");
    assertThat(outcome.failureCode()).isEqualTo("ACTIVITY_UNAVAILABLE");
    verify(activities, never()).search(any());
  }

  @Test
  void theExplanationTravelsWithThePlan() {
    doAnswer(inv -> policy(inv.getArgument(0), false)).when(activities).evaluatePolicy(any());
    result(start());
    ArgumentCaptor<ExplainTripRequest> explain = ArgumentCaptor.forClass(ExplainTripRequest.class);
    verify(activities).explain(explain.capture());
    assertThat(explain.getValue().getSelected().getBundleId())
        .isEqualTo("bdl_" + OFFER_CHEAP.substring(4));
    assertThat(explain.getValue().getCandidatesSearched()).isEqualTo(3);
    assertThat(explain.getValue().getCandidatesPermitted()).isEqualTo(2);
    assertThat(explain.getValue().getPolicyDecision().getDecisionId()).startsWith("pd_");
    assertThat(transitions.get(1).getTo()).isEqualTo(TripStatus.APPROVED);
    assertThat(transitions.get(1).getExplanation()).isEqualTo("Chosen because cheapest.");
  }

  @Test
  void narrationOutageNeverBlocksBooking() {
    doAnswer(inv -> policy(inv.getArgument(0), false)).when(activities).evaluatePolicy(any());
    when(activities.explain(any()))
        .thenThrow(ApplicationFailure.newNonRetryableFailure("budget", "RESOURCE_EXHAUSTED"));
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("BOOKED");
    assertThat(transitions.get(1).getExplanation()).isEmpty();
  }

  @Test
  void anAlreadyBookedTripIsReportedNotReplanned() {
    when(activities.loadTrip(anyString(), anyString()))
        .thenReturn(
            trip(TripStatus.BOOKED, true).toBuilder()
                .setOrderId("ord_01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .build());
    TripWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("BOOKED");
    assertThat(outcome.orderId()).isEqualTo("ord_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    assertThat(transitions).isEmpty();
    verify(activities, never()).search(any());
  }

  // ------------------------------------------------------------------ helpers

  private TripWorkflow start() {
    TripWorkflow workflow =
        client.newWorkflowStub(
            TripWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TripPlanning.TASK_QUEUE)
                .setWorkflowId(TRIP)
                .build());
    WorkflowClient.start(workflow::run, new TripPlanning.Input(TENANT, TRIP));
    return workflow;
  }

  private static TripWorkflow.Outcome result(TripWorkflow workflow) {
    return WorkflowStub.fromTyped(workflow).getResult(TripWorkflow.Outcome.class);
  }

  private List<TripStatus> statuses() {
    List<TripStatus> result = new ArrayList<>();
    transitions.forEach(t -> result.add(t.getTo()));
    return result;
  }

  private static Trip trip(TripStatus status, boolean withIntent) {
    Trip.Builder b =
        Trip.newBuilder()
            .setTripId(TRIP)
            .setTenantId(TENANT)
            .setTravelerId("emp_1001")
            .setStatus(status)
            .setTraveler(
                TravelerSnapshot.newBuilder()
                    .setTravelerId("emp_1001")
                    .setGivenName("Alice")
                    .setFamilyName("Nguyen")
                    .setEmail("alice@acme.example"));
    if (!withIntent) {
      b.setRequestText("Fly BOS to SEA on 2026-10-06, back 2026-10-08, hotel needed");
    }
    if (withIntent) {
      b.setIntent(
          TravelIntent.newBuilder()
              .setOrigin("BOS")
              .setDestination("SEA")
              .setEarliestDeparture(ts(1_791_000_000L))
              .setArrivalDeadline(ts(1_791_030_000L))
              .setReturnAfter(ts(1_791_100_000L))
              .setLatestReturn(ts(1_791_140_000L))
              .setTravelers(1));
    }
    return b.build();
  }

  private static ExtractIntentResponse extracted(ExtractIntentResponse.Result result) {
    return ExtractIntentResponse.newBuilder()
        .setResult(result)
        .setIntent(trip(TripStatus.SUBMITTED, true).getIntent())
        .setConfidence(0.9)
        .addAssumptions("earliest departure assumed 06:00 local")
        .setCall(
            ModelCall.newBuilder()
                .setCallId("llm_01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .setProvider("fake")
                .setModel("fake-rules-v1")
                .setPromptId("intent-extraction")
                .setPromptVersion(1))
        .build();
  }

  private static SearchAirResponse threeOffers() {
    return SearchAirResponse.newBuilder()
        .setSearchSessionId("srch_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .addOffers(offer(OFFER_CHEAP, 47500))
        .addOffers(offer(OFFER_BIZ, 90000))
        .addOffers(offer(OFFER_PRICEY, 70000))
        .build();
  }

  private static Offer offer(String id, long cents) {
    return Offer.newBuilder()
        .setOfferId(id)
        .setProvider("sandbox-air")
        .setProviderOfferId("SBX-" + id)
        .setType(OfferType.AIR)
        .setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(cents))
        .setAir(AirOffer.newBuilder())
        .build();
  }

  /** cheap -> ALLOW (approval when asked), biz -> DENY, pricey -> ALLOW_WITH_TRAVELER_PAYMENT. */
  private static EvaluateTripResponse policy(
      EvaluateTripRequest request, boolean cheapNeedsApproval) {
    EvaluateTripResponse.Builder b =
        EvaluateTripResponse.newBuilder().setEvaluationId("dec_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    request
        .getCandidatesList()
        .forEach(
            c -> {
              String id = c.getBundleId();
              PolicyDecision d =
                  id.endsWith("FA1")
                      ? decision(
                          cheapNeedsApproval ? Outcome.ALLOW_WITH_APPROVAL : Outcome.ALLOW,
                          cheapNeedsApproval,
                          "TOTAL_ABOVE_APPROVAL_THRESHOLD")
                      : id.endsWith("FA2")
                          ? decision(Outcome.DENY, false, "CABIN_NOT_PERMITTED")
                          : decision(
                              Outcome.ALLOW_WITH_TRAVELER_PAYMENT,
                              false,
                              "FARE_ABOVE_POLICY_CEILING");
              b.addCandidates(CandidateDecision.newBuilder().setBundleId(id).setDecision(d));
            });
    return b.build();
  }

  private static PolicyDecision decision(Outcome outcome, boolean requiresApproval, String code) {
    PolicyDecision.Builder b =
        PolicyDecision.newBuilder()
            .setDecisionId("pd_01ARZ3NDEKTSV4RRFFQ69G5FA" + outcome.getNumber())
            .setPolicyId("US_STANDARD_TRAVEL")
            .setPolicyVersion(1)
            .setOutcome(outcome)
            .setRequiresApproval(requiresApproval);
    if (outcome != Outcome.ALLOW) {
      b.addReasons(
          ReasonCode.newBuilder().setCode(code).setRuleId("RULE").setMessage("because " + code));
    }
    if (requiresApproval) {
      b.addApprovers(ApproverRequirement.newBuilder().setRole("MANAGER").setReasonCode(code));
    }
    return b.build();
  }

  private static OptimizeTripResponse optimized(OptimizeTripRequest request, String selected) {
    OptimizeTripResponse.Builder b =
        OptimizeTripResponse.newBuilder()
            .setOptimizationRunId("opt_01ARZ3NDEKTRSV4RRFFQ69G5FAV".replace("TR", "TS"))
            .setSelectedBundleId(selected)
            .setSolver("test");
    int rank = 1;
    for (var c : request.getCandidatesList()) {
      b.addRanking(
          RankedCandidate.newBuilder()
              .setBundleId(c.getBundleId())
              .setFeasible(!selected.isEmpty())
              .setScore(selected.isEmpty() ? 0 : 90 - rank)
              .setRank(rank++));
    }
    return b.build();
  }

  private static Order order(OrderStatus status, String failureCode) {
    return Order.newBuilder()
        .setOrderId("ord_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setTripId(TRIP)
        .setStatus(status)
        .setFailureCode(failureCode)
        .setCompensated(status != OrderStatus.CONFIRMED)
        .build();
  }

  private static Timestamp ts(long seconds) {
    return Timestamp.newBuilder().setSeconds(seconds).build();
  }
}
