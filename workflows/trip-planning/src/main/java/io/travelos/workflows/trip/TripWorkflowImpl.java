package io.travelos.workflows.trip;

import com.google.protobuf.Timestamp;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.llm.v1.ExplainTripRequest;
import io.travelos.contracts.llm.v1.ExplainTripResponse;
import io.travelos.contracts.llm.v1.ExtractIntentRequest;
import io.travelos.contracts.llm.v1.ExtractIntentResponse;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.optimization.v1.ConstraintSet;
import io.travelos.contracts.optimization.v1.LearningInputs;
import io.travelos.contracts.optimization.v1.OptimizationPreferences;
import io.travelos.contracts.optimization.v1.OptimizeTripRequest;
import io.travelos.contracts.optimization.v1.OptimizeTripResponse;
import io.travelos.contracts.optimization.v1.RankedCandidate;
import io.travelos.contracts.optimization.v1.Weights;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.contracts.policy.v1.CandidateDecision;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.trip.v1.ApplyIntentExtractionRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.workflows.TripPlanning;
import io.travelos.workflows.TripPlanning.ApprovalDecision;
import io.travelos.workflows.learning.LearningActivities;
import io.travelos.workflows.learning.LearningResolution;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The trip lifecycle as durable code:
 *
 * <pre>
 * load trip -> [understand free text -> freeze intent] -> PLANNING -> search -> policy (filter
 * DENY) -> optimize -> [narrate] -> [AWAITING_APPROVAL -> wait for a person] -> APPROVED ->
 * BOOKING -> create order -> BOOKED
 * </pre>
 *
 * Every step is an idempotent activity, so a worker dying mid-way resumes without side effects. The
 * language model has exactly two bounded roles here (ADR-0009): turning free text into a structured
 * intent that Travel Core validates and freezes, and narrating the decision from its evidence. It
 * decides nothing: policy is deterministic, optimization is a solver, booking is a saga. Narration
 * failing never blocks a trip.
 */
public class TripWorkflowImpl implements TripWorkflow {

  private static final Logger log = Workflow.getLogger(TripWorkflowImpl.class);

  static final String PRINCIPAL = "agent/trip-planner/v1";
  static final String DEFAULT_TIMEZONE = "America/New_York";
  static final Duration APPROVAL_TIMEOUT = Duration.ofHours(48);

  /** How often to re-read the trip while waiting, in case a signal was lost. */
  static final Duration APPROVAL_POLL = Duration.ofHours(1);

  private final TripActivities activities =
      Workflow.newActivityStub(
          TripActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(60))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofSeconds(30))
                      .setMaximumAttempts(6)
                      .build())
              .build());

  /** Model calls take longer and are worth fewer retries. */
  private final TripActivities understanding =
      Workflow.newActivityStub(
          TripActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(90))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(2))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofSeconds(30))
                      .setMaximumAttempts(4)
                      .build())
              .build());

  /** Narration is optional: two tries, then the trip proceeds without it. */
  private final TripActivities narration =
      Workflow.newActivityStub(
          TripActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(60))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(2))
                      .setMaximumAttempts(2)
                      .build())
              .build());

  private final TripActivities booking =
      Workflow.newActivityStub(
          TripActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(3))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(2))
                      .setMaximumInterval(Duration.ofMinutes(1))
                      .setMaximumAttempts(8)
                      .build())
              .build());

  /** Slice 5: learning inputs, resolved once per attempt and pinned by the history (3 tries). */
  private final LearningActivities learning = LearningResolution.stub();

  private TripPlanning.Stage stage = TripPlanning.Stage.LOADING;
  private @Nullable ApprovalDecision decision;

  @Override
  public Outcome run(TripPlanning.Input input) {
    String tenant = input.tenantId();
    String tripId = input.tripId();
    Trip trip = activities.loadTrip(tenant, tripId);

    // Restarted after completion: report what already happened.
    switch (trip.getStatus()) {
      case BOOKED -> {
        stage = TripPlanning.Stage.BOOKED;
        return new Outcome(tripId, "BOOKED", trip.getOrderId(), null, null);
      }
      case FAILED -> {
        stage = TripPlanning.Stage.FAILED;
        return new Outcome(tripId, "FAILED", null, trip.getFailureStage(), trip.getFailureCode());
      }
      case CANCELLED -> {
        stage = TripPlanning.Stage.CANCELLED;
        return new Outcome(tripId, "CANCELLED", null, null, null);
      }
      default -> {}
    }

    try {
      // ---- understand
      if (!hasIntent(trip)) {
        if (trip.getRequestText().isBlank()) {
          return fail(
              tenant,
              tripId,
              "INTENT",
              "INTENT_REQUIRED",
              "the trip has neither a structured intent nor a request text");
        }
        stage = TripPlanning.Stage.UNDERSTANDING;
        ExtractIntentResponse extracted =
            understanding.extractIntent(extractRequest(tenant, tripId, trip));
        Trip applied = activities.applyIntentExtraction(applyRequest(tenant, tripId, extracted));
        if (applied.getStatus() == TripStatus.FAILED) {
          // Travel Core refused the understood intent (e.g. HOTEL_DETAILS_INSUFFICIENT) and has
          // already recorded stage, code and reason; nothing to add and nothing to plan.
          stage = TripPlanning.Stage.FAILED;
          return new Outcome(
              tripId, "FAILED", null, applied.getFailureStage(), applied.getFailureCode());
        }
        switch (extracted.getResult()) {
          case EXTRACTED -> trip = applied;
          case NEEDS_CLARIFICATION -> {
            return fail(
                tenant,
                tripId,
                "INTENT",
                "NEEDS_CLARIFICATION",
                extracted.getClarifyingQuestion().isBlank()
                    ? "the request needs more detail before it can be planned"
                    : extracted.getClarifyingQuestion());
          }
          default -> {
            return fail(
                tenant,
                tripId,
                "INTENT",
                "NOT_A_TRAVEL_REQUEST",
                "the request does not describe a trip");
          }
        }
        if (!hasIntent(trip)) {
          return fail(
              tenant,
              tripId,
              "INTENT",
              "INTENT_NOT_FROZEN",
              "travel core did not accept the intent");
        }
      }
      TravelIntent intent = trip.getIntent();

      transition(tenant, tripId, TripStatus.PLANNING, b -> b.setReason("planning started"));

      // ---- Slice 3: an itinerary of legs, stays and transfers takes its own path
      if (intent.hasItinerary() && intent.getItinerary().getLegsCount() > 0) {
        return new ItineraryFlow(activities, booking, new Bridge(), log).run(tenant, tripId, trip);
      }

      // ---- search
      stage = TripPlanning.Stage.SEARCHING;
      SearchAirResponse search = activities.search(searchRequest(tenant, tripId, intent));
      if (search.getOffersCount() == 0) {
        String detail =
            search.getErrorsCount() == 0
                ? "no supplier returned offers"
                : search.getErrors(0).getProvider() + ": " + search.getErrors(0).getCode();
        return fail(tenant, tripId, "SEARCH", "NO_OFFERS", detail);
      }
      List<Bundle> bundles = new ArrayList<>();
      for (Offer offer : search.getOffersList()) {
        bundles.add(
            Bundle.newBuilder()
                .setBundleId("bdl_" + offer.getOfferId().substring("off_".length()))
                .addOffers(offer)
                .setTotal(offer.getTotal())
                .build());
      }

      // ---- policy
      stage = TripPlanning.Stage.EVALUATING_POLICY;
      EvaluateTripResponse policy =
          activities.evaluatePolicy(
              EvaluateTripRequest.newBuilder()
                  .setCtx(ctx(tenant, tripId, ""))
                  .setTripId(tripId)
                  .setTravelerId(trip.getTravelerId())
                  .setIntent(intent)
                  .addAllCandidates(bundles)
                  .build());
      Map<String, PolicyDecision> decisions = new HashMap<>();
      for (CandidateDecision cd : policy.getCandidatesList()) {
        decisions.put(cd.getBundleId(), cd.getDecision());
      }
      List<Bundle> permitted =
          bundles.stream()
              .filter(b -> decisions.containsKey(b.getBundleId()))
              .filter(
                  b ->
                      decisions.get(b.getBundleId()).getOutcome()
                          != io.travelos.contracts.policy.v1.Outcome.DENY)
              .toList();
      if (permitted.isEmpty()) {
        return fail(tenant, tripId, "POLICY", "ALL_CANDIDATES_DENIED", denialSummary(policy));
      }

      // ---- optimize (with the learning inputs pinned to this attempt; baseline when unavailable)
      stage = TripPlanning.Stage.OPTIMIZING;
      LearningInputs learned =
          LearningResolution.resolve(
              learning, log, tenant, tripId, trip.getTravelerId(), "PLANNING");
      OptimizeTripResponse optimized =
          activities.optimize(
              OptimizeTripRequest.newBuilder()
                  .setCtx(ctx(tenant, tripId, ""))
                  .setTripId(tripId)
                  .addAllCandidates(permitted)
                  .setConstraints(constraints(intent))
                  .setPreferences(
                      OptimizationPreferences.newBuilder()
                          .setWeights(
                              Weights.newBuilder()
                                  .setCost(0.4)
                                  .setTime(0.25)
                                  .setRisk(0.15)
                                  .setPreference(0.1)
                                  .setExperience(0.1)))
                  .setLearning(learned)
                  .build());
      if (optimized.getSelectedBundleId().isBlank()) {
        return fail(
            tenant,
            tripId,
            "OPTIMIZATION",
            "NO_FEASIBLE_CANDIDATE",
            "no permitted candidate satisfies the trip's constraints");
      }
      Bundle selected =
          permitted.stream()
              .filter(b -> b.getBundleId().equals(optimized.getSelectedBundleId()))
              .findFirst()
              .orElseThrow();
      PolicyDecision selectedDecision = decisions.get(selected.getBundleId());
      Money total = selected.getTotal();

      // ---- narrate (optional)
      final String explanation =
          explain(
              tenant,
              tripId,
              intent,
              selected,
              optimized,
              selectedDecision,
              bundles.size(),
              permitted.size());

      // ---- approval
      String approvalId = null;
      if (selectedDecision.getRequiresApproval()) {
        stage = TripPlanning.Stage.AWAITING_APPROVAL;
        String role =
            selectedDecision.getApproversCount() > 0
                ? selectedDecision.getApprovers(0).getRole()
                : "MANAGER";
        Trip awaiting =
            transition(
                tenant,
                tripId,
                TripStatus.AWAITING_APPROVAL,
                b -> {
                  b.setSelectedBundleId(selected.getBundleId())
                      .setOptimizationRunId(optimized.getOptimizationRunId())
                      .setPolicyDecisionId(selectedDecision.getDecisionId())
                      .setTotal(total)
                      .setApproverRole(role)
                      .setReason(reasonSummary(selectedDecision));
                  if (explanation != null) {
                    b.setExplanation(explanation);
                  }
                });
        approvalId = awaiting.getApprovalId();
        String verdict = awaitDecision(tenant, tripId);
        if (verdict == null) {
          return fail(
              tenant,
              tripId,
              "APPROVAL",
              "APPROVAL_TIMED_OUT",
              "no decision within " + APPROVAL_TIMEOUT);
        }
        if (!"APPROVED".equals(verdict)) {
          String by = decision == null ? "a manager" : decision.decidedBy();
          transition(tenant, tripId, TripStatus.CANCELLED, b -> b.setReason("rejected by " + by));
          stage = TripPlanning.Stage.CANCELLED;
          return new Outcome(tripId, "CANCELLED", null, null, null);
        }
        transition(
            tenant,
            tripId,
            TripStatus.APPROVED,
            b ->
                b.setReason(
                    "approved by " + (decision == null ? "a manager" : decision.decidedBy())));
      } else {
        transition(
            tenant,
            tripId,
            TripStatus.APPROVED,
            b -> {
              b.setSelectedBundleId(selected.getBundleId())
                  .setOptimizationRunId(optimized.getOptimizationRunId())
                  .setPolicyDecisionId(selectedDecision.getDecisionId())
                  .setTotal(total)
                  .setReason("in policy, no approval required");
              if (explanation != null) {
                b.setExplanation(explanation);
              }
            });
      }

      // ---- booking
      stage = TripPlanning.Stage.BOOKING;
      transition(tenant, tripId, TripStatus.BOOKING, b -> b.setReason("booking"));
      CreateOrderCommand.Builder command =
          CreateOrderCommand.newBuilder()
              .setCtx(ctx(tenant, tripId, tripId + ":CREATE-ORDER:1"))
              .setTripId(tripId)
              .setTravelerId(trip.getTravelerId())
              .setBundle(selected)
              .setPolicyDecisionId(selectedDecision.getDecisionId())
              .setOptimizationRunId(optimized.getOptimizationRunId())
              .addPassengers(
                  Passenger.newBuilder()
                      .setGivenName(trip.getTraveler().getGivenName())
                      .setFamilyName(trip.getTraveler().getFamilyName())
                      .setEmail(trip.getTraveler().getEmail()))
              .setPaymentToken(paymentToken());
      if (approvalId != null) {
        command.setApprovalId(approvalId);
      }
      Order order = booking.createOrder(command.build());
      if (order.getStatus() != OrderStatus.CONFIRMED) {
        String code =
            order.getFailureCode().isBlank()
                ? "ORDER_" + order.getStatus().name()
                : order.getFailureCode();
        return fail(
            tenant,
            tripId,
            "BOOKING",
            code,
            "order "
                + order.getOrderId()
                + " ended "
                + order.getStatus()
                + (order.getCompensated() ? " (compensated)" : ""));
      }
      transition(
          tenant,
          tripId,
          TripStatus.BOOKED,
          b -> b.setOrderId(order.getOrderId()).setReason("order confirmed"));
      stage = TripPlanning.Stage.BOOKED;
      return new Outcome(tripId, "BOOKED", order.getOrderId(), null, null);
    } catch (ActivityFailure e) {
      String code = failureCode(e);
      log.error("trip {} failed at {}: {}", tripId, stage, code);
      return fail(
          tenant,
          tripId,
          stageName(stage),
          code,
          e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
    }
  }

  @Override
  public void approvalDecided(TripPlanning.ApprovalDecision decision) {
    this.decision = decision;
  }

  @Override
  public TripPlanning.Stage stage() {
    return stage;
  }

  /** The itinerary flow's window into this workflow's helpers and state. */
  private final class Bridge implements ItineraryFlow.Host {
    @Override
    public Trip transition(
        String tenant,
        String tripId,
        TripStatus to,
        java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
      return TripWorkflowImpl.this.transition(tenant, tripId, to, customize);
    }

    @Override
    public @Nullable String awaitDecision(String tenant, String tripId) {
      return TripWorkflowImpl.this.awaitDecision(tenant, tripId);
    }

    @Override
    public TripPlanning.@Nullable ApprovalDecision decision() {
      return decision;
    }

    @Override
    public void forgetDecision() {
      decision = null;
    }

    @Override
    public Outcome fail(
        String tenant, String tripId, String stageName, String code, String message) {
      return TripWorkflowImpl.this.fail(tenant, tripId, stageName, code, message);
    }

    @Override
    public void stage(TripPlanning.Stage next) {
      stage = next;
    }

    @Override
    public String paymentToken() {
      return TripWorkflowImpl.this.paymentToken();
    }

    @Override
    public @Nullable String explain(
        String tenant,
        String tripId,
        TravelIntent intent,
        Bundle selected,
        List<RankedCandidate> ranking,
        PolicyDecision decision,
        int searched,
        int permitted) {
      return TripWorkflowImpl.this.explain(
          tenant,
          tripId,
          intent,
          selected,
          OptimizeTripResponse.newBuilder().addAllRanking(ranking).build(),
          decision,
          searched,
          permitted);
    }

    @Override
    public RequestContext ctx(String tenant, String tripId, String idempotencyKey) {
      return TripWorkflowImpl.ctx(tenant, tripId, idempotencyKey);
    }

    @Override
    public LearningInputs resolveLearning(String tenant, String tripId, String travelerId) {
      return LearningResolution.resolve(learning, log, tenant, tripId, travelerId, "PLANNING");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static boolean hasIntent(Trip trip) {
    return trip.hasIntent() && !trip.getIntent().getOrigin().isBlank();
  }

  private ExtractIntentRequest extractRequest(String tenant, String tripId, Trip trip) {
    long now = Workflow.currentTimeMillis();
    return ExtractIntentRequest.newBuilder()
        .setCtx(ctx(tenant, tripId, ""))
        .setTripId(tripId)
        .setRequestText(trip.getRequestText())
        .setReferenceTime(
            Timestamp.newBuilder().setSeconds(now / 1000).setNanos((int) (now % 1000) * 1_000_000))
        .setTimezone(defaultTimezone())
        .build();
  }

  private ApplyIntentExtractionRequest applyRequest(
      String tenant, String tripId, ExtractIntentResponse extracted) {
    ApplyIntentExtractionRequest.Builder b =
        ApplyIntentExtractionRequest.newBuilder()
            .setCtx(ctx(tenant, tripId, ""))
            .setTripId(tripId)
            .setResult(extracted.getResult().name())
            .addAllMissingFields(extracted.getMissingFieldsList())
            .setClarifyingQuestion(extracted.getClarifyingQuestion())
            .addAllAssumptions(extracted.getAssumptionsList())
            .setConfidence(extracted.getConfidence());
    if (extracted.hasIntent()) {
      b.setIntent(extracted.getIntent());
    }
    if (extracted.hasCall()) {
      b.setCall(extracted.getCall());
    }
    return b.build();
  }

  /** Best effort. The plan is the plan whether or not it gets a paragraph. */
  private @Nullable String explain(
      String tenant,
      String tripId,
      TravelIntent intent,
      Bundle selected,
      OptimizeTripResponse optimized,
      PolicyDecision selectedDecision,
      int searched,
      int permitted) {
    try {
      ExplainTripResponse response =
          narration.explain(
              ExplainTripRequest.newBuilder()
                  .setCtx(ctx(tenant, tripId, ""))
                  .setTripId(tripId)
                  .setAudience("TRAVELER")
                  .setIntent(intent)
                  .setSelected(selected)
                  .addAllRanking(optimized.getRankingList())
                  .setPolicyDecision(selectedDecision)
                  .setCandidatesSearched(searched)
                  .setCandidatesPermitted(permitted)
                  .build());
      return response.getExplanation().isBlank() ? null : response.getExplanation();
    } catch (ActivityFailure e) {
      log.warn(
          "trip {}: explanation unavailable ({}); continuing without it", tripId, failureCode(e));
      return null;
    }
  }

  /** Waits for the signal, re-reading the trip periodically so a lost signal only delays. */
  private @Nullable String awaitDecision(String tenant, String tripId) {
    Duration waited = Duration.ZERO;
    while (waited.compareTo(APPROVAL_TIMEOUT) < 0) {
      Duration slice =
          APPROVAL_POLL.compareTo(APPROVAL_TIMEOUT.minus(waited)) < 0
              ? APPROVAL_POLL
              : APPROVAL_TIMEOUT.minus(waited);
      Workflow.await(slice, () -> decision != null);
      if (decision != null) {
        return decision.decision();
      }
      waited = waited.plus(slice);
      Trip current = activities.loadTrip(tenant, tripId);
      if ("APPROVED".equals(current.getApprovalStatus())
          || "REJECTED".equals(current.getApprovalStatus())) {
        decision =
            new TripPlanning.ApprovalDecision(
                current.getApprovalId(), current.getApprovalStatus(), "human/unknown", null);
        return current.getApprovalStatus();
      }
    }
    return null;
  }

  private Outcome fail(
      String tenant, String tripId, String stageName, String code, String message) {
    try {
      transition(
          tenant,
          tripId,
          TripStatus.FAILED,
          b -> b.setFailureStage(stageName).setFailureCode(code).setReason(message));
    } catch (ActivityFailure e) {
      log.error("trip {}: could not record failure {} ({})", tripId, code, e.getMessage());
    }
    stage = TripPlanning.Stage.FAILED;
    return new Outcome(tripId, "FAILED", null, stageName, code);
  }

  private Trip transition(
      String tenant,
      String tripId,
      TripStatus to,
      java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
    TransitionTripRequest.Builder b =
        TransitionTripRequest.newBuilder()
            .setCtx(ctx(tenant, tripId, ""))
            .setTripId(tripId)
            .setTo(to);
    customize.accept(b);
    return activities.transition(b.build());
  }

  private static RequestContext ctx(String tenant, String tripId, String idempotencyKey) {
    return RequestContext.newBuilder()
        .setTenantId(tenant)
        .setCorrelationId(tripId)
        .setCausationId(Workflow.getInfo().getWorkflowId())
        .setIdempotencyKey(idempotencyKey)
        .setPrincipal(Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(PRINCIPAL))
        .build();
  }

  private static SearchAirRequest searchRequest(String tenant, String tripId, TravelIntent intent) {
    SearchAirRequest.Builder b =
        SearchAirRequest.newBuilder()
            .setCtx(ctx(tenant, tripId, ""))
            .setOrigin(intent.getOrigin())
            .setDestination(intent.getDestination())
            .setPassengers(Math.max(1, intent.getTravelers()))
            .addCabins(Cabin.ECONOMY)
            .addCabins(Cabin.PREMIUM_ECONOMY)
            .addCabins(Cabin.BUSINESS)
            .setOutboundDeparture(
                TimeWindow.newBuilder()
                    .setNotBefore(intent.getEarliestDeparture())
                    .setNotAfter(intent.getArrivalDeadline()));
    if (intent.hasReturnAfter()) {
      b.setReturnDeparture(
          TimeWindow.newBuilder()
              .setNotBefore(intent.getReturnAfter())
              .setNotAfter(intent.getLatestReturn()));
    }
    return b.build();
  }

  private static ConstraintSet constraints(TravelIntent intent) {
    ConstraintSet.Builder b =
        ConstraintSet.newBuilder().setArrivalDeadline(intent.getArrivalDeadline());
    if (intent.hasReturnAfter()) {
      b.setReturnAfter(intent.getReturnAfter());
    }
    return b.build();
  }

  private String paymentToken() {
    return Workflow.sideEffect(
        String.class,
        () -> System.getProperty("travelos.workflow.payment-token", "tok_corp_visa_sandbox"));
  }

  private String defaultTimezone() {
    return Workflow.sideEffect(
        String.class,
        () -> System.getProperty("travelos.workflow.default-timezone", DEFAULT_TIMEZONE));
  }

  private static String denialSummary(EvaluateTripResponse policy) {
    Map<String, Integer> codes = new HashMap<>();
    for (CandidateDecision cd : policy.getCandidatesList()) {
      cd.getDecision().getReasonsList().forEach(r -> codes.merge(r.getCode(), 1, Integer::sum));
    }
    return "every candidate was denied: " + codes;
  }

  private static String reasonSummary(PolicyDecision decision) {
    if (decision.getReasonsCount() == 0) {
      return "policy requires approval";
    }
    return decision.getReasons(0).getMessage();
  }

  private static String failureCode(ActivityFailure e) {
    if (e.getCause() instanceof ApplicationFailure af
        && af.getType() != null
        && !af.getType().isBlank()) {
      return "ACTIVITY_" + af.getType();
    }
    return "ACTIVITY_FAILED";
  }

  private static String stageName(TripPlanning.Stage stage) {
    return switch (stage) {
      case LOADING -> "CONTEXT";
      case UNDERSTANDING -> "INTENT";
      case SEARCHING -> "SEARCH";
      case EVALUATING_POLICY -> "POLICY";
      case OPTIMIZING -> "OPTIMIZATION";
      case AWAITING_APPROVAL -> "APPROVAL";
      case REVALIDATING -> "REVALIDATION";
      case COMPENSATING -> "COMPENSATION";
      default -> "BOOKING";
    };
  }
}
