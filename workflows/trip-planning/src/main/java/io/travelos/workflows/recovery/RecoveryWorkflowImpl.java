package io.travelos.workflows.recovery;

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
import io.travelos.contracts.disruption.v1.AffectedSegment;
import io.travelos.contracts.disruption.v1.ComponentChange;
import io.travelos.contracts.disruption.v1.Disruption;
import io.travelos.contracts.disruption.v1.DisruptionStatus;
import io.travelos.contracts.disruption.v1.DisruptionType;
import io.travelos.contracts.disruption.v1.RecordRecoveryDecisionRequest;
import io.travelos.contracts.disruption.v1.RecordRecoveryOutcomeRequest;
import io.travelos.contracts.disruption.v1.RecoveryDecision;
import io.travelos.contracts.disruption.v1.RecoveryOutcome;
import io.travelos.contracts.disruption.v1.RecoveryState;
import io.travelos.contracts.disruption.v1.RejectedCandidate;
import io.travelos.contracts.disruption.v1.SupplierResult;
import io.travelos.contracts.disruption.v1.TransitionDisruptionRequest;
import io.travelos.contracts.llm.v1.ExplainDisruptionRequest;
import io.travelos.contracts.llm.v1.ExplainDisruptionResponse;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.optimization.v1.ConstraintSet;
import io.travelos.contracts.optimization.v1.LearningInputs;
import io.travelos.contracts.optimization.v1.OptimizationPreferences;
import io.travelos.contracts.optimization.v1.OptimizeTripRequest;
import io.travelos.contracts.optimization.v1.OptimizeTripResponse;
import io.travelos.contracts.optimization.v1.RankedCandidate;
import io.travelos.contracts.optimization.v1.Weights;
import io.travelos.contracts.order.v1.ChangeOrderCommand;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderChange;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.contracts.policy.v1.CandidateDecision;
import io.travelos.contracts.policy.v1.EvaluateActionRequest;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.workflows.DisruptionRecovery;
import io.travelos.workflows.DisruptionRecovery.ApprovalDecision;
import io.travelos.workflows.DisruptionRecovery.Stage;
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
 * Autonomous disruption recovery as durable code:
 *
 * <pre>
 * load disruption/order/trip -> SEARCHING_ALTERNATIVES -> search -> policy (filter DENY) ->
 * OPTIMIZING -> optimize -> DECISION_READY (immutable decision record) -> policy on the ACTION
 * (order.change, incremental cost, constraints) -> AUTO_ALLOWED | HUMAN_REQUIRED (wait) ->
 * CHANGING -> ChangeOrder (idempotent by key) -> RESOLVED
 * </pre>
 *
 * The workflow asks; it never decides. Which itinerary: the optimizer over what policy permitted.
 * Whether the agent may change the order: the policy engine, alone (ADR-0003). The supplier's own
 * words and the LLM's narration are carried as text and influence nothing. Every step is an
 * idempotent activity, so a worker dying anywhere resumes without a second search, decision,
 * approval request or supplier change.
 */
public class RecoveryWorkflowImpl implements RecoveryWorkflow {

  private static final Logger log = Workflow.getLogger(RecoveryWorkflowImpl.class);

  static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);
  static final Duration APPROVAL_POLL = Duration.ofMinutes(30);
  static final String ACTION = "order.change";

  private final RecoveryActivities activities =
      Workflow.newActivityStub(
          RecoveryActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(60))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofSeconds(30))
                      // ~3 minutes of retries: an optimizer, policy or supplier outage shorter
                      // than that is a retry, not a failed recovery (the change step below
                      // gets the same budget)
                      .setMaximumAttempts(10)
                      .build())
              .build());

  private final RecoveryActivities narration =
      Workflow.newActivityStub(
          RecoveryActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(60))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(2))
                      .setMaximumAttempts(2)
                      .build())
              .build());

  private final RecoveryActivities changing =
      Workflow.newActivityStub(
          RecoveryActivities.class,
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

  private Stage stage = Stage.LOADING;
  private @Nullable ApprovalDecision decision;

  @Override
  public Outcome run(DisruptionRecovery.Input input) {
    String tenant = input.tenantId();
    String disruptionId = input.disruptionId();
    Disruption d = activities.loadDisruption(tenant, disruptionId);

    // Restarted after completion: report what already happened.
    switch (d.getStatus()) {
      case RESOLVED -> {
        stage = Stage.RESOLVED;
        return new Outcome(
            disruptionId,
            "RESOLVED",
            d.getOrderId(),
            d.getRecovery().getReplacementBundleId(),
            null,
            null);
      }
      case NO_ALTERNATIVE, FAILED, MANUAL_INTERVENTION_REQUIRED -> {
        stage = Stage.valueOf(d.getStatus().name());
        return new Outcome(
            disruptionId,
            d.getStatus().name(),
            d.getOrderId(),
            null,
            d.getRecovery().getFailureStage(),
            d.getRecovery().getFailureCode());
      }
      default -> {}
    }
    if (d.getTripId().isBlank() || d.getOrderId().isBlank()) {
      return fail(
          tenant,
          disruptionId,
          "MANUAL_INTERVENTION_REQUIRED",
          "IMPACT",
          "IMPACT_UNKNOWN",
          "the disruption is not tied to a trip and an order");
    }
    String tripId = d.getTripId();
    RecoveryState.Builder state = d.getRecovery().toBuilder();

    try {
      Order order = activities.loadOrder(tenant, tripId, d.getOrderId());
      Trip trip = activities.loadTrip(tenant, tripId);
      // Phase 6: a traveler's request moves the intent's window to what they asked for; policy and
      // the optimizer then judge the replacement against THAT window, with the person as the actor.
      TravelIntent intent = requestedIntent(trip.getIntent(), d.getAffected());
      boolean travelerRequest = d.getType() == DisruptionType.TRAVELER_REQUEST;
      Bundle original = originalItinerary(order);
      String trigger =
          travelerRequest
              ? "TRAVELER_REQUEST by "
                  + d.getAffected().getRequestedBy()
                  + ": move "
                  + d.getAffected().getFlightNumber()
                  + " "
                  + d.getAffected().getOrigin()
                  + "-"
                  + d.getAffected().getDestination()
                  + " to "
                  + iso(d.getAffected().getRequestedNotBefore())
                  + (d.getAffected().hasRequestedNotAfter()
                      ? ".." + iso(d.getAffected().getRequestedNotAfter())
                      : "")
              : d.getType().name()
                  + " "
                  + d.getAffected().getFlightNumber()
                  + " "
                  + d.getAffected().getOrigin()
                  + "-"
                  + d.getAffected().getDestination()
                  + " (supplier event "
                  + d.getSupplierEventId()
                  + ")";

      // ---- search
      stage = Stage.SEARCHING_ALTERNATIVES;
      transition(
          tenant,
          disruptionId,
          DisruptionStatus.SEARCHING_ALTERNATIVES,
          b -> b.setReason("searching replacements after " + trigger));
      SearchAirResponse search =
          activities.search(searchRequest(tenant, tripId, intent, Workflow.currentTimeMillis()));
      String currentOffer = currentProviderOfferId(order);
      List<Bundle> bundles = new ArrayList<>();
      for (Offer offer : search.getOffersList()) {
        if (offer.getProviderOfferId().equals(currentOffer)) {
          continue; // the supplier still lists what we hold; it is not a replacement
        }
        bundles.add(
            Bundle.newBuilder()
                .setBundleId("bdl_" + offer.getOfferId().substring("off_".length()))
                .addOffers(offer)
                .setTotal(offer.getTotal())
                .build());
      }
      if (bundles.isEmpty()) {
        String detail =
            search.getErrorsCount() == 0
                ? "no supplier returned a replacement"
                : search.getErrors(0).getProvider() + ": " + search.getErrors(0).getCode();
        return fail(tenant, disruptionId, "NO_ALTERNATIVE", "SEARCH", "NO_OFFERS", detail);
      }

      // ---- policy: deterministic filter
      stage = Stage.EVALUATING_POLICY;
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
      List<Bundle> permitted = new ArrayList<>();
      List<RejectedCandidate> rejected = new ArrayList<>();
      for (Bundle b : bundles) {
        PolicyDecision pd = decisions.get(b.getBundleId());
        if (pd != null && pd.getOutcome() != io.travelos.contracts.policy.v1.Outcome.DENY) {
          permitted.add(b);
        } else {
          RejectedCandidate.Builder r =
              RejectedCandidate.newBuilder()
                  .setBundleId(b.getBundleId())
                  .setStage("POLICY")
                  .setTotal(b.getTotal());
          if (pd != null) {
            pd.getReasonsList().forEach(reason -> r.addReasonCodes(reason.getCode()));
          } else {
            r.addReasonCodes("NOT_EVALUATED");
          }
          rejected.add(r.build());
        }
      }
      if (permitted.isEmpty()) {
        return fail(
            tenant,
            disruptionId,
            "NO_ALTERNATIVE",
            "POLICY",
            "ALL_CANDIDATES_DENIED",
            "every replacement was denied by policy");
      }

      // ---- optimize
      stage = Stage.OPTIMIZING;
      transition(tenant, disruptionId, DisruptionStatus.OPTIMIZING, b -> b.setReason("optimizing"));
      LearningInputs learned =
          LearningResolution.resolve(
              learning, log, tenant, tripId, trip.getTravelerId(), "RECOVERY");
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
      int feasible = 0;
      for (RankedCandidate rc : optimized.getRankingList()) {
        if (rc.getFeasible()) {
          feasible++;
        } else {
          rejected.add(
              RejectedCandidate.newBuilder()
                  .setBundleId(rc.getBundleId())
                  .setStage("OPTIMIZATION")
                  .addAllReasonCodes(rc.getInfeasibilityReasonsList())
                  .build());
        }
      }
      if (optimized.getSelectedBundleId().isBlank()) {
        return fail(
            tenant,
            disruptionId,
            "NO_ALTERNATIVE",
            "OPTIMIZATION",
            "NO_FEASIBLE_CANDIDATE",
            "no permitted replacement satisfies the trip's constraints");
      }
      Bundle selected =
          permitted.stream()
              .filter(b -> b.getBundleId().equals(optimized.getSelectedBundleId()))
              .findFirst()
              .orElseThrow();
      RankedCandidate selectedRanking =
          optimized.getRankingList().stream()
              .filter(rc -> rc.getBundleId().equals(selected.getBundleId()))
              .findFirst()
              .orElse(RankedCandidate.getDefaultInstance());
      Money incremental =
          Money.newBuilder()
              .setCurrency(order.getTotal().getCurrency())
              .setAmountMinor(
                  selected.getTotal().getAmountMinor() - order.getTotal().getAmountMinor())
              .build();

      // ---- Slice 3: an itinerary drags its dependants along (transfers, stays, the next leg)
      Bundle replacement = selected;
      List<ComponentChange> componentChanges = List.of();
      OrderItem affectedItem = intent.hasItinerary() ? Dependants.affectedItem(order, d) : null;
      if (affectedItem != null) {
        Dependants.Plan plan =
            Dependants.plan(
                activities,
                key0 -> ctx(tenant, tripId, key0),
                intent.getItinerary(),
                order,
                affectedItem,
                selected.getOffers(0));
        replacement = plan.replacement();
        incremental = plan.incremental();
        componentChanges = plan.changes();
        state.clearAffectedComponentIds().addAllAffectedComponentIds(plan.affectedComponentIds());
      }
      final Bundle proposed = replacement;
      final List<ComponentChange> accounted = componentChanges;

      // ---- may the agent do this? Policy decides; nothing here does.
      stage = Stage.DECIDING;
      PolicyDecision verdict =
          activities.evaluateAction(
              EvaluateActionRequest.newBuilder()
                  .setCtx(
                      travelerRequest
                          ? actorCtx(tenant, tripId, d.getAffected().getRequestedBy())
                          : ctx(tenant, tripId, ""))
                  .setTripId(tripId)
                  .setTravelerId(trip.getTravelerId())
                  .setAction(ACTION)
                  .setIncrementalCost(incremental)
                  .setProposed(proposed)
                  .setIntent(intent)
                  .setContextRef(disruptionId)
                  .build());
      String autonomy = verdict.getOutcome().name();
      state
          .setReplacementBundleId(proposed.getBundleId())
          .setIncrementalCost(incremental)
          .setPolicyDecisionId(verdict.getDecisionId())
          .setOptimizationRunId(optimized.getOptimizationRunId())
          .setAutonomyOutcome(autonomy);

      // ---- the immutable decision record (also moves the disruption to DECISION_READY)
      activities.recordDecision(
          RecordRecoveryDecisionRequest.newBuilder()
              .setCtx(ctx(tenant, tripId, ""))
              .setDisruptionId(disruptionId)
              .setDecision(
                  RecoveryDecision.newBuilder()
                      .setDisruptionId(disruptionId)
                      .setOriginalItinerary(original)
                      .setTrigger(trigger)
                      .setCandidatesSearched(bundles.size())
                      .setCandidatesPermitted(permitted.size())
                      .setCandidatesFeasible(feasible)
                      .addAllRejected(rejected)
                      .setSelected(proposed)
                      .setSelectedRanking(selectedRanking)
                      .setOptimizationRunId(optimized.getOptimizationRunId())
                      .setOriginalTotal(order.getTotal())
                      .setReplacementTotal(proposed.getTotal())
                      .setIncrementalCost(incremental)
                      .setPolicyDecision(verdict)
                      .setAutonomyOutcome(autonomy)
                      .setAgentPrincipal(DisruptionRecovery.AGENT)
                      .setWorkflowType(DisruptionRecovery.WORKFLOW_TYPE)
                      .setWorkflowVersion(DisruptionRecovery.WORKFLOW_VERSION)
                      .addAllComponentChanges(accounted)
                      .setLearningMode(learned.getMode())
                      .setLearningProfileId(learned.getProfileId())
                      .setLearningFallback(learned.getFallbackReason()))
              .build());

      // ---- narrate (optional; the decision is the decision with or without a paragraph)
      String explanation =
          explain(
              tenant,
              tripId,
              d,
              original,
              proposed,
              optimized,
              verdict,
              incremental,
              bundles.size(),
              permitted.size(),
              autonomy);
      if (explanation != null) {
        state.setExplanation(explanation);
      }

      // ---- authorization
      String approvalId = null;
      String approvedBy = null;
      String approvalComment = null;
      switch (verdict.getOutcome()) {
        case ALLOW -> {
          transition(
              tenant,
              disruptionId,
              DisruptionStatus.AUTO_ALLOWED,
              b -> b.setReason("policy allows autonomous rebooking").setRecovery(state.build()));
        }
        case ALLOW_WITH_APPROVAL, ALLOW_WITH_TRAVELER_PAYMENT -> {
          stage = Stage.AWAITING_APPROVAL;
          String role =
              verdict.getApproversCount() > 0
                  ? verdict.getApprovers(0).getRole()
                  : verdict.getOutcome()
                          == io.travelos.contracts.policy.v1.Outcome.ALLOW_WITH_TRAVELER_PAYMENT
                      ? "TRAVELER"
                      : "MANAGER";
          Disruption waiting =
              transition(
                  tenant,
                  disruptionId,
                  DisruptionStatus.HUMAN_REQUIRED,
                  b ->
                      b.setReason(reasonSummary(verdict))
                          .setApproverRole(role)
                          .setRecovery(state.build()));
          approvalId = waiting.getRecovery().getApprovalId();
          state.setApprovalId(approvalId).setApprovalStatus("PENDING");
          String outcome = awaitDecision(tenant, disruptionId);
          if (outcome == null) {
            return fail(
                tenant,
                disruptionId,
                "MANUAL_INTERVENTION_REQUIRED",
                "APPROVAL",
                "APPROVAL_TIMED_OUT",
                "no decision within " + APPROVAL_TIMEOUT);
          }
          if (!"APPROVED".equals(outcome)) {
            return fail(
                tenant,
                disruptionId,
                "MANUAL_INTERVENTION_REQUIRED",
                "APPROVAL",
                "APPROVAL_REJECTED",
                "rejected by " + (decision == null ? "a manager" : decision.decidedBy()));
          }
          approvedBy = decision == null ? "human/unknown" : decision.decidedBy();
          approvalComment = decision == null ? null : decision.comment();
          state.setApprovalStatus("APPROVED");
        }
        default -> {
          String code =
              verdict.getReasonsCount() > 0 ? verdict.getReasons(0).getCode() : "CHANGE_DENIED";
          return fail(
              tenant,
              disruptionId,
              "MANUAL_INTERVENTION_REQUIRED",
              "POLICY",
              code,
              "policy denies the change: " + reasonSummary(verdict));
        }
      }

      // ---- change the order (the only transaction, idempotent by key)
      stage = Stage.CHANGING;
      transition(
          tenant, disruptionId, DisruptionStatus.CHANGING, b -> b.setRecovery(state.build()));
      String key = "TRIP:" + tripId + ":DISRUPTION:" + disruptionId + ":CHANGE:1";
      ChangeOrderCommand.Builder command =
          ChangeOrderCommand.newBuilder()
              .setCtx(ctx(tenant, tripId, key))
              .setOrderId(order.getOrderId())
              .setDisruptionId(disruptionId)
              .setReplacement(proposed)
              .setPolicyDecisionId(verdict.getDecisionId())
              .setOptimizationRunId(optimized.getOptimizationRunId())
              .addPassengers(
                  Passenger.newBuilder()
                      .setGivenName(trip.getTraveler().getGivenName())
                      .setFamilyName(trip.getTraveler().getFamilyName())
                      .setEmail(trip.getTraveler().getEmail()))
              .setPaymentToken(paymentToken())
              .setReason("disruption " + disruptionId + ": " + trigger);
      if (approvalId != null) {
        command.setApprovalId(approvalId);
      }
      Order changed = changing.changeOrder(command.build());
      OrderChange applied = latestChange(changed, key);
      if (changed.getStatus() != OrderStatus.CHANGED
          || applied == null
          || !"APPLIED".equals(applied.getStatus())) {
        String code =
            applied != null && !applied.getFailureCode().isBlank()
                ? applied.getFailureCode()
                : "ORDER_" + changed.getStatus().name();
        return fail(
            tenant,
            disruptionId,
            "FAILED",
            "CHANGE",
            code,
            "order " + changed.getOrderId() + " could not be changed (" + code + ")",
            approvalId,
            approvedBy,
            approvalComment);
      }

      // ---- resolved
      OrderItem current = currentItem(changed);
      RecoveryOutcome.Builder outcome =
          RecoveryOutcome.newBuilder()
              .setDisruptionId(disruptionId)
              .setStatus(DisruptionStatus.RESOLVED)
              .setChangedOrderId(changed.getOrderId())
              .setSupplierResult(
                  SupplierResult.newBuilder()
                      .setExternalOrderId(changed.getExternalOrderId())
                      .setStatus("CHANGED")
                      .setRecordLocator(current == null ? "" : current.getRecordLocator())
                      .setCharged(changed.getTotal()));
      if (approvalId != null) {
        outcome.setApprovalId(approvalId).setApprovedBy(approvedBy == null ? "" : approvedBy);
        if (approvalComment != null) {
          outcome.setApprovalComment(approvalComment);
        }
      }
      activities.recordOutcome(
          RecordRecoveryOutcomeRequest.newBuilder()
              .setCtx(ctx(tenant, tripId, ""))
              .setDisruptionId(disruptionId)
              .setOutcome(outcome)
              .build());
      stage = Stage.RESOLVED;
      return new Outcome(
          disruptionId, "RESOLVED", changed.getOrderId(), selected.getBundleId(), null, null);
    } catch (ActivityFailure e) {
      String code = failureCode(e);
      log.error("recovery {} failed at {}: {}", disruptionId, stage, code);
      return fail(
          tenant,
          disruptionId,
          "FAILED",
          stageName(stage),
          code,
          e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
    }
  }

  @Override
  public void approvalDecided(ApprovalDecision decision) {
    this.decision = decision;
  }

  @Override
  public Stage stage() {
    return stage;
  }

  // ------------------------------------------------------------------ helpers

  private Disruption transition(
      String tenant,
      String disruptionId,
      DisruptionStatus to,
      java.util.function.Consumer<TransitionDisruptionRequest.Builder> customize) {
    TransitionDisruptionRequest.Builder b =
        TransitionDisruptionRequest.newBuilder()
            .setCtx(ctx(tenant, disruptionId, ""))
            .setDisruptionId(disruptionId)
            .setTo(to);
    customize.accept(b);
    return activities.transition(b.build());
  }

  private Outcome fail(
      String tenant,
      String disruptionId,
      String status,
      String stageName,
      String code,
      @Nullable String message) {
    return fail(tenant, disruptionId, status, stageName, code, message, null, null, null);
  }

  private Outcome fail(
      String tenant,
      String disruptionId,
      String status,
      String stageName,
      String code,
      @Nullable String message,
      @Nullable String approvalId,
      @Nullable String approvedBy,
      @Nullable String comment) {
    try {
      RecoveryOutcome.Builder outcome =
          RecoveryOutcome.newBuilder()
              .setDisruptionId(disruptionId)
              .setStatus(DisruptionStatus.valueOf(status))
              .setFailureStage(stageName)
              .setFailureCode(code)
              .setMessage(message == null ? "" : message);
      if (approvalId != null) {
        outcome.setApprovalId(approvalId).setApprovedBy(approvedBy == null ? "" : approvedBy);
        if (comment != null) {
          outcome.setApprovalComment(comment);
        }
      }
      activities.recordOutcome(
          RecordRecoveryOutcomeRequest.newBuilder()
              .setCtx(ctx(tenant, disruptionId, ""))
              .setDisruptionId(disruptionId)
              .setOutcome(outcome)
              .build());
    } catch (ActivityFailure e) {
      log.error(
          "recovery {}: could not record outcome {} ({})", disruptionId, code, e.getMessage());
    }
    stage = Stage.valueOf(status);
    return new Outcome(disruptionId, status, null, null, stageName, code);
  }

  /** Best effort; the recovery is the recovery whether or not it gets a paragraph. */
  private @Nullable String explain(
      String tenant,
      String tripId,
      Disruption d,
      Bundle original,
      Bundle selected,
      OptimizeTripResponse optimized,
      PolicyDecision verdict,
      Money incremental,
      int searched,
      int permitted,
      String autonomy) {
    try {
      ExplainDisruptionResponse response =
          narration.explain(
              ExplainDisruptionRequest.newBuilder()
                  .setCtx(ctx(tenant, tripId, ""))
                  .setTripId(tripId)
                  .setDisruptionId(d.getDisruptionId())
                  .setAudience("TRAVELER")
                  .setDisruptionType(d.getType().name())
                  .setSupplier(d.getSupplier())
                  .setSupplierReason(d.getReason())
                  .setOriginal(original)
                  .setReplacement(selected)
                  .addAllRanking(optimized.getRankingList())
                  .setPolicyDecision(verdict)
                  .setIncrementalCost(incremental)
                  .setCandidatesSearched(searched)
                  .setCandidatesPermitted(permitted)
                  .setAutonomyOutcome(autonomy)
                  .build());
      return response.getExplanation().isBlank() ? null : response.getExplanation();
    } catch (ActivityFailure e) {
      log.warn(
          "recovery {}: explanation unavailable ({}); continuing without it",
          d.getDisruptionId(),
          failureCode(e));
      return null;
    }
  }

  /** Waits for the signal, re-reading the disruption periodically so a lost signal only delays. */
  private @Nullable String awaitDecision(String tenant, String disruptionId) {
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
      Disruption current = activities.loadDisruption(tenant, disruptionId);
      String status = current.getRecovery().getApprovalStatus();
      if ("APPROVED".equals(status) || "REJECTED".equals(status)) {
        decision =
            new ApprovalDecision(
                current.getRecovery().getApprovalId(), status, "human/unknown", null);
        return status;
      }
    }
    return null;
  }

  static Bundle originalItinerary(Order order) {
    Bundle.Builder b =
        Bundle.newBuilder().setBundleId(order.getBundleId()).setTotal(order.getTotal());
    OrderItem current = currentItem(order);
    if (current != null) {
      b.addOffers(current.getOffer());
    }
    return b.build();
  }

  static @Nullable OrderItem currentItem(Order order) {
    OrderItem current = null;
    for (OrderItem item : order.getItemsList()) {
      if (item.getStatus() == OrderItemStatus.ITEM_CONFIRMED) {
        current = item;
      }
    }
    return current;
  }

  static String currentProviderOfferId(Order order) {
    OrderItem current = currentItem(order);
    return current == null ? "" : current.getOffer().getProviderOfferId();
  }

  static @Nullable OrderChange latestChange(Order order, String key) {
    for (OrderChange c : order.getChangesList()) {
      if (c.getIdempotencyKey().equals(key)) {
        return c;
      }
    }
    return null;
  }

  private static RequestContext ctx(String tenant, String correlationId, String idempotencyKey) {
    return RequestContext.newBuilder()
        .setTenantId(tenant)
        .setCorrelationId(correlationId)
        .setCausationId(Workflow.getInfo().getWorkflowId())
        .setIdempotencyKey(idempotencyKey)
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(DisruptionRecovery.AGENT))
        .build();
  }

  /**
   * Phase 6: the policy actor for a traveler's own request is the person, so the human rules apply
   * (the approval threshold), not the agent's rebooking autonomy. Everything else the workflow does
   * stays the agent's.
   */
  private static RequestContext actorCtx(String tenant, String correlationId, String requestedBy) {
    Principal.Kind kind =
        requestedBy.startsWith("human/") ? Principal.Kind.HUMAN : Principal.Kind.AGENT;
    return ctx(tenant, correlationId, "").toBuilder()
        .setPrincipal(
            Principal.newBuilder()
                .setKind(kind)
                .setId(requestedBy.isBlank() ? DisruptionRecovery.AGENT : requestedBy))
        .build();
  }

  /**
   * Phase 6: a traveler-requested window replaces the intent's outbound window: depart no earlier
   * than asked, arrive by the end of the asked window (or a day after its start when open-ended).
   */
  static TravelIntent requestedIntent(TravelIntent intent, AffectedSegment affected) {
    if (!affected.hasRequestedNotBefore()) {
      return intent;
    }
    Timestamp notBefore = affected.getRequestedNotBefore();
    Timestamp deadline =
        affected.hasRequestedNotAfter()
            ? Timestamp.newBuilder()
                .setSeconds(affected.getRequestedNotAfter().getSeconds() + 24 * 3600)
                .build()
            : Timestamp.newBuilder().setSeconds(notBefore.getSeconds() + 36 * 3600).build();
    TravelIntent.Builder b =
        intent.toBuilder().setEarliestDeparture(notBefore).setArrivalDeadline(deadline);
    if (intent.hasReturnAfter() && intent.getReturnAfter().getSeconds() < deadline.getSeconds()) {
      // the return cannot precede the new arrival; keep it a plan, not a constraint
      b.clearReturnAfter().clearLatestReturn();
    }
    return b.build();
  }

  private static String iso(Timestamp t) {
    return java.time.Instant.ofEpochSecond(t.getSeconds()).toString();
  }

  /** The trip's windows, but never in the past: a replacement departs no earlier than now. */
  static SearchAirRequest searchRequest(
      String tenant, String tripId, TravelIntent intent, long nowMillis) {
    long nowSeconds = nowMillis / 1000;
    Timestamp notBefore =
        intent.getEarliestDeparture().getSeconds() >= nowSeconds
            ? intent.getEarliestDeparture()
            : Timestamp.newBuilder().setSeconds(nowSeconds).build();
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
                    .setNotBefore(notBefore)
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

  private static String stageName(Stage stage) {
    return switch (stage) {
      case LOADING -> "CONTEXT";
      case SEARCHING_ALTERNATIVES -> "SEARCH";
      case EVALUATING_POLICY -> "POLICY";
      case OPTIMIZING -> "OPTIMIZATION";
      case DECIDING -> "DECISION";
      case AWAITING_APPROVAL -> "APPROVAL";
      default -> "CHANGE";
    };
  }
}
