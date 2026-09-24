package io.travelos.workflows.trip;

import com.google.protobuf.Timestamp;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
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
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.trip.v1.ApplyIntentExtractionRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripAlternative;
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

  /** Phase 3: how long a quoted plan waits for a person before the trip fails. */
  static final Duration PURCHASE_TIMEOUT = Duration.ofHours(72);

  static final Duration PURCHASE_POLL = Duration.ofHours(1);

  private TripPlanning.Stage stage = TripPlanning.Stage.LOADING;
  private @Nullable ApprovalDecision decision;
  private TripPlanning.@Nullable PurchaseAuthorized purchase;
  private TripPlanning.@Nullable SelectionChanged selection;
  private boolean refreshRequested;

  /** The plan as it stands while a person decides; mutable because quotes and selections move. */
  static final class Plan {
    Bundle selected;
    PolicyDecision decision;
    Money total;
    @Nullable String explanation;

    Plan(Bundle selected, PolicyDecision decision, @Nullable String explanation) {
      this.selected = selected;
      this.decision = decision;
      this.total = selected.getTotal();
      this.explanation = explanation;
    }
  }

  /** Set by the cancelled signal: the requester withdrew the trip while it was being planned. */
  private boolean withdrawn;

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
      SearchAirResponse search =
          activities.search(
              searchRequest(tenant, tripId, intent).toBuilder()
                  .addAllNegotiatedRates(Governed.airRates(governance(tenant, tripId, trip)))
                  .build());
      if (search.getOffersCount() == 0) {
        String detail =
            search.getErrorsCount() == 0
                ? "no supplier returned offers"
                : search.getErrors(0).getProvider() + ": " + search.getErrors(0).getCode();
        return fail(tenant, tripId, "SEARCH", "NO_OFFERS", detail);
      }
      List<Offer> offers = Purchase.filter(search.getOffersList(), intent);
      if (offers.isEmpty()) {
        return fail(
            tenant,
            tripId,
            "SEARCH",
            "NO_OFFERS_MATCHING_PREFERENCES",
            search.getOffersCount()
                + " offer(s) found, none satisfies the stated preferences (nonstop / refundable /"
                + " stops)");
      }
      List<Bundle> bundles = new ArrayList<>();
      for (Offer offer : offers) {
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
                  .setScope(Governed.scopeOf(trip))
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
                                  .setExperience(0.1))
                          .addAllPreferredCarriers(Purchase.preferredCarriers(intent)))
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

      // ---- Phase 3: purchase authority. Either policy granted it for this plan (recorded as a
      // POLICY_AUTONOMY authorization by Travel Core) or a person confirms the quoted plan first.
      Plan plan = new Plan(selected, selectedDecision, explanation);
      boolean confirm = Purchase.confirmRequired(trip, selectedDecision);
      if (confirm) {
        String verdict =
            quoteUntilAuthorized(
                tenant,
                tripId,
                plan,
                permitted,
                decisions,
                optimized,
                intent,
                bundles.size(),
                "planned; a person confirms the purchase");
        if (verdict == null) {
          return fail(
              tenant,
              tripId,
              "PURCHASE",
              "PURCHASE_TIMED_OUT",
              "no purchase authorization within " + PURCHASE_TIMEOUT);
        }
        if ("CANCELLED".equals(verdict)) {
          stage = TripPlanning.Stage.CANCELLED;
          return new Outcome(tripId, "CANCELLED", null, null, null);
        }
      }
      final Bundle chosen = plan.selected;
      final PolicyDecision chosenDecision = plan.decision;
      final Money chosenTotal = plan.total;
      final String chosenExplanation = plan.explanation;
      final boolean autonomous = !confirm && chosenDecision.getAutonomousPurchase();
      final Timestamp quoteExpiry = Purchase.quoteExpiry(chosen, Workflow.currentTimeMillis());

      // ---- approval
      String approvalId = null;
      if (chosenDecision.getRequiresApproval()) {
        stage = TripPlanning.Stage.AWAITING_APPROVAL;
        String role =
            chosenDecision.getApproversCount() > 0
                ? chosenDecision.getApprovers(0).getRole()
                : "MANAGER";
        Trip awaiting =
            transition(
                tenant,
                tripId,
                TripStatus.AWAITING_APPROVAL,
                b -> {
                  b.setSelectedBundleId(chosen.getBundleId())
                      .setOptimizationRunId(optimized.getOptimizationRunId())
                      .setPolicyDecisionId(chosenDecision.getDecisionId())
                      .setTotal(chosenTotal)
                      .setApproverRole(role)
                      .addAllApprovalChain(Governed.chain(chosenDecision, role))
                      .setApprovalExpiresAfterSeconds(
                          chosenDecision.getApprovalExpiresAfterSeconds())
                      .setReason(reasonSummary(chosenDecision))
                      .setAutonomousPurchase(autonomous)
                      .setQuoteExpiresAt(quoteExpiry)
                      .setConditions(Purchase.conditions(chosen));
                  if (chosenExplanation != null) {
                    b.setExplanation(chosenExplanation);
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
        if ("CANCELLED".equals(verdict)) {
          // the requester withdrew the trip while it waited; Travel Core already recorded it
          stage = TripPlanning.Stage.CANCELLED;
          return new Outcome(tripId, "CANCELLED", null, null, null);
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
              b.setSelectedBundleId(chosen.getBundleId())
                  .setOptimizationRunId(optimized.getOptimizationRunId())
                  .setPolicyDecisionId(chosenDecision.getDecisionId())
                  .setTotal(chosenTotal)
                  .setReason(
                      confirm
                          ? "purchase authorized; in policy, no approval required"
                          : "in policy, no approval required")
                  .setAutonomousPurchase(autonomous)
                  .setQuoteExpiresAt(quoteExpiry)
                  .setConditions(Purchase.conditions(chosen));
              if (chosenExplanation != null) {
                b.setExplanation(chosenExplanation);
              }
            });
      }

      // ---- booking: only while the departure is still ahead and nobody withdrew the trip
      stage = TripPlanning.Stage.BOOKING;
      if (departurePassed(intent.getArrivalDeadline())) {
        return fail(
            tenant,
            tripId,
            "REVALIDATION",
            "DEPARTURE_PASSED",
            "the outbound window closed at "
                + ItineraryFlow.instant(intent.getArrivalDeadline())
                + " while the trip waited; nothing was booked");
      }
      // Phase 7: the scope's budget, reserved under its lock; a hard budget that does not fit stops
      // the purchase here (a soft one was judged by policy and, when required, approved).
      io.travelos.contracts.policy.v1.BudgetReservation reserved =
          reserveBudget(tenant, tripId, trip, chosenTotal);
      if (reserved != null
          && reserved.getStatus() == io.travelos.contracts.policy.v1.BudgetStatus.EXCEEDED
          && reserved.getHard()) {
        return fail(tenant, tripId, "BOOKING", "BUDGET_EXCEEDED", reserved.getMessage());
      }
      // Travel Core consumes exactly one purchase authorization covering the plan and price here,
      // or refuses. A refusal (the authorization lapsed) sends the trip back to a person.
      while (true) {
        try {
          if (beginBooking(
                  tenant, tripId, b -> b.setReason("booking").setAutonomousPurchase(autonomous))
              == null) {
            stage = TripPlanning.Stage.CANCELLED;
            return new Outcome(tripId, "CANCELLED", null, null, null);
          }
          break;
        } catch (ActivityFailure e) {
          if (!Purchase.notAuthorized(e)) {
            throw e;
          }
          String verdict =
              quoteUntilAuthorized(
                  tenant,
                  tripId,
                  plan,
                  permitted,
                  decisions,
                  optimized,
                  intent,
                  bundles.size(),
                  "the purchase authorization lapsed before booking; confirm again");
          if (verdict == null) {
            return fail(
                tenant, tripId, "PURCHASE", "PURCHASE_TIMED_OUT", "no purchase authorization");
          }
          if ("CANCELLED".equals(verdict)) {
            stage = TripPlanning.Stage.CANCELLED;
            return new Outcome(tripId, "CANCELLED", null, null, null);
          }
          transition(
              tenant, tripId, TripStatus.APPROVED, b -> b.setReason("purchase re-authorized"));
          stage = TripPlanning.Stage.BOOKING;
        }
      }
      CreateOrderCommand.Builder command =
          CreateOrderCommand.newBuilder()
              .setCtx(ctx(tenant, tripId, tripId + ":CREATE-ORDER:1"))
              .setTripId(tripId)
              .setTravelerId(trip.getTravelerId())
              .setBundle(plan.selected)
              .setPolicyDecisionId(plan.decision.getDecisionId())
              .setOptimizationRunId(optimized.getOptimizationRunId())
              .addPassengers(passenger(tenant, tripId, trip))
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
      Outcome cancelled = cancelledMeanwhile(tenant, tripId, e);
      if (cancelled != null) {
        return cancelled;
      }
      String code = failureCode(e);
      log.error("trip {} failed at {}: {}", tripId, stage, code);
      return fail(tenant, tripId, stageName(stage), code, FailureCodes.message(e));
    }
  }

  @Override
  public void approvalDecided(TripPlanning.ApprovalDecision decision) {
    this.decision = decision;
  }

  @Override
  public void cancelled(String reason) {
    this.withdrawn = true;
  }

  @Override
  public void purchaseAuthorized(TripPlanning.PurchaseAuthorized authorized) {
    this.purchase = authorized;
  }

  @Override
  public void selectionChanged(TripPlanning.SelectionChanged changed) {
    this.selection = changed;
  }

  @Override
  public void refreshQuote(String reason) {
    this.refreshRequested = true;
  }

  /**
   * Phase 3: QUOTED until a person authorizes the purchase, choosing between alternatives or
   * refreshing the price meanwhile. AUTHORIZED | CANCELLED, or null when nobody acted in time.
   */
  private @Nullable String quoteUntilAuthorized(
      String tenant,
      String tripId,
      Plan plan,
      List<Bundle> permitted,
      Map<String, PolicyDecision> decisions,
      OptimizeTripResponse optimized,
      TravelIntent intent,
      int searched,
      String reason) {
    String why = reason;
    while (true) {
      stage = TripPlanning.Stage.AWAITING_PURCHASE;
      final Bundle current = plan.selected;
      final PolicyDecision currentDecision = plan.decision;
      final Money currentTotal = plan.total;
      final String currentExplanation = plan.explanation;
      final String currentReason = why;
      List<TripAlternative> alternatives =
          Purchase.alternatives(optimized.getRankingList(), permitted, decisions);
      transition(
          tenant,
          tripId,
          TripStatus.QUOTED,
          b -> {
            b.setSelectedBundleId(current.getBundleId())
                .setOptimizationRunId(optimized.getOptimizationRunId())
                .setPolicyDecisionId(currentDecision.getDecisionId())
                .setTotal(currentTotal)
                .setReason(currentReason)
                .setQuoteExpiresAt(Purchase.quoteExpiry(current, Workflow.currentTimeMillis()))
                .setConditions(Purchase.conditions(current))
                .addAllAlternatives(alternatives);
            if (currentExplanation != null) {
              b.setExplanation(currentExplanation);
            }
          });
      String verdict = awaitPurchase(tenant, tripId, current.getBundleId());
      if (verdict == null || "CANCELLED".equals(verdict) || "AUTHORIZED".equals(verdict)) {
        return verdict;
      }
      if (verdict.startsWith("SELECTION:")) {
        String bundleId = verdict.substring("SELECTION:".length());
        Bundle chosen =
            permitted.stream()
                .filter(b -> b.getBundleId().equals(bundleId))
                .findFirst()
                .orElse(null);
        if (chosen == null) {
          why = bundleId + " is not one of the permitted plans; the quote stands";
          continue;
        }
        plan.selected = chosen;
        plan.decision = decisions.get(chosen.getBundleId());
        plan.total = chosen.getTotal();
        plan.explanation =
            explain(
                tenant,
                tripId,
                intent,
                chosen,
                optimized,
                plan.decision,
                searched,
                permitted.size());
        why = "re-selected " + bundleId;
        continue;
      }
      // REFRESH
      Bundle fresh = requote(tenant, tripId, plan.selected);
      if (fresh == null) {
        why = "the plan could not be re-quoted; the earlier quote stands";
      } else {
        plan.selected = fresh;
        plan.total = fresh.getTotal();
        why = "quote refreshed";
      }
    }
  }

  /**
   * Phase 4: the passenger as the suppliers need them, from Enterprise Context at booking time.
   * When Enterprise Context knows no profile (or is unreachable), the trip's own snapshot (names,
   * email) is what the supplier gets; a supplier that needs more refuses with a clear code.
   */
  private Passenger passenger(String tenant, String tripId, Trip trip) {
    try {
      Passenger full = activities.passenger(tenant, tripId, trip.getTravelerId());
      if (full != null && !full.getGivenName().isBlank() && !full.getFamilyName().isBlank()) {
        return full;
      }
    } catch (ActivityFailure e) {
      log.warn(
          "trip {}: passenger details unavailable ({}); booking on the trip's snapshot",
          tripId,
          failureCode(e));
    }
    return Passenger.newBuilder()
        .setGivenName(trip.getTraveler().getGivenName())
        .setFamilyName(trip.getTraveler().getFamilyName())
        .setEmail(trip.getTraveler().getEmail())
        .build();
  }

  /** The plan priced again by its suppliers, or null when any offer can no longer be quoted. */
  private @Nullable Bundle requote(String tenant, String tripId, Bundle selected) {
    Bundle.Builder fresh = Bundle.newBuilder().setBundleId(selected.getBundleId());
    long total = 0;
    for (Offer o : selected.getOffersList()) {
      QuoteOfferResponse quote;
      try {
        quote =
            activities.quote(
                QuoteOfferRequest.newBuilder()
                    .setCtx(ctx(tenant, tripId, ""))
                    .setProvider(o.getProvider())
                    .setProviderOfferId(o.getProviderOfferId())
                    .build());
      } catch (ActivityFailure e) {
        log.warn(
            "trip {}: offer {} could not be re-quoted ({})",
            tripId,
            o.getOfferId(),
            failureCode(e));
        return null;
      }
      Offer current = ItineraryFlow.requoted(o, quote.getOffer());
      total += current.getTotal().getAmountMinor();
      fresh.addOffers(current);
    }
    return fresh
        .setTotal(
            Money.newBuilder().setCurrency(selected.getTotal().getCurrency()).setAmountMinor(total))
        .build();
  }

  /**
   * Waits for a person: AUTHORIZED | CANCELLED | REFRESH | SELECTION:&lt;bundleId&gt;, or null on
   * timeout. The trip is re-read on every poll, so a lost signal only delays.
   */
  private @Nullable String awaitPurchase(String tenant, String tripId, String selectedBundleId) {
    Duration waited = Duration.ZERO;
    while (waited.compareTo(PURCHASE_TIMEOUT) < 0) {
      Duration slice =
          PURCHASE_POLL.compareTo(PURCHASE_TIMEOUT.minus(waited)) < 0
              ? PURCHASE_POLL
              : PURCHASE_TIMEOUT.minus(waited);
      Workflow.await(
          slice, () -> purchase != null || selection != null || refreshRequested || withdrawn);
      if (withdrawn) {
        return "CANCELLED";
      }
      if (selection != null) {
        String bundle = selection.bundleId();
        selection = null;
        return "SELECTION:" + bundle;
      }
      if (refreshRequested) {
        refreshRequested = false;
        return "REFRESH";
      }
      if (purchase != null) {
        TripPlanning.PurchaseAuthorized signalled = purchase;
        purchase = null;
        if (signalled.bundleId() == null || signalled.bundleId().equals(selectedBundleId)) {
          return "AUTHORIZED";
        }
        // a confirmation of a plan that is no longer the quoted one: keep waiting
      }
      waited = waited.plus(slice);
      Trip current = activities.loadTrip(tenant, tripId);
      if (current.getStatus() == TripStatus.CANCELLED) {
        return "CANCELLED";
      }
      if (!current.getSelectedBundleId().isBlank()
          && !current.getSelectedBundleId().equals(selectedBundleId)) {
        return "SELECTION:" + current.getSelectedBundleId();
      }
      if (current.hasPurchase()
          && "ACTIVE".equals(current.getPurchase().getStatus())
          && current.getPurchase().getBundleId().equals(selectedBundleId)) {
        return "AUTHORIZED";
      }
    }
    return null;
  }

  /**
   * A lifecycle move Travel Core refused because the requester cancelled the trip meanwhile is not
   * a failure: the trip is CANCELLED, nothing was booked, and the workflow simply ends.
   */
  private @Nullable Outcome cancelledMeanwhile(String tenant, String tripId, ActivityFailure e) {
    boolean refused =
        e.getCause() instanceof ApplicationFailure af && "FAILED_PRECONDITION".equals(af.getType());
    if (!refused && !withdrawn) {
      return null;
    }
    try {
      Trip current = activities.loadTrip(tenant, tripId);
      if (current.getStatus() == TripStatus.CANCELLED) {
        log.info("trip {} was cancelled while it was being planned; nothing booked", tripId);
        stage = TripPlanning.Stage.CANCELLED;
        return new Outcome(tripId, "CANCELLED", null, null, null);
      }
    } catch (ActivityFailure reload) {
      log.warn("trip {}: could not re-read after a refused transition", tripId);
    }
    return null;
  }

  /** Moves the trip to BOOKING, or returns null when it was cancelled while it waited. */
  private @Nullable Trip beginBooking(
      String tenant,
      String tripId,
      java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
    try {
      return transition(tenant, tripId, TripStatus.BOOKING, customize);
    } catch (ActivityFailure e) {
      if (cancelledMeanwhile(tenant, tripId, e) != null) {
        return null;
      }
      throw e;
    }
  }

  /** The window is closed once its deadline is not ahead of the workflow's clock. */
  private static boolean departurePassed(Timestamp deadline) {
    java.time.Instant now = java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis());
    return !ItineraryFlow.instant(deadline).isAfter(now);
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
    public @Nullable Trip beginBooking(
        String tenant,
        String tripId,
        java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
      return TripWorkflowImpl.this.beginBooking(tenant, tripId, customize);
    }

    @Override
    public @Nullable String awaitDecision(String tenant, String tripId) {
      return TripWorkflowImpl.this.awaitDecision(tenant, tripId);
    }

    @Override
    public @Nullable String awaitPurchase(String tenant, String tripId, String selectedBundleId) {
      return TripWorkflowImpl.this.awaitPurchase(tenant, tripId, selectedBundleId);
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
    public Passenger passenger(String tenant, String tripId, Trip trip) {
      return TripWorkflowImpl.this.passenger(tenant, tripId, trip);
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
      Workflow.await(slice, () -> decision != null || withdrawn);
      if (withdrawn) {
        return "CANCELLED";
      }
      if (decision != null) {
        return decision.decision();
      }
      waited = waited.plus(slice);
      Trip current = activities.loadTrip(tenant, tripId);
      if (current.getStatus() == TripStatus.CANCELLED) {
        return "CANCELLED";
      }
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

  /** Phase 7: the governance of the trip's scope; null when policy cannot say (no agreements). */
  private io.travelos.contracts.policy.v1.@Nullable Governance governance(
      String tenant, String tripId, Trip trip) {
    try {
      return activities.governance(
          io.travelos.contracts.policy.v1.GetGovernanceRequest.newBuilder()
              .setCtx(ctx(tenant, tripId, ""))
              .setTravelerId(trip.getTravelerId())
              .setScope(Governed.scopeOf(trip))
              .build());
    } catch (ActivityFailure e) {
      log.warn(
          "trip {}: governance unavailable ({}); searching public fares", tripId, failureCode(e));
      return null;
    }
  }

  private io.travelos.contracts.policy.v1.@Nullable BudgetReservation reserveBudget(
      String tenant, String tripId, Trip trip, io.travelos.contracts.common.v1.Money total) {
    try {
      return activities.reserveBudget(
          io.travelos.contracts.policy.v1.ReserveBudgetRequest.newBuilder()
              .setCtx(ctx(tenant, tripId, tripId + ":RESERVE-BUDGET:1"))
              .setTripId(tripId)
              .setTravelerId(trip.getTravelerId())
              .setScope(Governed.scopeOf(trip))
              .setAmount(total)
              .build());
    } catch (ActivityFailure e) {
      log.warn(
          "trip {}: budget could not be reserved ({}); proceeding without", tripId, failureCode(e));
      return null;
    }
  }

  private static SearchAirRequest searchRequest(String tenant, String tripId, TravelIntent intent) {
    SearchAirRequest.Builder b =
        SearchAirRequest.newBuilder()
            .setCtx(ctx(tenant, tripId, ""))
            .setOrigin(intent.getOrigin())
            .setDestination(intent.getDestination())
            .setPassengers(Math.max(1, intent.getTravelers()))
            .addAllCabins(Purchase.cabins(intent))
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
    return FailureCodes.of(e);
  }

  private static String stageName(TripPlanning.Stage stage) {
    return switch (stage) {
      case LOADING -> "CONTEXT";
      case UNDERSTANDING -> "INTENT";
      case SEARCHING -> "SEARCH";
      case EVALUATING_POLICY -> "POLICY";
      case OPTIMIZING -> "OPTIMIZATION";
      case AWAITING_PURCHASE -> "PURCHASE";
      case AWAITING_APPROVAL -> "APPROVAL";
      case REVALIDATING -> "REVALIDATION";
      case COMPENSATING -> "COMPENSATION";
      default -> "BOOKING";
    };
  }
}
